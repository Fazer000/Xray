package io.github.saeeddev94.xray.helper

import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.database.Config
import io.github.saeeddev94.xray.extensions.encodeToString
import io.github.saeeddev94.xray.extensions.putValue
import io.github.saeeddev94.xray.extensions.remove
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        base = base.putValue("outbounds", sanitizedOutbounds)

        val dnsObj = JsonHelper.getObject(base, "dns")
        if (!dnsObj.containsKey("queryStrategy")) {
            val dnsMap = dnsObj.toMutableMap()
            dnsMap["queryStrategy"] = kotlinx.serialization.json.JsonPrimitive(if (settings.enableIpV6) "UseIP" else "UseIPv4")
            base = base.putValue("dns", JsonObject(dnsMap))
        }

        val routingObj = JsonHelper.getObject(base, "routing")
        if (!routingObj.containsKey("domainStrategy")) {
            val routingMap = routingObj.toMutableMap()
            routingMap["domainStrategy"] = kotlinx.serialization.json.JsonPrimitive(if (settings.enableIpV6) "IPIfNonMatch" else "UseIPv4")
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

        if (!logMap.containsKey("error") || logMap["error"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            logMap["error"] = kotlinx.serialization.json.JsonPrimitive(settings.xrayCoreLogs().absolutePath)
        }
        if (!logMap.containsKey("loglevel")) {
            logMap["loglevel"] = kotlinx.serialization.json.JsonPrimitive("warning")
        }

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
