package io.github.saeeddev94.xray.activity

import XrayCore.XrayCore
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import io.github.saeeddev94.xray.BuildConfig
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.adapter.LinkAdapter
import io.github.saeeddev94.xray.adapter.ProfileAdapter
import io.github.saeeddev94.xray.database.Link
import io.github.saeeddev94.xray.databinding.ActivityMainBinding
import io.github.saeeddev94.xray.dto.ProfileList
import io.github.saeeddev94.xray.helper.HttpHelper
import io.github.saeeddev94.xray.helper.LinkHelper
import io.github.saeeddev94.xray.helper.ProfileTouchHelper
import io.github.saeeddev94.xray.helper.TransparentProxyHelper
import io.github.saeeddev94.xray.helper.UpdateHelper
import io.github.saeeddev94.xray.service.TProxyService
import io.github.saeeddev94.xray.viewmodel.LinkViewModel
import io.github.saeeddev94.xray.viewmodel.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class MainActivity : AppCompatActivity() {

    private val clipboardManager by lazy { getSystemService(ClipboardManager::class.java) }
    private val settings by lazy { Settings(applicationContext) }
    private val transparentProxyHelper by lazy { TransparentProxyHelper(this, settings) }
    private val linkViewModel: LinkViewModel by viewModels()
    private val profileViewModel: ProfileViewModel by viewModels()
    private var isRunning: Boolean = false

    private lateinit var binding: ActivityMainBinding
    private lateinit var profileAdapter: ProfileAdapter
    private val linkAdapter by lazy { LinkAdapter() }
    private lateinit var tabs: List<Link>
    private val profilesRecyclerView by lazy { findViewById<RecyclerView>(R.id.profilesRecyclerView) }
    private val profiles = arrayListOf<ProfileList>()

    private var cameraPermission = registerForActivityResult(RequestPermission()) {
        if (!it) return@registerForActivityResult
        scannerLauncher.launch(
            Intent(applicationContext, ScannerActivity::class.java)
        )
    }
    private val notificationPermission = registerForActivityResult(RequestPermission()) {
        onToggleButtonClick()
    }
    private val linksManager = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        refreshLinks()
    }
    private var scannerLauncher = registerForActivityResult(StartActivityForResult()) {
        val link = it.data?.getStringExtra("link")
        if (it.resultCode != RESULT_OK || link == null) return@registerForActivityResult
        this@MainActivity.processLink(link)
    }
    private val vpnLauncher = registerForActivityResult(StartActivityForResult()) {
        if (it.resultCode != RESULT_OK) return@registerForActivityResult
        toggleVpnService()
    }

    private val vpnServiceEventReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            when (intent.action) {
                TProxyService.START_VPN_SERVICE_ACTION_NAME -> vpnStartStatus()
                TProxyService.STOP_VPN_SERVICE_ACTION_NAME -> vpnStopStatus()
                TProxyService.STATUS_VPN_SERVICE_ACTION_NAME -> {
                    intent.getBooleanExtra("isRunning", false).let { isRunning ->
                        if (isRunning) vpnStartStatus()
                        else vpnStopStatus()
                    }
                }
            }
        }
    }

    private val linksTabListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            if (tab == null) return
            settings.selectedLink = tab.tag.toString().toLong()
            profileViewModel.next(settings.selectedLink)
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {}
        override fun onTabReselected(tab: TabLayout.Tab?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.setPadding(0, systemBars.top, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)

        setupBottomNavigation()
        setupServersTab()
        setupSubscriptionsTab()
        setupSettingsTab()

        intent?.data?.let { deepLink ->
            val pathSegments = deepLink.pathSegments
            if (pathSegments.isNotEmpty()) processLink(pathSegments[0])
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_servers -> {
                    binding.screenServers.isVisible = true
                    binding.screenSubscriptions.isVisible = false
                    binding.screenSettings.isVisible = false
                    title = "Сервера"
                    invalidateOptionsMenu()
                    true
                }
                R.id.nav_subscriptions -> {
                    binding.screenServers.isVisible = false
                    binding.screenSubscriptions.isVisible = true
                    binding.screenSettings.isVisible = false
                    title = "Подписки"
                    invalidateOptionsMenu()
                    true
                }
                R.id.nav_settings -> {
                    binding.screenServers.isVisible = false
                    binding.screenSubscriptions.isVisible = false
                    binding.screenSettings.isVisible = true
                    title = "Настройки"
                    invalidateOptionsMenu()
                    true
                }
                else -> false
            }
        }
        title = "Сервера"
    }

    private fun setupServersTab() {
        binding.toggleButton.setOnClickListener { onToggleButtonClick() }
        binding.pingBox.setOnClickListener { ping() }

        profileAdapter = ProfileAdapter(
            lifecycleScope,
            settings,
            profileViewModel,
            profiles,
            { index, profile -> profileSelect(index, profile) },
            { profile -> profileEdit(profile) },
            { profile -> profileDelete(profile) },
        )
        profilesRecyclerView.adapter = profileAdapter
        profilesRecyclerView.layoutManager = LinearLayoutManager(applicationContext)
        ItemTouchHelper(ProfileTouchHelper(profileAdapter)).also {
            it.attachToRecyclerView(profilesRecyclerView)
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                linkViewModel.tabs.collectLatest { onNewTabs(it) }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileViewModel.filtered.collectLatest { onNewProfiles(it) }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileViewModel.profiles.collectLatest {
                    val tabs = if (::tabs.isInitialized) tabs else linkViewModel.activeLinks()
                    val list = tabsList(tabs)
                    val index = tabsIndex(list)
                    profileViewModel.next(list[index].id)
                }
            }
        }
    }

    private fun setupSubscriptionsTab() {
        binding.linksRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.linksRecyclerView.itemAnimator = DefaultItemAnimator()
        binding.linksRecyclerView.adapter = linkAdapter

        linkAdapter.onEditClick = { link -> openLink(link) }
        linkAdapter.onDeleteClick = { link -> deleteLink(link) }

        binding.addLinkFab.setOnClickListener { openLink() }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                linkViewModel.links.collectLatest { linksList ->
                    linkAdapter.submitList(linksList)
                    binding.emptySubscriptionsLayout.isVisible = linksList.isEmpty()
                    binding.linksRecyclerView.isVisible = linksList.isNotEmpty()
                }
            }
        }
    }

    private fun setupSettingsTab() {
        binding.settingsAssets.setOnClickListener {
            startActivity(Intent(applicationContext, AssetsActivity::class.java))
        }
        binding.settingsAppsRouting.setOnClickListener {
            startActivity(Intent(applicationContext, AppsRoutingActivity::class.java))
        }
        binding.settingsConfigs.setOnClickListener {
            startActivity(Intent(applicationContext, ConfigsActivity::class.java))
        }
        binding.settingsApp.setOnClickListener {
            startActivity(Intent(applicationContext, SettingsActivity::class.java))
        }
        binding.settingsLogs.setOnClickListener {
            startActivity(Intent(applicationContext, LogsActivity::class.java))
        }
        binding.settingsCheckUpdate.setOnClickListener {
            UpdateHelper(this, lifecycleScope).checkUpdate(manual = true)
        }

        binding.appVersionText.text = "Версия приложения: ${BuildConfig.VERSION_NAME}"
        binding.xrayVersionText.text = "Ядро Xray: ${XrayCore.version()}"
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (settings.transparentProxy) transparentProxyHelper.install()
        }
        updateActiveProfileName()
    }

    override fun onStart() {
        super.onStart()
        IntentFilter().also {
            it.addAction(TProxyService.START_VPN_SERVICE_ACTION_NAME)
            it.addAction(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            it.addAction(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(vpnServiceEventReceiver, it, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(vpnServiceEventReceiver, it)
            }
        }
        Intent(this, TProxyService::class.java).also {
            it.action = TProxyService.STATUS_VPN_SERVICE_ACTION_NAME
            startService(it)
        }
        if (settings.refreshLinksOnOpen) {
            val interval = (settings.refreshLinksInterval * 60 * 1000).toLong()
            val diff = System.currentTimeMillis() - settings.lastRefreshLinks
            if (diff >= interval) refreshLinks()
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(vpnServiceEventReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (menu == null) return super.onPrepareOptionsMenu(menu)
        val isServersTab = binding.screenServers.isVisible
        menu.findItem(R.id.refreshLinks)?.isVisible = isServersTab || binding.screenSubscriptions.isVisible
        menu.findItem(R.id.newProfile)?.isVisible = isServersTab
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.refreshLinks -> refreshLinks()
            R.id.newProfile -> startActivity(ProfileActivity.getIntent(applicationContext))
            R.id.scanQrCode -> cameraPermission.launch(android.Manifest.permission.CAMERA)
            R.id.fromClipboard -> {
                runCatching {
                    clipboardManager.primaryClip!!.getItemAt(0).text.toString().trim()
                }.getOrNull()?.let { processLink(it) }
            }
        }
        return true
    }

    private fun tabsList(list: List<Link>): List<Link> {
        tabs = list
        return listOf(Link(name = "All")) + tabs
    }

    private fun tabsIndex(list: List<Link>): Int {
        return list.indexOfFirst { it.id == settings.selectedLink }.takeIf { it != -1 } ?: 0
    }

    private fun onNewTabs(value: List<Link>) {
        binding.linksTab.removeOnTabSelectedListener(linksTabListener)
        binding.linksTab.removeAllTabs()
        binding.linksTab.isVisible = value.isNotEmpty()
        val list = tabsList(value)
        val index = tabsIndex(list)
        list.forEach {
            val tab = binding.linksTab.newTab()
            tab.tag = it.id
            tab.text = it.name
            binding.linksTab.addTab(tab)
        }
        binding.linksTab.selectTab(binding.linksTab.getTabAt(index))
        binding.linksTab.addOnTabSelectedListener(linksTabListener)
    }

    private fun onNewProfiles(value: List<ProfileList>) {
        profiles.clear()
        profiles.addAll(ArrayList(value))
        @Suppress("NotifyDataSetChanged")
        profileAdapter.notifyDataSetChanged()
        updateActiveProfileName()
    }

    private fun updateActiveProfileName() {
        val selectedId = settings.selectedProfile
        if (selectedId <= 0L) {
            binding.activeProfileName.text = "Сервер не выбран"
            return
        }
        val currentProfile = profiles.firstOrNull { it.id == selectedId }
        if (currentProfile != null) {
            binding.activeProfileName.text = currentProfile.name
        } else {
            lifecycleScope.launch {
                val profile = profileViewModel.find(selectedId)
                if (profile.id > 0L) {
                    binding.activeProfileName.text = profile.name
                } else {
                    binding.activeProfileName.text = "Сервер не выбран"
                }
            }
        }
    }

    private fun vpnStartStatus() {
        isRunning = true
        binding.vpnStatusDot.setBackgroundResource(R.drawable.ic_dot_status_active)
        binding.vpnStatusText.text = "Подключено"
        binding.toggleButton.text = getString(R.string.vpnStop)
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
        binding.pingResult.text = getString(R.string.pingConnected)
    }

    private fun vpnStopStatus() {
        isRunning = false
        binding.vpnStatusDot.setBackgroundResource(R.drawable.ic_dot_status_inactive)
        binding.vpnStatusText.text = "Отключено"
        binding.toggleButton.text = getString(R.string.vpnStart)
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2563EB"))
        binding.pingResult.text = getString(R.string.pingNotConnected)
    }

    private fun onToggleButtonClick() {
        if (!settings.tun2socks || settings.transparentProxy) {
            toggleVpnService()
            return
        }

        if (!hasPostNotification()) return
        VpnService.prepare(this).also {
            if (it == null) {
                toggleVpnService()
                return
            }
            vpnLauncher.launch(it)
        }
    }

    private fun toggleVpnService() {
        if (isRunning) {
            TProxyService.stop(applicationContext)
            return
        }
        TProxyService.start(applicationContext, false)
    }

    private fun profileSelect(index: Int, profile: ProfileList) {
        val selectedProfile = settings.selectedProfile
        lifecycleScope.launch {
            val ref = if (selectedProfile > 0L) profileViewModel.find(selectedProfile) else null
            withContext(Dispatchers.Main) {
                if (selectedProfile == profile.id) return@withContext
                settings.selectedProfile = profile.id
                profileAdapter.notifyItemChanged(index)
                updateActiveProfileName()
                if (isRunning) TProxyService.newConfig(applicationContext)
                if (ref == null || ref.id == profile.id) return@withContext
                profiles.indexOfFirst { it.id == ref.id }.let {
                    if (it != -1) profileAdapter.notifyItemChanged(it)
                }
            }
        }
    }

    private fun profileEdit(profile: ProfileList) {
        if (isRunning && settings.selectedProfile == profile.id) return
        startActivity(ProfileActivity.getIntent(applicationContext, profile.id))
    }

    private fun profileDelete(profile: ProfileList) {
        if (isRunning && settings.selectedProfile == profile.id) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить сервер?")
            .setMessage("\"${profile.name}\" будет удален навсегда.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    val ref = profileViewModel.find(profile.id)
                    val id = ref.id
                    profileViewModel.remove(ref)
                    withContext(Dispatchers.Main) {
                        val selectedProfile = settings.selectedProfile
                        if (selectedProfile == id) {
                            settings.selectedProfile = 0L
                        }
                        updateActiveProfileName()
                    }
                }
            }.show()
    }

    private fun openLink(link: Link = Link()) {
        val intent = LinksManagerActivity.openLink(applicationContext, link)
        linksManager.launch(intent)
    }

    private fun deleteLink(link: Link) {
        val intent = LinksManagerActivity.deleteLink(applicationContext, link)
        linksManager.launch(intent)
    }

    private fun processLink(link: String) {
        val uri = runCatching { URI(link) }.getOrNull() ?: return
        if (uri.scheme == "http") {
            Toast.makeText(
                applicationContext, getString(R.string.forbiddenHttp), Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (uri.scheme == "https") {
            openLink(uri)
            return
        }
        val linkHelper = LinkHelper(settings, link)
        if (!linkHelper.isValid()) {
            Toast.makeText(
                applicationContext, getString(R.string.invalidLink), Toast.LENGTH_SHORT
            ).show()
            return
        }
        val json = linkHelper.json()
        val name = linkHelper.remark()
        startActivity(ProfileActivity.getIntent(applicationContext, name = name, config = json))
    }

    private fun refreshLinks() {
        startActivity(LinksManagerActivity.refreshLinks(applicationContext))
    }

    private fun openLink(uri: URI) {
        val link = Link()
        link.name = LinkHelper.remark(uri, LinkHelper.LINK_DEFAULT)
        link.address = uri.toString()
        val intent = LinksManagerActivity.openLink(applicationContext, link)
        linksManager.launch(intent)
    }

    private fun ping() {
        if (!isRunning) return
        binding.pingResult.text = getString(R.string.pingTesting)
        HttpHelper(lifecycleScope, settings).measureDelay(!settings.transparentProxy) {
            binding.pingResult.text = it
        }
    }

    private fun hasPostNotification(): Boolean {
        val sharedPref = getSharedPreferences("app", MODE_PRIVATE)
        val key = "request_notification_permission"
        val askedBefore = sharedPref.getBoolean(key, false)
        if (askedBefore) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sharedPref.edit { putBoolean(key, true) }
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return false
        }
        return true
    }
}
