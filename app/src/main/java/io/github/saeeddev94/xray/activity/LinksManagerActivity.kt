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
import io.github.saeeddev94.xray.helper.PingHelper
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

    private fun refreshLinks() {
        lifecycleScope.launch {
            val links = linkViewModel.activeLinks()
            links.forEach { link ->
                val profiles = profileViewModel.linkProfiles(link.id)
                runCatching {
                    val hardwareId = settings.hardwareId && !settings.hardwareIdHeader.isNullOrBlank()
                    val hardwareIdHeader = if (hardwareId) settings.hardwareIdHeader else null
                    val res = HttpHelper.getSubscriptionData(link.address, link.userAgent, hardwareIdHeader, applicationContext)

                    if (!res.userInfo.isNullOrBlank()) {
                        res.userInfo.split(";").forEach { part ->
                            val kv = part.trim().split("=")
                            if (kv.size == 2) {
                                val v = kv[1].trim().toLongOrNull() ?: 0L
                                when (kv[0].trim().lowercase()) {
                                    "upload" -> link.upload = v
                                    "download" -> link.download = v
                                    "total" -> link.total = v
                                    "expire" -> link.expire = v
                                }
                            }
                        }
                    }
                    if (!res.announcement.isNullOrBlank()) link.announcement = res.announcement
                    if (!res.profileWebPageUrl.isNullOrBlank()) link.siteUrl = res.profileWebPageUrl
                    if (!res.profileTitle.isNullOrBlank() && (link.name.isBlank() || link.name == "Subscription")) link.name = res.profileTitle

                    linkViewModel.update(link)

                    val content = res.body.trim()
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
                setResult(RESULT_OK)
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
                val autoJsonObj = buildAutoBalancerConfig(proxyOutbounds, nonProxyOutbounds)
                val autoJson = autoJsonObj.encodeToString()

                val cleanParentRemark = if (parentRemark.length > 30 || parentRemark.contains("http") || parentRemark.contains("fwqfw")) {
                    link.name.ifBlank { "Auto Balancer" }
                } else {
                    parentRemark
                }

                val autoTitle = "⚡ Auto (${cleanParentRemark.ifBlank { "Smart Balancer" }})"
                list.add(Profile().apply {
                    linkId = link.id
                    name = autoTitle
                    config = autoJson
                })

                val seenConfigs = mutableSetOf<String>()
                val seenAddresses = mutableSetOf<String>()

                // 2. Individual server profiles
                for (proxyOutbound in proxyOutbounds) {
                    val hostPort = PingHelper.extractHostAndPort(proxyOutbound.encodeToString())
                    val uniqueAddrKey = if (hostPort != null) "${hostPort.first}:${hostPort.second}" else ""

                    val serverConfigObj = buildSingleServerConfig(configuration, proxyOutbound, nonProxyOutbounds)
                    val configStr = serverConfigObj.encodeToString()

                    if (seenConfigs.contains(configStr) || (uniqueAddrKey.isNotBlank() && seenAddresses.contains(uniqueAddrKey))) {
                        continue // Skip duplicate configs or servers
                    }
                    seenConfigs.add(configStr)
                    if (uniqueAddrKey.isNotBlank()) seenAddresses.add(uniqueAddrKey)

                    val serverName = LinkHelper.generateServerName(proxyOutbound)
                    val baseTitle = if (cleanParentRemark.isNotBlank() && 
                        cleanParentRemark != LinkHelper.REMARK_DEFAULT && 
                        cleanParentRemark != link.name && 
                        !cleanParentRemark.contains("Auto", ignoreCase = true) &&
                        !serverName.startsWith(cleanParentRemark, ignoreCase = true) &&
                        !serverName.contains("🇩🇪") && !serverName.contains("🇺🇸") && !serverName.contains("🇫🇷") && !serverName.contains("🇫🇮") && !serverName.contains("🇹🇷") && !serverName.contains("🇳🇱") && !serverName.contains("🇷🇺")
                    ) {
                        "$cleanParentRemark - $serverName"
                    } else {
                        serverName
                    }

                    val count = (titleCounts[baseTitle] ?: 0) + 1
                    titleCounts[baseTitle] = count
                    val profileTitle = if (count > 1) "$baseTitle #$count" else baseTitle

                    list.add(Profile().apply {
                        linkId = link.id
                        name = profileTitle
                        config = configStr
                    })
                }
            } else if (proxyOutbounds.size == 1) {
                val singleProxy = proxyOutbounds[0]
                val serverName = LinkHelper.generateServerName(singleProxy)
                val baseTitle = if (parentRemark.isNotBlank() && 
                    parentRemark.length <= 30 &&
                    parentRemark != LinkHelper.REMARK_DEFAULT && 
                    parentRemark != link.name && 
                    !serverName.startsWith(parentRemark, ignoreCase = true)
                ) {
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

    private fun buildAutoBalancerConfig(
        proxyOutbounds: List<JsonObject>,
        nonProxyOutbounds: List<JsonObject>
    ): JsonObject {
        val proxyTags = mutableListOf<String>()
        val newOutbounds = buildJsonArray {
            proxyOutbounds.forEachIndexed { index, proxyObj ->
                val tag = "proxy-$index"
                proxyTags.add(tag)
                val newProxy = buildJsonObject {
                    for ((k, v) in proxyObj) {
                        if (k != "sendThrough" && k != "tag") {
                            put(k, v)
                        }
                    }
                    put("tag", JsonPrimitive(tag))
                }
                add(newProxy)
            }
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

        val observatory = buildJsonObject {
            put("subjectSelector", buildJsonArray { add(JsonPrimitive("proxy-")) })
            put("probeUrl", JsonPrimitive(settings.pingAddress.ifBlank { "https://www.google.com" }))
            put("probeInterval", JsonPrimitive("30s"))
        }

        val routing = buildJsonObject {
            put("domainStrategy", JsonPrimitive("IPIfNonMatch"))
            put("balancers", buildJsonArray {
                add(buildJsonObject {
                    put("tag", JsonPrimitive("auto-balancer"))
                    put("selector", buildJsonArray { add(JsonPrimitive("proxy-")) })
                    put("strategy", buildJsonObject {
                        put("type", JsonPrimitive("leastPing"))
                    })
                })
            })
            put("rules", buildJsonArray {
                add(buildJsonObject {
                    put("ip", buildJsonArray {
                        add(JsonPrimitive(settings.primaryDns))
                        add(JsonPrimitive(settings.secondaryDns))
                    })
                    put("port", JsonPrimitive(53))
                    put("balancerTag", JsonPrimitive("auto-balancer"))
                })
                add(buildJsonObject {
                    put("ip", buildJsonArray {
                        add(JsonPrimitive("10.0.0.0/8"))
                        add(JsonPrimitive("172.16.0.0/12"))
                        add(JsonPrimitive("192.168.0.0/16"))
                        add(JsonPrimitive("127.0.0.0/8"))
                        add(JsonPrimitive("169.254.0.0/16"))
                        add(JsonPrimitive("fc00::/7"))
                        add(JsonPrimitive("fe80::/10"))
                        add(JsonPrimitive("::1/128"))
                    })
                    put("outboundTag", JsonPrimitive("direct"))
                })
                add(buildJsonObject {
                    put("network", JsonPrimitive("tcp,udp"))
                    put("balancerTag", JsonPrimitive("auto-balancer"))
                })
            })
        }

        val sniffing = buildJsonObject {
            put("enabled", JsonPrimitive(true))
            put("destOverride", buildJsonArray {
                add(JsonPrimitive("http"))
                add(JsonPrimitive("tls"))
                add(JsonPrimitive("quic"))
            })
        }

        val inbounds = buildJsonArray {
            add(buildJsonObject {
                put("listen", JsonPrimitive(settings.socksAddress))
                put("port", JsonPrimitive(settings.socksPort.toIntOrNull() ?: 10808))
                put("protocol", JsonPrimitive("socks"))
                put("settings", buildJsonObject {
                    put("udp", JsonPrimitive(true))
                })
                put("sniffing", sniffing)
                put("tag", JsonPrimitive("socks"))
            })
        }

        val dns = buildJsonObject {
            put("servers", buildJsonArray {
                add(JsonPrimitive(settings.primaryDns))
                add(JsonPrimitive(settings.secondaryDns))
            })
        }

        return buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", JsonPrimitive("warning"))
                put("error", JsonPrimitive(settings.xrayCoreLogs().absolutePath))
            })
            put("dns", dns)
            put("inbounds", inbounds)
            put("outbounds", newOutbounds)
            put("routing", routing)
            put("observatory", observatory)
        }
    }

    private fun subscriptionProfiles(link: Link, value: String): List<Profile> {
        val trimmed = value.trim()
        val happDecrypted = if (io.github.saeeddev94.xray.helper.HappHelper.isHappContent(trimmed)) {
            io.github.saeeddev94.xray.helper.HappHelper.decryptLocal(trimmed, applicationContext)
        } else null
        val workingContent = happDecrypted ?: trimmed
        val decoded = runCatching { LinkHelper.tryDecodeBase64(workingContent).trim() }.getOrNull() ?: workingContent

        if (decoded.startsWith("[") || decoded.startsWith("{")) {
            return jsonProfiles(link, decoded)
        }

        val list = arrayListOf<Profile>()
        val seenAddresses = mutableSetOf<String>()
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
            for ((rawName, configJson) in linkHelper.profiles()) {
                val hostPort = PingHelper.extractHostAndPort(configJson)
                val uniqueKey = if (hostPort != null) "${hostPort.first}:${hostPort.second}" else configJson
                if (seenAddresses.contains(uniqueKey)) continue
                seenAddresses.add(uniqueKey)

                val formattedName = LinkHelper.addFlagEmoji(rawName)
                val profile = Profile().apply {
                    linkId = link.id
                    this.name = formattedName
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
            runCatching {
                val selectedProfileId = settings.selectedProfile
                val profilesOfLink = profileViewModel.linkProfiles(link.id)
                if (profilesOfLink.any { it.id == selectedProfileId }) {
                    settings.selectedProfile = 0L
                }
                if (settings.selectedLink == link.id) {
                    settings.selectedLink = 0L
                }
                linkViewModel.delete(link)
            }
            withContext(Dispatchers.Main) {
                setResult(RESULT_OK)
                finish()
            }
        }
    }
}
