package io.github.saeeddev94.xray.helper

import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.database.Config
import io.github.saeeddev94.xray.extensions.encodeToString
import io.github.saeeddev94.xray.extensions.putValue
import io.github.saeeddev94.xray.extensions.remove
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ConfigHelper(
    settings: Settings,
    config: Config,
    base: String,
) {
    private var base: JsonObject = JsonHelper.makeObject(base)

    init {
        process("log", config.log, config.logMode)
        process("dns", config.dns, config.dnsMode)
        process("inbounds", config.inbounds, config.inboundsMode)
        process("outbounds", config.outbounds, config.outboundsMode)
        process("routing", config.routing, config.routingMode)

        ensureLogPath(settings)
        ensureInbounds(settings)
        sanitizeConfig(settings)

        if (settings.tproxyHotspot || settings.tproxyTethering) {
            sharedInbounds()
        }
    }

    private fun sanitizeConfig(settings: Settings) {
        val outbounds = JsonHelper.getArray(base, "outbounds")
        val sanitizedOutbounds = buildJsonArray {
            for (element in outbounds) {
                if (element is JsonObject) {
                    add(JsonHelper.sanitizeOutbound(element))
                } else {
                    add(element)
                }
            }
        }
        
        val outboundsList = sanitizedOutbounds.toMutableList()
        val hasDirect = outboundsList.any { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.contentOrNull == "direct" }
        val hasBlock = outboundsList.any { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.contentOrNull == "block" }
        val hasDnsOut = outboundsList.any { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.contentOrNull == "dns-out" }
        val hasFragment = outboundsList.any { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.contentOrNull == "fragment" }

        if (!hasDirect) {
            outboundsList.add(buildJsonObject {
                put("protocol", "freedom")
                put("tag", "direct")
            })
        }
        if (!hasBlock) {
            outboundsList.add(buildJsonObject {
                put("protocol", "blackhole")
                put("tag", "block")
            })
        }
        if (!hasDnsOut) {
            outboundsList.add(buildJsonObject {
                put("protocol", "dns")
                put("tag", "dns-out")
            })
        }
        if (!hasFragment) {
            outboundsList.add(buildJsonObject {
                put("protocol", "freedom")
                put("tag", "fragment")
                put("settings", buildJsonObject {
                    put("domainStrategy", "AsIs")
                    put("fragment", buildJsonObject {
                        put("packets", "tlshello")
                        put("length", "100-200")
                        put("interval", "10-20")
                    })
                })
            })
        }
        base = base.putValue("outbounds", JsonArray(outboundsList))

        val dnsObj = JsonHelper.getObject(base, "dns")
        val dnsMap = dnsObj.toMutableMap()
        if (!dnsMap.containsKey("queryStrategy")) {
            dnsMap["queryStrategy"] = kotlinx.serialization.json.JsonPrimitive(if (settings.enableIpV6) "UseIP" else "UseIPv4")
        }

        val existingHosts = (dnsMap["hosts"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (!existingHosts.containsKey("dns.google")) {
            existingHosts["dns.google"] = buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("8.8.8.8"))
                add(kotlinx.serialization.json.JsonPrimitive("8.8.4.4"))
            }
        }
        if (!existingHosts.containsKey("dns.cloudflare.com")) {
            existingHosts["dns.cloudflare.com"] = buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("1.1.1.1"))
                add(kotlinx.serialization.json.JsonPrimitive("1.0.0.1"))
            }
        }
        if (!existingHosts.containsKey("one.one.one.one")) {
            existingHosts["one.one.one.one"] = buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("1.1.1.1"))
                add(kotlinx.serialization.json.JsonPrimitive("1.0.0.1"))
            }
        }
        if (!existingHosts.containsKey("common.dot.dns.yandex.net")) {
            existingHosts["common.dot.dns.yandex.net"] = buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("77.88.8.8"))
                add(kotlinx.serialization.json.JsonPrimitive("77.88.8.1"))
            }
        }
        dnsMap["hosts"] = JsonObject(existingHosts)

        val rawServers = dnsMap["servers"] as? JsonArray
        if (rawServers != null && rawServers.isNotEmpty()) {
            val serversList = rawServers.toMutableList()
            val hasIpServer = serversList.any { elem ->
                val str = (elem as? JsonObject)?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: elem.jsonPrimitive.contentOrNull
                    ?: ""
                !str.startsWith("https://") && !str.startsWith("http://") && !str.startsWith("tcp://") && !str.startsWith("udp://") && str.any { it.isDigit() }
            }
            if (!hasIpServer) {
                serversList.add(kotlinx.serialization.json.JsonPrimitive(settings.primaryDns.ifBlank { "1.1.1.1" }))
                serversList.add(kotlinx.serialization.json.JsonPrimitive(settings.secondaryDns.ifBlank { "1.0.0.1" }))
            }
            dnsMap["servers"] = JsonArray(serversList)
        } else {
            dnsMap["servers"] = buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(settings.primaryDns.ifBlank { "1.1.1.1" }))
                add(kotlinx.serialization.json.JsonPrimitive(settings.secondaryDns.ifBlank { "1.0.0.1" }))
            }
        }

        base = base.putValue("dns", JsonObject(dnsMap))

        val geoIpFile = java.io.File(settings.context.filesDir, "geoip.dat")
        val geoSiteFile = java.io.File(settings.context.filesDir, "geosite.dat")

        if (geoIpFile.exists() && geoIpFile.length() < 100000L) {
            runCatching { geoIpFile.delete() }
        }
        if (geoSiteFile.exists() && geoSiteFile.length() < 100000L) {
            runCatching { geoSiteFile.delete() }
        }

        val hasValidGeoIp = geoIpFile.exists() && geoIpFile.length() >= 100000L
        val hasValidGeoSite = geoSiteFile.exists() && geoSiteFile.length() >= 100000L

        val privateCidrs = listOf(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "127.0.0.0/8", "169.254.0.0/16", "fc00::/7", "fe80::/10", "::1/128"
        )

        val routingObj = JsonHelper.getObject(base, "routing")
        val rulesArray = routingObj["rules"] as? JsonArray
        if (rulesArray != null) {
            val sanitizedRules = buildJsonArray {
                for (ruleElem in rulesArray) {
                    val ruleObj = ruleElem as? JsonObject ?: continue
                    val ruleMap = ruleObj.toMutableMap()

                    val ipArray = ruleObj["ip"] as? JsonArray
                    if (ipArray != null) {
                        val newIpList = mutableListOf<kotlinx.serialization.json.JsonElement>()
                        for (ipElem in ipArray) {
                            val ipStr = ipElem.jsonPrimitive.contentOrNull ?: ""
                            if (ipStr.equals("geoip:private", ignoreCase = true)) {
                                privateCidrs.forEach { cidr -> newIpList.add(JsonPrimitive(cidr)) }
                            } else if (ipStr.startsWith("geoip:", ignoreCase = true)) {
                                if (hasValidGeoIp) {
                                    newIpList.add(ipElem)
                                }
                            } else {
                                newIpList.add(ipElem)
                            }
                        }
                        if (newIpList.isNotEmpty()) {
                            ruleMap["ip"] = JsonArray(newIpList)
                        } else {
                            ruleMap.remove("ip")
                        }
                    }

                    val domainArray = ruleObj["domain"] as? JsonArray
                    if (domainArray != null) {
                        val newDomainList = mutableListOf<kotlinx.serialization.json.JsonElement>()
                        for (domainElem in domainArray) {
                            val domainStr = domainElem.jsonPrimitive.contentOrNull ?: ""
                            if (domainStr.startsWith("geosite:", ignoreCase = true)) {
                                if (hasValidGeoSite) {
                                    newDomainList.add(domainElem)
                                }
                            } else {
                                newDomainList.add(domainElem)
                            }
                        }
                        if (newDomainList.isNotEmpty()) {
                            ruleMap["domain"] = JsonArray(newDomainList)
                        } else {
                            ruleMap.remove("domain")
                        }
                    }

                    val hasCriteria = ruleMap.containsKey("ip") ||
                            ruleMap.containsKey("domain") ||
                            ruleMap.containsKey("port") ||
                            ruleMap.containsKey("network") ||
                            ruleMap.containsKey("protocol") ||
                            ruleMap.containsKey("inboundTag") ||
                            ruleMap.containsKey("user") ||
                            ruleMap.containsKey("balancerTag")

                    if (hasCriteria) {
                        add(JsonObject(ruleMap))
                    }
                }
            }
            val rulesList = sanitizedRules.toMutableList()
            val hasDnsRule = rulesList.any { rule ->
                val rObj = rule as? JsonObject
                rObj?.get("port")?.jsonPrimitive?.contentOrNull == "53" || rObj?.get("outboundTag")?.jsonPrimitive?.contentOrNull == "dns-out"
            }
            if (!hasDnsRule) {
                rulesList.add(0, buildJsonObject {
                    put("port", 53)
                    put("outboundTag", "dns-out")
                })
            }
            val routingMap = routingObj.toMutableMap()
            routingMap["rules"] = JsonArray(rulesList)
            if (!routingMap.containsKey("domainStrategy")) {
                routingMap["domainStrategy"] = JsonPrimitive("IPIfNonMatch")
            }
            base = base.putValue("routing", JsonObject(routingMap))
        } else {
            val routingMap = routingObj.toMutableMap()
            routingMap["rules"] = buildJsonArray {
                add(buildJsonObject {
                    put("port", 53)
                    put("outboundTag", "dns-out")
                })
            }
            if (!routingMap.containsKey("domainStrategy")) {
                routingMap["domainStrategy"] = JsonPrimitive("IPIfNonMatch")
            }
            base = base.putValue("routing", JsonObject(routingMap))
        }
    }

    private fun ensureInbounds(settings: Settings) {
        val inbounds = JsonHelper.getArray(base, "inbounds")
        val hasMatchingInbound = if (settings.transparentProxy) {
            inbounds.any { element ->
                val obj = element as? JsonObject
                obj?.get("protocol")?.jsonPrimitive?.contentOrNull == "dokodemo-door" &&
                        obj.get("port")?.jsonPrimitive?.contentOrNull == settings.tproxyPort.toString()
            }
        } else {
            inbounds.any { element ->
                val obj = element as? JsonObject
                val protocol = obj?.get("protocol")?.jsonPrimitive?.contentOrNull
                val port = obj?.get("port")?.jsonPrimitive?.contentOrNull
                (protocol == "socks" || protocol == "http") && port == settings.socksPort
            }
        }

        if (!hasMatchingInbound) {
            val sniffing = buildJsonObject {
                put("enabled", true)
                put("destOverride", buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("http"))
                    add(kotlinx.serialization.json.JsonPrimitive("tls"))
                    add(kotlinx.serialization.json.JsonPrimitive("quic"))
                })
            }
            val requiredInbound = if (settings.transparentProxy) {
                buildJsonObject {
                    put("listen", settings.tproxyAddress)
                    put("port", settings.tproxyPort)
                    put("protocol", "dokodemo-door")
                    put("settings", buildJsonObject {
                        put("network", "tcp,udp")
                        put("followRedirect", true)
                    })
                    put("sniffing", sniffing)
                    put("streamSettings", buildJsonObject {
                        put("sockopt", buildJsonObject {
                            put("tproxy", "tproxy")
                        })
                    })
                    put("tag", "all-in")
                }
            } else {
                buildJsonObject {
                    put("listen", settings.socksAddress)
                    put("port", settings.socksPort.toIntOrNull() ?: 10808)
                    put("protocol", "socks")
                    put("settings", buildJsonObject {
                        put("udp", true)
                        if (settings.socksUsername.isNotBlank() && settings.socksPassword.isNotBlank()) {
                            put("auth", "password")
                            put("accounts", buildJsonArray {
                                add(buildJsonObject {
                                    put("user", settings.socksUsername)
                                    put("pass", settings.socksPassword)
                                })
                            })
                        }
                    })
                    put("sniffing", sniffing)
                    put("tag", "socks")
                }
            }

            val newInbounds = buildJsonArray {
                add(requiredInbound)
                for (inbound in inbounds) {
                    add(inbound)
                }
            }
            base = base.putValue("inbounds", newInbounds)
        }
    }

    private fun ensureLogPath(settings: Settings) {
        val currentLog = JsonHelper.getObject(base, "log")
        val logMap = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        currentLog.forEach { (k, v) -> logMap[k] = v }

        logMap["error"] = kotlinx.serialization.json.JsonPrimitive(settings.xrayCoreLogs().absolutePath)
        logMap["access"] = kotlinx.serialization.json.JsonPrimitive(settings.xrayCoreLogs().absolutePath)
        logMap["loglevel"] = kotlinx.serialization.json.JsonPrimitive("debug")

        base = base.putValue("log", JsonObject(logMap))
    }

    override fun toString(): String = base.encodeToString()

    fun script(): String? {
        val key = "script"
        val value: String? = base[key]?.jsonPrimitive?.content
        base = base.remove(key)
        return value
    }

    private fun process(
        key: String,
        config: String,
        mode: Config.Mode,
    ) {
        if (mode == Config.Mode.Disable) return
        when (key == "inbounds" || key == "outbounds") {
            true -> processArray(key, config, mode)
            false -> processObject(key, config, mode)
        }
    }

    private fun processObject(
        key: String,
        config: String,
        mode: Config.Mode,
    ) {
        val oldValue = JsonHelper.getObject(base, key)
        val newValue = JsonHelper.makeObject(config)
        base = when (mode == Config.Mode.Replace) {
            true -> newValue
            false -> JsonHelper.mergeObjects(oldValue, newValue)
        }.let { base.putValue(key, it) }
    }

    private fun processArray(
        key: String,
        config: String,
        mode: Config.Mode,
    ) {
        val oldValue = JsonHelper.getArray(base, key)
        val newValue = JsonHelper.makeArray(config)
        base = when (mode == Config.Mode.Replace) {
            true -> newValue
            false -> JsonHelper.mergeArrays(oldValue, newValue, "protocol")
        }.let { base.putValue(key, it) }
    }

    private fun sharedInbounds() {
        val inbounds = JsonHelper.getArray(base, "inbounds")
        base = buildJsonArray {
            for (inbound in inbounds) {
                if (inbound is JsonObject) {
                    add(inbound.remove("listen"))
                }
            }
        }.let { base.putValue("inbounds", it) }
    }
}
