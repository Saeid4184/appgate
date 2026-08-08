package ir.factory.entryexit.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.factory.entryexit.R
import ir.factory.entryexit.databinding.ActivitySettingsBinding
import ir.factory.entryexit.util.AppPreferences
import kotlinx.coroutines.launch

/** Display/interaction/theme customization, requested separately from the one-time
 *  "تنظیمات اولیه" (photo assignment) setup screen. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.settings_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Load current values without triggering the listeners below.
        binding.switchRecentActivity.isChecked = AppPreferences.isRecentActivityVisible(this)
        binding.switchInsideFirst.isChecked = AppPreferences.isInsideFirstSort(this)
        binding.switchHaptic.isChecked = AppPreferences.isHapticEnabled(this)
        binding.switchQuickTap.isChecked = AppPreferences.isQuickTapEnabled(this)

        when (AppPreferences.getThemeMode(this)) {
            AppPreferences.ThemeMode.SYSTEM -> binding.radioThemeSystem.isChecked = true
            AppPreferences.ThemeMode.LIGHT -> binding.radioThemeLight.isChecked = true
            AppPreferences.ThemeMode.DARK -> binding.radioThemeDark.isChecked = true
        }

        binding.etAiApiKey.setText(AppPreferences.getAiApiKey(this))
        binding.btnSaveAiKey.setOnClickListener {
            val key = binding.etAiApiKey.text?.toString().orEmpty()
            AppPreferences.setAiApiKey(this, key)
            android.widget.Toast.makeText(this, getString(R.string.ai_key_saved), android.widget.Toast.LENGTH_SHORT).show()
            // Share it with the web admin panel too, so it doesn't have to be re-entered there.
            lifecycleScope.launch { ir.factory.entryexit.data.CloudSettings.pushAiApiKey(key) }
        }
        // If this device hasn't had a key entered yet, check whether one was already set from
        // the web panel (or another device) before making the user type it in again.
        if (AppPreferences.getAiApiKey(this).isBlank()) {
            lifecycleScope.launch {
                val cloudKey = ir.factory.entryexit.data.CloudSettings.fetchAiApiKey()
                if (!cloudKey.isNullOrBlank()) {
                    AppPreferences.setAiApiKey(this@SettingsActivity, cloudKey)
                    binding.etAiApiKey.setText(cloudKey)
                }
            }
        }

        binding.etWebPanelUrl.setText(AppPreferences.getAdminPanelUrl(this))
        binding.btnSaveWebPanelUrl.setOnClickListener {
            val url = binding.etWebPanelUrl.text?.toString().orEmpty()
            AppPreferences.setAdminPanelUrl(this, url)
            android.widget.Toast.makeText(this, getString(R.string.web_panel_url_saved), android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.switchRecentActivity.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setRecentActivityVisible(this, checked)
        }
        binding.switchInsideFirst.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setInsideFirstSort(this, checked)
        }
        binding.switchHaptic.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setHapticEnabled(this, checked)
        }
        binding.switchQuickTap.setOnCheckedChangeListener { _, checked ->
            AppPreferences.setQuickTapEnabled(this, checked)
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioThemeLight -> AppPreferences.ThemeMode.LIGHT
                R.id.radioThemeDark -> AppPreferences.ThemeMode.DARK
                else -> AppPreferences.ThemeMode.SYSTEM
            }
            AppPreferences.setThemeMode(this, mode)
        }

        setupCargoOptionsEditor()
    }

    /** Lets the admin customize which cargo/load options show up at checkout for each
     *  machinery sub-fleet — one item per line, falling back to sensible defaults until
     *  edited. A mixer should never see aggregate options, a dump truck should never see
     *  ready-mix options, etc., so these lists are kept separate per category. */
    private fun setupCargoOptionsEditor() {
        val categoryFields = mapOf(
            ir.factory.entryexit.data.MachineryCategory.MIXER to binding.etCargoMixer,
            ir.factory.entryexit.data.MachineryCategory.DUMP_TRUCK to binding.etCargoDumpTruck,
            ir.factory.entryexit.data.MachineryCategory.CONCRETE_PUMP to binding.etCargoPump,
            ir.factory.entryexit.data.MachineryCategory.LOGISTICS to binding.etCargoLogistics
        )

        categoryFields.forEach { (category, field) ->
            field.setText(AppPreferences.getCargoOptions(this, category).joinToString("\n"))
        }

        binding.btnSaveCargoOptions.setOnClickListener {
            categoryFields.forEach { (category, field) ->
                val options = field.text?.toString()?.split("\n").orEmpty()
                AppPreferences.setCargoOptions(this, category, options)
            }
            android.widget.Toast.makeText(this, getString(R.string.cargo_options_saved), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
