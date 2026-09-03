package io.github.yeetingwaterbottle.stylo2.haptics

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground app watcher.
 *
 * Why an AccessibilityService instead of UsageStatsManager: an input method
 * (e.g. a keyboard's handwriting field) is its own window and never becomes the
 * "foreground app", so UsageStats cannot see it. Accessibility events do report
 * TYPE_WINDOW_STATE_CHANGED for IME windows, which lets keyboards be whitelisted too.
 *
 * The service config sets canRetrieveWindowContent="false", so this only receives
 * window-change events and cannot read screen content.
 */
class PenHapticsService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Current foreground package, used to sync state once the SDK connects. */
    @Volatile
    private var currentPkg: String? = null

    private var retryJob: Job? = null
    private var retries = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "PenHapticsService connected")
        instance = this

        scope.launch {
            Haptics.client(this@PenHapticsService).connection.collect { c ->
                when (c.state) {
                    StylusVibrationClient.State.CONNECTED_SDK -> {
                        retries = 0
                        Haptics.onConnected(this@PenHapticsService, currentPkg)
                    }
                    StylusVibrationClient.State.ERROR -> {
                        Haptics.onDisconnected()
                        scheduleRetry(c.error)
                    }
                    StylusVibrationClient.State.DISCONNECTED -> Haptics.onDisconnected()
                    else -> Unit
                }
            }
        }
        Haptics.bind(this)
    }

    /**
     * Retry a failed bind.
     *
     * This service starts at boot, and com.oplus.ipemanager may not be ready yet;
     * without a retry the pen would stay silent for the whole session and the user
     * would have to open the app and bind by hand.
     *
     * Only ERROR is retried. DISCONNECTED is also what a deliberate Unbind from the
     * UI looks like, and reconnecting after the remote process restarts is already
     * handled by BIND_AUTO_CREATE.
     */
    private fun scheduleRetry(err: StylusVibrationClient.SdkError?) {
        // A missing privileged permission will not fix itself.
        if (err is StylusVibrationClient.SdkError.PermissionDenied) {
            Log.w(TAG, "not retrying: permission denied")
            return
        }
        if (retries >= MAX_RETRIES) {
            Log.w(TAG, "giving up after $retries bind attempts")
            return
        }
        val wait = RETRY_BASE_MS shl retries
        retries++
        Log.i(TAG, "bind failed, retry #$retries in ${wait}ms")
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            Haptics.bind(this@PenHapticsService)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        // SystemUI raises window events for the status bar, shade and toasts, which would
        // otherwise look like leaving the app the user is writing in.
        if (pkg in IGNORED) return
        if (pkg == currentPkg) return

        currentPkg = pkg
        Haptics.applyForeground(this, pkg)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        retryJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Stylo2Haptics"

        /** 2s, 4s, 8s, 16s, 32s — enough to cover a slow boot without spinning forever. */
        private const val RETRY_BASE_MS = 2_000L
        private const val MAX_RETRIES = 5

        private val IGNORED = setOf(
            "com.android.systemui",
        )

        @Volatile
        private var instance: PenHapticsService? = null

        /** Re-apply the decision after the user edits the whitelist or toggles auto mode. */
        fun reevaluate() {
            val svc = instance ?: return
            Haptics.applyForeground(svc, svc.currentPkg)
        }
    }
}
