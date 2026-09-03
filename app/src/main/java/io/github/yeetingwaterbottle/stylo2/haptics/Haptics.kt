package io.github.yeetingwaterbottle.stylo2.haptics

import android.content.Context
import android.util.Log

/**
 * Process-wide haptics controller.
 *
 * MainActivity and PenHapticsService must share one SDK connection.
 * StylusVibrationClient binds using the application context, so the binding lives
 * as long as the process; holding it here keeps the two from binding separately
 * and fighting over the state.
 */
object Haptics {

    private const val TAG = "Stylo2Haptics"

    @Volatile
    private var client: StylusVibrationClient? = null

    /** Whether startFeedBackVibration is currently in effect, so we don't resend on every window change. */
    @Volatile
    private var running = false

    fun client(context: Context): StylusVibrationClient =
        client ?: synchronized(this) {
            client ?: StylusVibrationClient(context.applicationContext).also { client = it }
        }

    val isRunning: Boolean get() = running

    fun bind(context: Context) = client(context).bind()

    /** Reset on disconnect, otherwise the first start after reconnecting is swallowed as a duplicate. */
    fun onDisconnected() {
        running = false
    }

    /**
     * Turn haptics on or off for the given foreground package. No-op unless auto mode is on.
     * @return true if a command was actually sent
     */
    fun applyForeground(context: Context, pkg: String?): Boolean {
        val prefs = Prefs(context)
        if (!prefs.autoMode) return false
        return setRunning(context, prefs.isWhitelisted(pkg))
    }

    /** Set the state directly (manual mode). Repeated calls with the same value are ignored. */
    fun setRunning(context: Context, on: Boolean): Boolean {
        if (on == running) return false
        val c = client(context)
        val ok = if (on) c.startVibration() else c.stopVibration()
        if (ok) {
            running = on
            Log.d(TAG, "haptics -> ${if (on) "ON" else "OFF"}")
        } else {
            Log.w(TAG, "haptics ${if (on) "start" else "stop"} failed (not connected?)")
        }
        return ok
    }

    /** Called once the SDK is connected: push the stored preset and sync to the current foreground app. */
    fun onConnected(context: Context, foreground: String?) {
        val prefs = Prefs(context)
        client(context).setVibrationType(prefs.preset)
        running = false
        if (prefs.autoMode) applyForeground(context, foreground)
    }
}
