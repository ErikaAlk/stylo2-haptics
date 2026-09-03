package io.github.yeetingwaterbottle.stylo2.haptics

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import androidx.activity.ComponentActivity
import io.github.yeetingwaterbottle.stylo2.haptics.databinding.ActivityAppPickerBinding
import io.github.yeetingwaterbottle.stylo2.haptics.databinding.ItemAppBinding
import kotlin.concurrent.thread

/**
 * Whitelist editor: pick which apps should get pen haptics.
 *
 * The list is launchable apps + installed input methods + anything already whitelisted.
 * Input methods are added explicitly because keyboards usually have no launcher icon,
 * yet their handwriting field is exactly where haptics are wanted.
 */
class AppPickerActivity : ComponentActivity() {

    private data class Item(val pkg: String, val label: String, val isIme: Boolean) {
        var icon: Drawable? = null
    }

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var prefs: Prefs

    private val all = mutableListOf<Item>()
    private val shown = mutableListOf<Item>()
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.list.adapter = adapter
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        binding.progress.visibility = View.VISIBLE
        thread { load() }
    }

    override fun onPause() {
        super.onPause()
        // The whitelist may have changed; let the watcher re-decide for the current foreground app.
        PenHapticsService.reevaluate()
    }

    private fun load() {
        val pm = packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }

        val imes = runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .inputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())

        val imeSet = imes.toSet()
        val pkgs = (launchable + imes + prefs.whitelist).distinct().filter { it != packageName }

        val items = pkgs.mapNotNull { pkg ->
            val ai: ApplicationInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                ?: return@mapNotNull null
            Item(pkg, pm.getApplicationLabel(ai).toString(), pkg in imeSet)
        }.sortedWith(
            // Already-selected first so the current choices are easy to review, then by name.
            compareByDescending<Item> { it.pkg in prefs.whitelist }
                .thenBy { it.label.lowercase() }
        )

        runOnUiThread {
            all.clear(); all.addAll(items)
            binding.progress.visibility = View.GONE
            filter(binding.search.text?.toString().orEmpty())
        }
    }

    private fun filter(q: String) {
        val k = q.trim().lowercase()
        shown.clear()
        shown.addAll(
            if (k.isEmpty()) all
            else all.filter { it.label.lowercase().contains(k) || it.pkg.lowercase().contains(k) }
        )
        adapter.notifyDataSetChanged()
        binding.empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = if (convertView == null) {
                ItemAppBinding.inflate(layoutInflater, parent, false).also { it.root.tag = it }
            } else {
                convertView.tag as ItemAppBinding
            }
            val item = shown[position]

            if (item.icon == null) {
                item.icon = runCatching { packageManager.getApplicationIcon(item.pkg) }.getOrNull()
            }
            b.icon.setImageDrawable(item.icon)
            b.label.text =
                if (item.isIme) getString(R.string.ime_suffix, item.label) else item.label
            b.pkg.text = item.pkg

            // Detach before setting the state, or recycled rows would write the previous item's value.
            b.check.setOnCheckedChangeListener(null)
            b.check.isChecked = item.pkg in prefs.whitelist
            b.check.setOnCheckedChangeListener { _, on -> prefs.toggle(item.pkg, on) }
            b.root.setOnClickListener { b.check.isChecked = !b.check.isChecked }

            return b.root
        }
    }
}
