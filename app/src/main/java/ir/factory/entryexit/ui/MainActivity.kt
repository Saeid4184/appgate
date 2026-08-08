package ir.factory.entryexit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import ir.factory.entryexit.R
import ir.factory.entryexit.data.AuthRepository
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.data.Session
import ir.factory.entryexit.databinding.ActivityMainBinding
import ir.factory.entryexit.util.AppPreferences

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: CategoryPagerAdapter
    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Defensive: if this Activity is somehow reached without a signed-in session
        // (session cleared, process restarted oddly), bounce back to the login screen.
        if (!Session.isSignedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        binding.toolbar.logo = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.app_logo)

        Session.currentUser?.let { user ->
            supportActionBar?.subtitle = getString(R.string.signed_in_as_format, user.name, user.role.displayName)
        }

        pagerAdapter = CategoryPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter

        val tabIcons = intArrayOf(
            R.drawable.ic_personnel,
            R.drawable.ic_machinery,
            R.drawable.ic_visitor,
            R.drawable.ic_driver,
            R.drawable.ic_inspection,
            R.drawable.ic_inventory
        )
        val tabTitles = arrayOf(
            getString(R.string.category_personnel),
            getString(R.string.category_machinery),
            getString(R.string.category_visitor),
            getString(R.string.category_driver),
            getString(R.string.category_inspection),
            getString(R.string.category_item_log)
        )

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles[position]
            tab.setIcon(tabIcons[position])
        }.attach()

        // Jump directly to a tab, e.g. when returning from global search.
        intent?.getStringExtra(EXTRA_JUMP_TO_TYPE)?.let { typeName ->
            val type = runCatching { PersonType.valueOf(typeName) }.getOrNull()
            type?.let { binding.viewPager.setCurrentItem(pagerAdapter.positionOf(it), false) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // Dashboard/Reports/Setup/Settings/Web-panel are admin-only; guards only get
        // check-in/out + roster management (the 4 tabs) and search.
        val isAdmin = Session.isAdmin()
        menu.findItem(R.id.action_dashboard)?.isVisible = isAdmin
        menu.findItem(R.id.action_report)?.isVisible = isAdmin
        menu.findItem(R.id.action_setup)?.isVisible = isAdmin
        menu.findItem(R.id.action_settings)?.isVisible = isAdmin
        menu.findItem(R.id.action_web_panel)?.isVisible = isAdmin
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_dashboard -> {
                startActivity(Intent(this, AdminDashboardActivity::class.java))
                true
            }
            R.id.action_search -> {
                startActivity(Intent(this, GlobalSearchActivity::class.java))
                true
            }
            R.id.action_report -> {
                startActivity(Intent(this, ReportActivity::class.java))
                true
            }
            R.id.action_setup -> {
                startActivity(Intent(this, SetupActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_web_panel -> {
                openWebAdminPanel()
                true
            }
            R.id.action_sign_out -> {
                confirmSignOut()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out_confirm_title)
            .setMessage(R.string.sign_out_confirm_message)
            .setPositiveButton(R.string.menu_sign_out) { _, _ ->
                authRepository.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun openWebAdminPanel() {
        val url = AppPreferences.getAdminPanelUrl(this)
        if (url.isBlank()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.web_panel_url_missing_title)
                .setMessage(R.string.web_panel_url_missing_message)
                .setPositiveButton(R.string.ai_open_settings) { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
            return
        }
        val fullUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)))
        }
    }

    companion object {
        const val EXTRA_JUMP_TO_TYPE = "extra_jump_to_type"
    }
}
