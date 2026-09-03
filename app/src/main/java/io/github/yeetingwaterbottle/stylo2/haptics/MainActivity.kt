package io.github.yeetingwaterbottle.stylo2.haptics

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.oplus.ipemanager.sdk.Vibration
import io.github.yeetingwaterbottle.stylo2.haptics.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val client get() = Haptics.client(applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val presets = Vibration.values().map { it.name.lowercase().replaceFirstChar(Char::titlecase) }
        binding.dropdownVibration.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, presets)
        )
        binding.dropdownVibration.setText(
            prefs.preset.name.lowercase().replaceFirstChar(Char::titlecase), false
        )
        binding.dropdownVibration.setOnItemClickListener { _, _, pos, _ ->
            val chosen = Vibration.values()[pos]
            prefs.preset = chosen
            client.setVibrationType(chosen)
        }

        binding.btnBind.setOnClickListener { Haptics.bind(this) }
        binding.btnUnbind.setOnClickListener { client.unbind(); Haptics.onDisconnected() }

        binding.switchEnable.setOnCheckedChangeListener { view, checked ->
            // Ignore programmatic writes from applyModeUi()
            if (!view.isPressed) return@setOnCheckedChangeListener
            if (!Haptics.setRunning(this, checked) && checked) {
                Snackbar.make(binding.root, R.string.op_failed, Snackbar.LENGTH_SHORT).show()
                binding.switchEnable.isChecked = false
            }
        }

        binding.switchAuto.isChecked = prefs.autoMode
        binding.switchAuto.setOnCheckedChangeListener { view, checked ->
            if (!view.isPressed) return@setOnCheckedChangeListener
            prefs.autoMode = checked
            applyModeUi()
            if (checked) {
                // Hand over to the watcher; this app is in the foreground now, so it usually turns haptics off.
                PenHapticsService.reevaluate()
                if (!isAccessibilityEnabled()) {
                    Snackbar.make(binding.root, R.string.svc_off, Snackbar.LENGTH_LONG)
                        .setAction(R.string.svc_open_settings) { openAccessibilitySettings() }
                        .show()
                }
            }
        }

        binding.btnWhitelist.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        binding.btnService.setOnClickListener { openAccessibilitySettings() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.connection.collect { c -> render(c) }
            }
        }

        Haptics.bind(this)
    }

    override fun onResume() {
        super.onResume()
        applyModeUi()
    }

    /** In auto mode the watcher owns the switch, so disable it here to avoid the two fighting. */
    private fun applyModeUi() {
        val auto = prefs.autoMode
        val connected = client.connection.value.state == StylusVibrationClient.State.CONNECTED_SDK
        binding.switchEnable.isEnabled = connected && !auto
        binding.switchEnable.isChecked = Haptics.isRunning

        val on = isAccessibilityEnabled()
        binding.tvService.text = getString(if (on) R.string.svc_on else R.string.svc_off)
        binding.cardService.visibility = if (auto) View.VISIBLE else View.GONE
        binding.btnService.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, PenHapticsService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure {
                Snackbar.make(binding.root, R.string.err_no_a11y_settings, Snackbar.LENGTH_SHORT).show()
            }
    }

    private fun render(c: StylusVibrationClient.Connection) {
        val (state, err) = c

        binding.chipStatus.text = when (state) {
            StylusVibrationClient.State.DISCONNECTED -> "Disconnected"
            StylusVibrationClient.State.CONNECTING -> "Connecting…"
            StylusVibrationClient.State.CONNECTED_SDK -> "Connected"
            StylusVibrationClient.State.CONNECTED_WRONG_IFACE -> "Wrong interface"
            StylusVibrationClient.State.ERROR -> when (err) {
                StylusVibrationClient.SdkError.PermissionDenied -> "Permission denied"
                StylusVibrationClient.SdkError.ServiceUnavailable -> "Service unavailable"
                StylusVibrationClient.SdkError.NotConnected -> "Not connected"
                is StylusVibrationClient.SdkError.WrongInterface -> "Wrong interface"
                is StylusVibrationClient.SdkError.Remote -> "Remote error"
                is StylusVibrationClient.SdkError.Unknown -> "Error"
                null -> "Error"
            }
        }
        binding.chipStatus.applyStatusColors(state, err)

        val connected = state == StylusVibrationClient.State.CONNECTED_SDK
        binding.dropdownVibration.isEnabled = connected
        if (connected) client.setVibrationType(prefs.preset)
        applyModeUi()

        if (state == StylusVibrationClient.State.ERROR && err != null) {
            val message = when (err) {
                StylusVibrationClient.SdkError.PermissionDenied -> getString(R.string.err_permission)
                StylusVibrationClient.SdkError.ServiceUnavailable -> getString(R.string.err_service)
                is StylusVibrationClient.SdkError.WrongInterface ->
                    getString(R.string.err_iface, err.found ?: "unknown")
                StylusVibrationClient.SdkError.NotConnected -> getString(R.string.err_not_connected)
                is StylusVibrationClient.SdkError.Remote ->
                    getString(R.string.err_remote, err.cause?.message ?: "unknown")
                is StylusVibrationClient.SdkError.Unknown ->
                    getString(R.string.err_unknown, err.cause?.message ?: "unknown")
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun Chip.applyStatusColors(
        state: StylusVibrationClient.State,
        err: StylusVibrationClient.SdkError?
    ) {
        val (bgAttr, fgAttr) = when {
            state == StylusVibrationClient.State.CONNECTED_SDK ->
                com.google.android.material.R.attr.colorPrimaryContainer to
                        com.google.android.material.R.attr.colorOnPrimaryContainer
            state == StylusVibrationClient.State.CONNECTING ->
                com.google.android.material.R.attr.colorSecondaryContainer to
                        com.google.android.material.R.attr.colorOnSecondaryContainer
            state == StylusVibrationClient.State.CONNECTED_WRONG_IFACE ->
                com.google.android.material.R.attr.colorTertiaryContainer to
                        com.google.android.material.R.attr.colorOnTertiaryContainer
            state == StylusVibrationClient.State.ERROR ->
                com.google.android.material.R.attr.colorErrorContainer to
                        com.google.android.material.R.attr.colorOnErrorContainer
            else ->
                com.google.android.material.R.attr.colorSurfaceVariant to
                        com.google.android.material.R.attr.colorOnSurface
        }
        chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(this, bgAttr))
        setTextColor(MaterialColors.getColor(this, fgAttr))
    }
}
