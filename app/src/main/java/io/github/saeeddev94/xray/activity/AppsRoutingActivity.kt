package io.github.saeeddev94.xray.activity

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.adapter.AppsRoutingAdapter
import io.github.saeeddev94.xray.databinding.ActivityAppsRoutingBinding
import io.github.saeeddev94.xray.dto.AppList
import io.github.saeeddev94.xray.helper.TransparentProxyHelper
import io.github.saeeddev94.xray.service.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsRoutingActivity : AppCompatActivity() {

    private val settings by lazy { Settings(applicationContext) }
    private val transparentProxyHelper by lazy { TransparentProxyHelper(this, settings) }
    private lateinit var binding: ActivityAppsRoutingBinding
    private lateinit var appsList: RecyclerView
    private lateinit var appsRoutingAdapter: AppsRoutingAdapter
    private var apps: ArrayList<AppList> = arrayListOf()
    private var filtered: MutableList<AppList> = mutableListOf()
    private var appsRouting: MutableSet<String> = mutableSetOf()
    private var appsRoutingMode: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsRoutingBinding.inflate(layoutInflater)
        appsRoutingMode = settings.appsRoutingMode
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnModeExclude.setOnClickListener { setMode(true) }
        binding.btnModeInclude.setOnClickListener { setMode(false) }

        binding.btnClearSelection.setOnClickListener {
            appsRouting.clear()
            if (::appsRoutingAdapter.isInitialized) {
                appsRoutingAdapter.notifyDataSetChanged()
            }
            updateStats()
        }

        binding.btnSaveFloating.setOnClickListener { saveAppsRouting() }

        binding.search.focusable = View.NOT_FOCUSABLE
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                search(newText)
                return false
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.search.clearFocus()
                search(query)
                return false
            }
        })
        binding.search.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
            ?.setOnClickListener {
                binding.search.setQuery("", false)
                binding.search.clearFocus()
            }

        updateModeUi()
        getApps()
    }

    private fun setMode(mode: Boolean) {
        if (appsRoutingMode == mode) return
        appsRoutingMode = mode
        updateModeUi()
    }

    private fun updateModeUi() {
        if (appsRoutingMode) {
            binding.btnModeExclude.setCardBackgroundColor(Color.parseColor("#3A75FF"))
            binding.textModeExclude.setTextColor(Color.WHITE)
            binding.btnModeInclude.setCardBackgroundColor(Color.parseColor("#1E293B"))
            binding.textModeInclude.setTextColor(Color.parseColor("#94A3B8"))
            binding.modeDescription.text = "Трафик выбранных приложений будет идти напрямую, минуя VPN."
        } else {
            binding.btnModeExclude.setCardBackgroundColor(Color.parseColor("#1E293B"))
            binding.textModeExclude.setTextColor(Color.parseColor("#94A3B8"))
            binding.btnModeInclude.setCardBackgroundColor(Color.parseColor("#3A75FF"))
            binding.textModeInclude.setTextColor(Color.WHITE)
            binding.modeDescription.text = "Трафик ТОЛЬКО выбранных приложений будет направляться через VPN."
        }
    }

    private fun updateStats() {
        val selectedCount = appsRouting.filter { it.isNotBlank() }.size
        binding.selectedCountText.text = "Выбрано приложений: $selectedCount из ${apps.size}"
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun search(query: String?) {
        val keyword = query?.trim()?.lowercase() ?: ""
        if (keyword.isEmpty()) {
            filtered.clear()
            filtered.addAll(apps)
        } else {
            val list = apps.filter {
                it.appName.lowercase().contains(keyword) || it.packageName.lowercase().contains(keyword)
            }
            filtered.clear()
            filtered.addAll(list)
        }
        if (::appsRoutingAdapter.isInitialized) {
            appsRoutingAdapter.notifyDataSetChanged()
        }
    }

    private fun getApps() {
        binding.loadingProgress.isVisible = true
        binding.appsList.isVisible = false

        lifecycleScope.launch(Dispatchers.IO) {
            val rawRouting = settings.appsRouting.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            val selected = ArrayList<AppList>()
            val unselected = ArrayList<AppList>()

            runCatching {
                val installedPackages = packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                for (pkg in installedPackages) {
                    val appInfo = pkg.applicationInfo ?: continue
                    val packageName = pkg.packageName.takeIf { it.isNotBlank() } ?: continue
                    
                    val appName = runCatching {
                        appInfo.loadLabel(packageManager).toString()
                    }.getOrNull().takeIf { !it.isNullOrBlank() } ?: packageName

                    val appIcon = runCatching {
                        appInfo.loadIcon(packageManager)
                    }.getOrNull() ?: continue

                    val app = AppList(appIcon, appName, packageName)
                    if (rawRouting.contains(packageName)) {
                        selected.add(app)
                    } else {
                        unselected.add(app)
                    }
                }
            }

            selected.sortBy { it.appName.lowercase() }
            unselected.sortBy { it.appName.lowercase() }

            val loadedApps = ArrayList(selected + unselected)

            withContext(Dispatchers.Main) {
                apps = loadedApps
                filtered = apps.toMutableList()
                appsRouting = rawRouting

                appsList = binding.appsList
                appsRoutingAdapter = AppsRoutingAdapter(
                    this@AppsRoutingActivity, filtered, appsRouting
                ) {
                    updateStats()
                }
                appsList.adapter = appsRoutingAdapter
                appsList.layoutManager = LinearLayoutManager(applicationContext)

                binding.loadingProgress.isVisible = false
                binding.appsList.isVisible = true
                updateStats()
            }
        }
    }

    private fun saveAppsRouting() {
        val newRoutingMode = this.appsRoutingMode
        val newRoutingStr = this.appsRouting.filter { it.isNotBlank() }.joinToString("\n")

        lifecycleScope.launch(Dispatchers.IO) {
            val tproxySettingsChanged = settings.appsRoutingMode != newRoutingMode ||
                    settings.appsRouting != newRoutingStr
            val stopService = tproxySettingsChanged && settings.xrayCorePid().exists()
            if (tproxySettingsChanged) transparentProxyHelper.kill()

            withContext(Dispatchers.Main) {
                binding.search.clearFocus()
                settings.appsRoutingMode = newRoutingMode
                settings.appsRouting = newRoutingStr
                if (stopService) TProxyService.stop(this@AppsRoutingActivity)
                Toast.makeText(applicationContext, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
