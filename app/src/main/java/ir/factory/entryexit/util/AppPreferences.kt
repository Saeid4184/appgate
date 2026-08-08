package ir.factory.entryexit.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Lightweight wrapper around SharedPreferences for the app's display/interaction settings.
 * Read directly (no LiveData) since these are checked at the moment of an action/bind, not
 * observed continuously.
 */
object AppPreferences {

    private const val PREFS_NAME = "app_settings"

    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    private const val KEY_SHOW_RECENT_ACTIVITY = "show_recent_activity"
    private const val KEY_QUICK_TAP_MODE = "quick_tap_mode"
    private const val KEY_INSIDE_FIRST_SORT = "inside_first_sort"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_AI_API_KEY = "ai_api_key"
    private const val KEY_ADMIN_PANEL_URL = "admin_panel_url"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Haptic feedback on successful check-in/out ---
    fun isHapticEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_HAPTIC_ENABLED, true)
    fun setHapticEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    // --- Recent-activity ticker under the status badge ---
    fun isRecentActivityVisible(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_RECENT_ACTIVITY, true)
    fun setRecentActivityVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_RECENT_ACTIVITY, visible).apply()
    }

    // --- Click behavior: tapping an outside person/machine directly checks them in
    //     instead of opening the ورود/خروج chooser first. Checkout always keeps its
    //     confirmation dialog regardless of this setting, to avoid accidental exits. ---
    fun isQuickTapEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_QUICK_TAP_MODE, false)
    fun setQuickTapEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUICK_TAP_MODE, enabled).apply()
    }

    // --- Sort order within each group section: currently-inside items shown first ---
    fun isInsideFirstSort(context: Context): Boolean = prefs(context).getBoolean(KEY_INSIDE_FIRST_SORT, false)
    fun setInsideFirstSort(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INSIDE_FIRST_SORT, enabled).apply()
    }

    // --- Theme: SYSTEM (default), LIGHT, DARK ---
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    fun getThemeMode(context: Context): ThemeMode {
        val raw = prefs(context).getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(raw ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
        applyThemeMode(mode)
    }

    fun applyThemeMode(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    // --- AI analysis API key (user-supplied, never bundled with the app) ---
    fun getAiApiKey(context: Context): String = prefs(context).getString(KEY_AI_API_KEY, "") ?: ""
    fun setAiApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_AI_API_KEY, key.trim()).apply()
    }

    // --- Web admin panel URL (hosted separately, e.g. via GitHub Pages). Fixed/permanent for
    //     this factory, so it no longer needs to be typed into Settings on every device — the
    //     field there still exists (and still overrides this) only as an escape hatch in case
    //     the panel is ever moved to a new address. ---
    private const val DEFAULT_ADMIN_PANEL_URL = "https://saeid4184.github.io/sobhansecurity/"

    fun getAdminPanelUrl(context: Context): String {
        val saved = prefs(context).getString(KEY_ADMIN_PANEL_URL, "") ?: ""
        return saved.ifBlank { DEFAULT_ADMIN_PANEL_URL }
    }
    fun setAdminPanelUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_ADMIN_PANEL_URL, url.trim()).apply()
    }

    // --- Per-machinery-category cargo/load options, editable in Settings so each factory can
    //     add or remove its own list per sub-fleet (mixers, dump trucks, pumps, logistics)
    //     instead of a single fixed list shared by every vehicle type. Falls back to
    //     MachineryCategory's built-in defaults until the admin customizes them. ---
    private const val KEY_CARGO_OPTIONS_PREFIX = "cargo_options_"
    private const val CARGO_OPTIONS_SEPARATOR = "|||"

    fun getCargoOptions(context: Context, category: ir.factory.entryexit.data.MachineryCategory): List<String> {
        val raw = prefs(context).getString(KEY_CARGO_OPTIONS_PREFIX + category.name, null)
        if (raw.isNullOrBlank()) {
            return ir.factory.entryexit.data.MachineryCategory.defaultCargoOptions(category)
        }
        val options = raw.split(CARGO_OPTIONS_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        return options.ifEmpty { ir.factory.entryexit.data.MachineryCategory.defaultCargoOptions(category) }
    }

    fun setCargoOptions(context: Context, category: ir.factory.entryexit.data.MachineryCategory, options: List<String>) {
        val cleaned = options.map { it.trim() }.filter { it.isNotEmpty() }
        prefs(context).edit().putString(KEY_CARGO_OPTIONS_PREFIX + category.name, cleaned.joinToString(CARGO_OPTIONS_SEPARATOR)).apply()
    }
}
