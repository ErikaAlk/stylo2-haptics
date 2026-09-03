package io.github.yeetingwaterbottle.stylo2.haptics

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "PenHapticsService connected")
        instance = this

        scope.launch {
            Haptics.client(this@PenHapticsService).connection.collect { c ->
                when (c.state) {
                    StylusVibrationClient.State.CONNECTED_SDK ->
                        Haptics.onConnected(this@PenHapticsService, currentPkg)
                    StylusVibrationClient.State.DISCONNECTED,
                    StylusVibrationClient.State.ERROR ->
                        Haptics.onDisconnected()
                    else -> Unit
                }
            }
        }
        Haptics.bind(this)
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Stylo2Haptics"

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
