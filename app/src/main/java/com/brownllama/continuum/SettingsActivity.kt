package com.brownllama.continuum

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout

/**
 * The app's launcher screen. On top: a setup card (only until Continuum is the current keyboard)
 * and a field to try the keyboard. Below: a short menu (res/xml/prefs_root.xml) whose rows open
 * one page each (Appearance, Size & position, Typing, Gestures, Text shortcuts, GIFs, Guide) and
 * Reset to defaults.
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
                ROOT -> {
                    findPreference<Preference>("reset")?.setOnPreferenceClickListener {
                        confirmReset()
                        true
                    }
                    findPreference<Preference>("version")?.summary = runCatching {
                        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
                    }.getOrNull()
                }
                "shortcuts" -> {
                    findPreference<Preference>("shortcut_add")?.setOnPreferenceClickListener {
                        editShortcut(null)
                        true
                    }
                    showShortcuts()
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

        private val prefs get() = preferenceManager.sharedPreferences!!

        private fun shortcutText(key: Char) = prefs.getString(Prefs.shortcut(key), null)?.takeIf(String::isNotEmpty)

        /** Lists the saved shortcuts (a–z, then 0–9); "Add shortcut" goes once all keys are used. */
        private fun showShortcuts() {
            val list = findPreference<PreferenceGroup>("shortcut_list") ?: return
            list.removeAll()
            val used = Prefs.SHORTCUT_KEYS.filter { shortcutText(it) != null }
            for (key in used) {
                list.addPreference(
                    Preference(requireContext()).apply {
                        title = getString(R.string.pref_shortcut_title, key)
                        summary = shortcutText(key)
                        isPersistent = false
                        isIconSpaceReserved = false
                        isSingleLineTitle = true
                        setOnPreferenceClickListener {
                            editShortcut(key)
                            true
                        }
                    },
                )
            }
            list.isVisible = used.isNotEmpty()
            findPreference<Preference>("shortcut_add")?.isVisible = used.length < Prefs.SHORTCUT_KEYS.length
        }

        /**
         * Adds a shortcut ([current] null) or edits the one saved for [current]. Save is enabled once
         * the key is free (or [current]) and the text isn't empty; changing the key moves the shortcut.
         */
        private fun editShortcut(current: Char?) {
            val view = layoutInflater.inflate(R.layout.dialog_shortcut, null)
            val keyLayout = view.findViewById<TextInputLayout>(R.id.key_layout)
            val keyField = view.findViewById<EditText>(R.id.key)
            val textField = view.findViewById<EditText>(R.id.text)
            // Only a-z / 0-9, stored lowercase (##E and ##e are the same shortcut).
            keyField.filters = arrayOf(
                InputFilter { src, start, end, _, _, _ ->
                    src.subSequence(start, end).filter { it.lowercaseChar() in Prefs.SHORTCUT_KEYS }.toString().lowercase()
                },
                InputFilter.LengthFilter(1),
            )
            current?.let {
                keyField.setText(it.toString())
                textField.setText(shortcutText(it))
            }
            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (current == null) R.string.shortcut_new else R.string.shortcut_edit)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.shortcut_save) { _, _ ->
                    val key = keyField.text.single()
                    prefs.edit {
                        if (current != null && current != key) remove(Prefs.shortcut(current))
                        putString(Prefs.shortcut(key), textField.text.toString())
                    }
                    showShortcuts()
                }
            if (current != null) builder.setNeutralButton(R.string.shortcut_delete) { _, _ -> deleteShortcut(current) }
            val dialog = builder.show()
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            /** Whether the key is free; enables Save once there's text too. */
            fun validate(): Boolean {
                val key = keyField.text.firstOrNull()
                val taken = key != null && key != current && shortcutText(key) != null
                keyLayout.error = if (taken) getString(R.string.shortcut_key_taken, key) else null
                save.isEnabled = key != null && !taken && textField.text.isNotEmpty()
                return key != null && !taken
            }
            keyField.doAfterTextChanged {
                // A new shortcut's free key typed: on to its text.
                if (validate() && current == null) textField.requestFocus()
            }
            textField.doAfterTextChanged { validate() }
            validate()
            (if (current == null) keyField else textField).apply {
                requestFocus()
                setSelection(text.length)
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }

        /** Deletes the shortcut for [key], with an undo. */
        private fun deleteShortcut(key: Char) {
            val text = shortcutText(key) ?: return
            prefs.edit { remove(Prefs.shortcut(key)) }
            showShortcuts()
            Snackbar.make(requireView(), getString(R.string.shortcut_deleted, key), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    prefs.edit { putString(Prefs.shortcut(key), text) }
                    showShortcuts()
                }
                .show()
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
            val shortcuts = Prefs.SHORTCUT_KEYS.filter { shortcutText(it) != null }
            findPreference<Preference>("shortcuts")?.summary =
                if (shortcuts.isEmpty()) getString(R.string.summary_shortcuts_none)
                else shortcuts.map { getString(R.string.pref_shortcut_title, it) }.joinToString(" · ")
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
                "shortcuts" to R.xml.prefs_shortcuts,
                "gifs" to R.xml.prefs_gifs,
                "guide" to R.xml.prefs_guide,
            )

            fun of(screen: String) = ScreenFragment().apply { arguments = bundleOf(ARG_SCREEN to screen) }
        }
    }
}
