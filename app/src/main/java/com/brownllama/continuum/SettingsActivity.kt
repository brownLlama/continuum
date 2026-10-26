package com.brownllama.continuum

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The app's launcher screen. On top: a setup card (only until Continuum is the current keyboard)
 * and a field to try the keyboard. Below: a short menu (res/xml/prefs_root.xml) whose rows open
 * one page each (Appearance, Size & position, Typing, Gestures, GIFs, Guide) and Reset to defaults.
 *
 * The keyboard service reads the same SharedPreferences and updates live as they change.
 */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<Button>(R.id.enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.select).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.prefs, ScreenFragment.of(ScreenFragment.ROOT)).commit()
        }
        supportFragmentManager.addOnBackStackChangedListener { updateToolbar() }
        updateToolbar()
    }

    /** A menu row (a Preference with app:fragment) was tapped: open its page. */
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment: Fragment = when (pref.fragment) {
            ScreenFragment::class.java.name -> ScreenFragment.of(pref.key)
            else -> supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment ?: return false)
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.prefs, fragment)
            .addToBackStack(pref.title?.toString())
            .commit()
        return true
    }

    /** The open page's title, with a back arrow below the main menu. */
    private fun updateToolbar() {
        val depth = supportFragmentManager.backStackEntryCount
        toolbar.title = if (depth == 0) getString(R.string.app_name) else supportFragmentManager.getBackStackEntryAt(depth - 1).name
        if (depth == 0) toolbar.navigationIcon = null else toolbar.setNavigationIcon(R.drawable.ic_back)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus() // e.g. after the system keyboard picker closes
    }

    /** Shows the setup card until Continuum is the current keyboard, with only the step still needed. */
    private fun updateStatus() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        findViewById<View>(R.id.setup_card).visibility = if (current) View.GONE else View.VISIBLE
        findViewById<View>(R.id.enable).visibility = if (enabled) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.status).setText(if (enabled) R.string.status_enabled else R.string.status_disabled)
    }

    /** One settings page, chosen by the "screen" argument (a key of [SCREENS]). */
    class ScreenFragment : PreferenceFragmentCompat() {
        private val screen get() = requireArguments().getString(ARG_SCREEN) ?: ROOT

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(SCREENS.getValue(screen), rootKey)
            alignTitles(preferenceScreen)
            when (screen) {
                "typing" -> findPreference<Preference>("clear_learned_action")?.setOnPreferenceClickListener {
                    confirmForget()
                    true
                }
                ROOT -> findPreference<Preference>("reset")?.setOnPreferenceClickListener {
                    confirmReset()
                    true
                }
                "gifs" -> findPreference<EditTextPreference>(Prefs.KLIPY_KEY)?.apply {
                    // Show whether a key is set, not the key itself.
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                        getString(if (it.text.isNullOrBlank()) R.string.pref_klipy_key_unset else R.string.pref_klipy_key_set)
                    }
                    setOnBindEditTextListener {
                        it.isSingleLine = true
                        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            if (screen == ROOT) updateMenuSummaries()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            setDivider(null) // sections are separated by their headings, not lines
        }

        /** Rows without an icon don't reserve space for one, so their titles line up with the edge. */
        private fun alignTitles(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val p = group.getPreference(i)
                if (p.icon == null) p.isIconSpaceReserved = false
                if (p is PreferenceGroup) alignTitles(p)
            }
        }

        /** The menu rows show the current values of their page, e.g. "Nord · QWERTZ". */
        private fun updateMenuSummaries() {
            val prefs = preferenceManager.sharedPreferences ?: return
            fun label(names: Int, values: Int, value: String?): String {
                val i = resources.getStringArray(values).indexOf(value).coerceAtLeast(0)
                return resources.getStringArray(names)[i]
            }
            findPreference<Preference>("appearance")?.summary =
                label(R.array.theme_names, R.array.theme_values, prefs.getString(Prefs.THEME, Prefs.THEME_CHAMELEON)) +
                " · " + label(R.array.layout_names, R.array.layout_values, prefs.getString(Prefs.LAYOUT, Prefs.LAYOUT_DEFAULT))
            findPreference<Preference>("size")?.summary = getString(
                R.string.summary_size,
                prefs.getInt(Prefs.MINI_HEIGHT, Prefs.MINI_HEIGHT_DEFAULT),
                prefs.getInt(Prefs.FULL_HEIGHT, Prefs.FULL_HEIGHT_DEFAULT),
                prefs.getInt(Prefs.BAR_HEIGHT, Prefs.BAR_HEIGHT_DEFAULT),
            )
            val hold = prefs.getInt(Prefs.HOLD_MS, Prefs.HOLD_MS_DEFAULT)
            findPreference<Preference>("typing")?.summary = getString(
                if (prefs.getBoolean(Prefs.AUTOCORRECT, Prefs.AUTOCORRECT_DEFAULT)) R.string.summary_typing_on else R.string.summary_typing_off, hold,
            )
            findPreference<Preference>("gestures")?.setSummary(R.string.summary_gestures)
            findPreference<Preference>("gifs")?.setSummary(
                when {
                    !prefs.getBoolean(Prefs.SHOW_GIFS, Prefs.SHOW_GIFS_DEFAULT) -> R.string.summary_gifs_hidden
                    prefs.getString(Prefs.KLIPY_KEY, null).isNullOrBlank() -> R.string.summary_gifs_off
                    else -> R.string.summary_gifs_on
                },
            )
            findPreference<Preference>("guide")?.setSummary(R.string.summary_guide)
        }

        /** The IME owns the learned words, so it's asked to clear them through a preference change. */
        private fun confirmForget() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_confirm_title)
                .setMessage(R.string.clear_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_confirm) { _, _ ->
                    preferenceManager.sharedPreferences?.edit { putLong(Prefs.CLEAR_LEARNED, System.currentTimeMillis()) }
                    Toast.makeText(requireContext(), R.string.cleared, Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        /** Clears every setting ([Prefs.RESETTABLE]), so the keyboard and each page fall back to their defaults. */
        private fun confirmReset() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_confirm) { _, _ ->
                    preferenceManager.sharedPreferences?.edit { Prefs.RESETTABLE.forEach { remove(it) } }
                    updateMenuSummaries()
                    Toast.makeText(requireContext(), R.string.reset_done, Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        companion object {
            const val ROOT = "root"
            private const val ARG_SCREEN = "screen"

            /** Page name (the menu row's key) → its preferences XML. */
            private val SCREENS: Map<String, Int> = mapOf(
                ROOT to R.xml.prefs_root,
                "appearance" to R.xml.prefs_appearance,
                "size" to R.xml.prefs_size,
                "typing" to R.xml.prefs_typing,
                "gestures" to R.xml.prefs_gestures,
                "gifs" to R.xml.prefs_gifs,
                "guide" to R.xml.prefs_guide,
            )

            fun of(screen: String) = ScreenFragment().apply { arguments = bundleOf(ARG_SCREEN to screen) }
        }
    }
}
