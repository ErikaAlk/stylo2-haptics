package io.github.yeetingwaterbottle.stylo2.haptics

import android.content.Context
import androidx.core.content.edit
import com.oplus.ipemanager.sdk.Vibration

/**
 * App settings.
 *
 * SharedPreferences rather than DataStore: there are only three scalars, and the
 * AccessibilityService needs to read them synchronously from an event callback.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Package names that should get pen haptics while in the foreground. */
    var whitelist: Set<String>
        get() = sp.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        set(value) = sp.edit { putStringSet(KEY_WHITELIST, value) }

    /** Let the foreground app drive haptics instead of keeping them on globally. */
    var autoMode: Boolean
        get() = sp.getBoolean(KEY_AUTO, false)
        set(value) = sp.edit { putBoolean(KEY_AUTO, value) }

    /** Stored by name, not ordinal, so reordering the enum cannot silently change it. */
    var preset: Vibration
        get() = runCatching {
            Vibration.valueOf(sp.getString(KEY_PRESET, null) ?: Vibration.PENCIL.name)
        }.getOrDefault(Vibration.PENCIL)
        set(value) = sp.edit { putString(KEY_PRESET, value.name) }

    fun isWhitelisted(pkg: String?): Boolean = pkg != null && pkg in whitelist

    fun toggle(pkg: String, on: Boolean) {
        whitelist = whitelist.toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
    }

    private companion object {
        const val NAME = "stylo2_haptics"
        const val KEY_WHITELIST = "whitelist"
        const val KEY_AUTO = "auto_mode"
        const val KEY_PRESET = "preset"
    }
}
