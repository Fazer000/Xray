package io.github.saeeddev94.xray.activity

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.saeeddev94.xray.R
import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.database.Link
import io.github.saeeddev94.xray.database.Profile
import io.github.saeeddev94.xray.extensions.decodeToJsonArray
import io.github.saeeddev94.xray.extensions.decodeToJsonObject
import io.github.saeeddev94.xray.extensions.encodeToString
import io.github.saeeddev94.xray.fragment.LinkFormFragment
import io.github.saeeddev94.xray.helper.HttpHelper
import io.github.saeeddev94.xray.helper.IntentHelper
import io.github.saeeddev94.xray.helper.LinkHelper
import io.github.saeeddev94.xray.service.TProxyService
import io.github.saeeddev94.xray.viewmodel.LinkViewModel
import io.github.saeeddev94.xray.viewmodel.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class LinksManagerActivity : AppCompatActivity() {

    companion object {
        private const val LINK_REF = "ref"
        private const val DELETE_ACTION = "delete"

        fun refreshLinks(context: Context): Intent {
            return Intent(context, LinksManagerActivity::class.java)
        }

        fun openLink(context: Context, link: Link = Link()): Intent {
            return Intent(context, LinksManagerActivity::class.java).apply {
                putExtra(LINK_REF, link)
            }
        }

        fun deleteLink(context: Context, link: Link): Intent {
            return Intent(context, LinksManagerActivity::class.java).apply {
                putExtra(LINK_REF, link)
                putExtra(DELETE_ACTION, true)
            }
        }
    }

    private val settings by lazy { Settings(applicationContext) }
    private val linkViewModel: LinkViewModel by viewModels()
    private val profileViewModel: ProfileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val link: Link? = IntentHelper.getParcelable(intent, LINK_REF, Link::class.java)
        val deleteAction = intent.getBooleanExtra(DELETE_ACTION, false)

        if (link == null) {
            refreshLinks()
            return
        }

        if (deleteAction) {
            deleteLink(link)
            return
        }

        LinkFormFragment(link) {
            if (link.id == 0L) {
                linkViewModel.insert(link)
            } else {
                linkViewModel.update(link)
            }
            setResult(RESULT_OK)
            finish()
        }.show(supportFragmentManager, null)
    }

    private fun loadingDialog(): Dialog {
        val dialogView = LayoutInflater.from(this).inflate(
            R.layout.loading_dialog,
            LinearLayout(this)
        )
        return MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
    }

    private fun refreshLinks() {
        val loadingDialog = loadingDialog()
        loadingDialog.show()
        lifecycleScope.launch {
            val links = linkViewModel.activeLinks()
            links.forEach { link ->
                val profiles = profileViewModel.linkProfiles(link.id)
                runCatching {
                    val hardwareId = settings.hardwareId && !settings.hardwareIdHeader.isNullOrBlank()
                    val hardwareIdHeader = if (hardwareId) settings.hardwareIdHeader else null
                    val content = HttpHelper.get(link.address, link.userAgent, hardwareIdHeader).trim()
                    val newProfiles = if (link.type == Link.Type.Json) {
                        jsonProfiles(link, content)
                    } else {
                        subscriptionProfiles(link, content)
                    }
                    if (newProfiles.isNotEmpty()) {
                        val linkProfiles = profiles.filter { it.linkId == link.id }
                        manageProfiles(link, linkProfiles, newProfiles)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                settings.lastRefreshLinks = System.currentTimeMillis()
                TProxyService.newConfig(applicationContext)
                loadingDialog.dismiss()
                finish()
            }
        }
    }

    private fun jsonProfiles(link: Link, value: String): List<Profile> {
        val list = arrayListOf<Profile>()
        val configs = runCatching {
            val trimmed = value.trim()
            if (trimmed.startsWith("[")) {
                trimmed.decodeToJsonArray()
            } else if (trimmed.startsWith("{")) {
                buildJsonArray { add(trimmed.decodeToJsonObject()) }
            } else {
                JsonArray(emptyList())
            }
        }.getOrNull() ?: JsonArray(emptyList())

        val titleCounts = mutableMapOf<String, Int>()

        for (element in configs) {
            val configuration: JsonObject = element as? JsonObject ?: continue
            val parentRemark: String = configuration["remarks"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: link.name.ifBlank { LinkHelper.REMARK_DEFAULT }

            val rawOutbounds = configuration["outbounds"] as? JsonArray ?: JsonArray(emptyList())
            val proxyOutbounds = mutableListOf<JsonObject>()
            val nonProxyOutbounds = mutableListOf<JsonObject>()

            for (outboundElement in rawOutbounds) {
                val outboundObj = outboundElement as? JsonObject ?: continue
                if (LinkHelper.isProxyOutbound(outboundObj)) {
                    proxyOutbounds.add(outboundObj)
                } else {
                    nonProxyOutbounds.add(outboundObj)
                }
            }

            if (proxyOutbounds.size > 1) {
                // 1. Auto/Balancer profile
                val autoJson = buildJsonObject {
                    for ((key, jsonElement) in configuration) {
                        if (key != "remarks") {
                            put(key, jsonElement)
                        }
                    }
                }.encodeToString()

                val autoTitle = if (parentRemark.contains("auto", ignoreCase = true)) parentRemark else "$parentRemark (Auto)"
                list.add(Profile().apply {
                    linkId = link.id
                    name = autoTitle
                    config = autoJson
                })

                // 2. Individual server profiles
                for (proxyOutbound in proxyOutbounds) {
                    val serverName = LinkHelper.generateServerName(proxyOutbound)
                    val baseTitle = if (parentRemark.isNotBlank() && parentRemark != LinkHelper.REMARK_DEFAULT && parentRemark != link.name) {
                        "$parentRemark - $serverName"
                    } else {
                        serverName
                    }

                    val count = (titleCounts[baseTitle] ?: 0) + 1
                    titleCounts[baseTitle] = count
                    val profileTitle = if (count > 1) "$baseTitle #$count" else baseTitle

                    val serverConfigObj = buildSingleServerConfig(configuration, proxyOutbound, nonProxyOutbounds)
                    list.add(Profile().apply {
                        linkId = link.id
                        name = profileTitle
                        config = serverConfigObj.encodeToString()
                    })
                }
            } else if (proxyOutbounds.size == 1) {
                val singleProxy = proxyOutbounds[0]
                val serverName = LinkHelper.generateServerName(singleProxy)
                val baseTitle = if (parentRemark.isNotBlank() && parentRemark != LinkHelper.REMARK_DEFAULT && parentRemark != link.name) {
                    "$parentRemark - $serverName"
                } else {
                    serverName
                }

                val count = (titleCounts[baseTitle] ?: 0) + 1
                titleCounts[baseTitle] = count
                val profileTitle = if (count > 1) "$baseTitle #$count" else baseTitle

                val json = buildJsonObject {
                    for ((key, jsonElement) in configuration) {
                        if (key != "remarks") {
                            put(key, jsonElement)
                        }
                    }
                }.encodeToString()

                list.add(Profile().apply {
                    linkId = link.id
                    name = profileTitle
                    config = json
                })
            } else {
                val json = buildJsonObject {
                    for ((key, jsonElement) in configuration) {
                        if (key != "remarks") {
                            put(key, jsonElement)
                        }
                    }
                }.encodeToString()

                list.add(Profile().apply {
                    linkId = link.id
                    name = parentRemark
                    config = json
                })
            }
        }

        return list.reversed()
    }

    private fun buildSingleServerConfig(
        parentConfig: JsonObject,
        selectedProxyOutbound: JsonObject,
        nonProxyOutbounds: List<JsonObject>
    ): JsonObject {
        val proxyOutbound = buildJsonObject {
            for ((k, v) in selectedProxyOutbound) {
                if (k != "sendThrough" && k != "tag") {
                    put(k, v)
                }
            }
            put("tag", JsonPrimitive("proxy"))
        }

        val newOutbounds = buildJsonArray {
            add(proxyOutbound)
            for (outbound in nonProxyOutbounds) {
                add(outbound)
            }
            if (nonProxyOutbounds.none { it["tag"]?.jsonPrimitive?.contentOrNull == "direct" }) {
                add(buildJsonObject {
                    put("protocol", JsonPrimitive("freedom"))
                    put("tag", JsonPrimitive("direct"))
                })
            }
            if (nonProxyOutbounds.none { it["tag"]?.jsonPrimitive?.contentOrNull == "block" }) {
                add(buildJsonObject {
                    put("protocol", JsonPrimitive("blackhole"))
                    put("tag", JsonPrimitive("block"))
                })
            }
        }

        val routing = parentConfig["routing"] as? JsonObject
        val newRouting = if (routing != null) {
            val rules = routing["rules"] as? JsonArray
            val newRules = if (rules != null) {
                buildJsonArray {
                    for (ruleElement in rules) {
                        val ruleObj = ruleElement as? JsonObject ?: continue
                        val newRule = buildJsonObject {
                            for ((rk, rv) in ruleObj) {
                                if (rk == "balancerTag") {
                                    put("outboundTag", JsonPrimitive("proxy"))
                                } else {
                                    put(rk, rv)
                                }
                            }
                        }
                        add(newRule)
                    }
                }
            } else null

            buildJsonObject {
                for ((rk, rv) in routing) {
                    if (rk == "balancers") continue
                    if (rk == "rules" && newRules != null) {
                        put("rules", newRules)
                    } else {
                        put(rk, rv)
                    }
                }
            }
        } else null

        return buildJsonObject {
            for ((key, value) in parentConfig) {
                if (key == "remarks") continue
                if (key == "outbounds") {
                    put("outbounds", newOutbounds)
                } else if (key == "routing" && newRouting != null) {
                    put("routing", newRouting)
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun subscriptionProfiles(link: Link, value: String): List<Profile> {
        val trimmed = value.trim()
        val decoded = runCatching { LinkHelper.tryDecodeBase64(trimmed).trim() }.getOrNull() ?: trimmed

        if (decoded.startsWith("[") || decoded.startsWith("{")) {
            return jsonProfiles(link, decoded)
        }

        val list = arrayListOf<Profile>()
        val lines = decoded.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .reversed()

        for (line in lines) {
            if (line.startsWith("[") || line.startsWith("{")) {
                list.addAll(jsonProfiles(link, line))
                continue
            }
            val linkHelper = LinkHelper(settings, line)
            if (!linkHelper.isValid()) continue
            for ((name, configJson) in linkHelper.profiles()) {
                val profile = Profile().apply {
                    linkId = link.id
                    this.name = name
                    this.config = configJson
                }
                list.add(profile)
            }
        }

        return list
    }

    private suspend fun manageProfiles(
        link: Link, linkProfiles: List<Profile>, newProfiles: List<Profile>
    ) {
        if (newProfiles.size >= linkProfiles.size) {
            newProfiles.forEachIndexed { index, newProfile ->
                if (index >= linkProfiles.size) {
                    newProfile.linkId = link.id
                    insertProfile(newProfile)
                } else {
                    val linkProfile = linkProfiles[index]
                    updateProfile(linkProfile, newProfile)
                }
            }
            return
        }
        linkProfiles.forEachIndexed { index, linkProfile ->
            if (index >= newProfiles.size) {
                deleteProfile(linkProfile)
            } else {
                val newProfile = newProfiles[index]
                updateProfile(linkProfile, newProfile)
            }
        }
    }

    private suspend fun insertProfile(newProfile: Profile) {
        profileViewModel.create(newProfile)
    }

    private suspend fun updateProfile(linkProfile: Profile, newProfile: Profile) {
        linkProfile.name = newProfile.name
        linkProfile.config = newProfile.config
        profileViewModel.update(linkProfile)
    }

    private suspend fun deleteProfile(linkProfile: Profile) {
        profileViewModel.remove(linkProfile)
        withContext(Dispatchers.Main) {
            val selectedProfile = settings.selectedProfile
            if (selectedProfile == linkProfile.id) {
                settings.selectedProfile = 0L
            }
        }
    }

    private fun deleteLink(link: Link) {
        lifecycleScope.launch {
            profileViewModel.linkProfiles(link.id)
                .forEach { linkProfile ->
                    deleteProfile(linkProfile)
                }
            linkViewModel.delete(link)
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }
}
