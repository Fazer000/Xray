package io.github.saeeddev94.xray.helper

import XrayCore.XrayCore
import android.util.Base64
import io.github.saeeddev94.xray.Settings
import io.github.saeeddev94.xray.extensions.decodeToJsonObject
import io.github.saeeddev94.xray.extensions.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI

class LinkHelper(
    private val settings: Settings,
    link: String,
) {

    private val success: Boolean
    private val proxyOutbounds = mutableListOf<JsonObject>()
    private var defaultRemark: String = REMARK_DEFAULT

    init {
        val base64: String = XrayCore.json(link)
        val response = runCatching {
            tryDecodeBase64(base64).decodeToJsonObject()
        }.getOrDefault(JsonObject(emptyMap()))

        success = response["success"]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: false

        val rawOutbounds = response["data"]
            ?.jsonObject
            ?.get("outbounds")
            ?.jsonArray

        if (rawOutbounds != null) {
            for (element in rawOutbounds) {
                val obj = element as? JsonObject ?: continue
                if (isProxyOutbound(obj)) {
                    proxyOutbounds.add(obj)
                }
            }
        }
    }

    companion object {
        const val REMARK_DEFAULT = "New Profile"
        const val LINK_DEFAULT = "New Link"

        fun isProxyOutbound(outbound: JsonObject): Boolean {
            val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
            val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: ""
            if (protocol in listOf("freedom", "blackhole", "dns", "v2rayou")) return false
            if (tag in listOf("direct", "block", "dns-out", "direct-fragment")) return false
            return protocol.isNotEmpty()
        }

        fun generateServerName(outbound: JsonObject, parentRemark: String = ""): String {
            val sendThrough = outbound["sendThrough"]?.jsonPrimitive?.contentOrNull
            if (!sendThrough.isNullOrBlank()) return sendThrough

            val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull ?: ""
            if (tag.isNotEmpty() && !tag.startsWith("proxy", ignoreCase = true)) {
                return tag
            }

            val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull ?: ""
            val streamSettings = outbound["streamSettings"] as? JsonObject
            val network = streamSettings?.get("network")?.jsonPrimitive?.contentOrNull ?: ""

            val tlsSettings = streamSettings?.get("tlsSettings") as? JsonObject
            val wsSettings = streamSettings?.get("wsSettings") as? JsonObject
            val grpcSettings = streamSettings?.get("grpcSettings") as? JsonObject
            val realitySettings = streamSettings?.get("realitySettings") as? JsonObject
            val headers = wsSettings?.get("headers") as? JsonObject

            val sni = tlsSettings?.get("serverName")?.jsonPrimitive?.contentOrNull
                ?: realitySettings?.get("serverName")?.jsonPrimitive?.contentOrNull
                ?: headers?.get("host")?.jsonPrimitive?.contentOrNull
                ?: wsSettings?.get("host")?.jsonPrimitive?.contentOrNull
                ?: grpcSettings?.get("serviceName")?.jsonPrimitive?.contentOrNull

            val settingsObj = outbound["settings"] as? JsonObject
            val vnext = settingsObj?.get("vnext")?.jsonArray?.firstOrNull() as? JsonObject
            val servers = settingsObj?.get("servers")?.jsonArray?.firstOrNull() as? JsonObject

            val address = vnext?.get("address")?.jsonPrimitive?.contentOrNull
                ?: servers?.get("address")?.jsonPrimitive?.contentOrNull
                ?: settingsObj?.get("address")?.jsonPrimitive?.contentOrNull
                ?: ""

            val port = vnext?.get("port")?.jsonPrimitive?.contentOrNull
                ?: servers?.get("port")?.jsonPrimitive?.contentOrNull
                ?: settingsObj?.get("port")?.jsonPrimitive?.contentOrNull
                ?: ""

            val protoUpper = protocol.uppercase()
            val netUpper = network.uppercase()
            val protoLabel = if (netUpper.isNotEmpty() && netUpper != protoUpper) {
                "$protoUpper-$netUpper"
            } else {
                protoUpper
            }

            val hostLabel = when {
                !sni.isNullOrBlank() && address.isNotBlank() && sni != address -> "$sni [$address]"
                !sni.isNullOrBlank() -> sni
                address.isNotBlank() -> address
                else -> if (tag.isNotBlank()) tag else "Server"
            }

            val portLabel = if (port.isNotBlank()) ":$port" else ""
            val name = "$hostLabel ($protoLabel$portLabel)"

            return if (parentRemark.isNotBlank() && parentRemark != REMARK_DEFAULT) {
                "$parentRemark - $name"
            } else {
                name
            }
        }

        fun remark(uri: URI, default: String = ""): String {
            val name = uri.fragment ?: ""
            return name.ifEmpty { default }
        }

        fun tryDecodeBase64(value: String): String {
            return runCatching {
                val byteArray = Base64.decode(value, Base64.DEFAULT)
                String(byteArray)
            }.getOrNull() ?: value
        }
    }

    fun isValid(): Boolean = success && proxyOutbounds.isNotEmpty()

    fun count(): Int = proxyOutbounds.size

    fun remark(): String = remark(0)

    fun remark(index: Int): String {
        val outbound = proxyOutbounds.getOrNull(index) ?: return REMARK_DEFAULT
        val sendThrough = outbound["sendThrough"]?.jsonPrimitive?.contentOrNull
        if (!sendThrough.isNullOrBlank()) return sendThrough
        return generateServerName(outbound, defaultRemark)
    }

    fun json(): String = json(0)

    fun json(index: Int): String {
        val outbound = proxyOutbounds.getOrNull(index) ?: return ""
        return config(outbound).encodeToString() + "\n"
    }

    fun profiles(): List<Pair<String, String>> {
        val titleCounts = mutableMapOf<String, Int>()
        return proxyOutbounds.mapIndexed { index, outbound ->
            val baseName = remark(index)
            val currentCount = (titleCounts[baseName] ?: 0) + 1
            titleCounts[baseName] = currentCount
            val finalName = if (currentCount > 1) "$baseName #$currentCount" else baseName
            Pair(finalName, json(index))
        }
    }

    private fun log(): JsonObject {
        return buildJsonObject {
            put("loglevel", "warning")
        }
    }

    private fun dns(): JsonObject {
        return buildJsonObject {
            put(
                "servers",
                buildJsonArray {
                    add(JsonPrimitive(settings.primaryDns))
                    add(JsonPrimitive(settings.secondaryDns))
                }
            )
        }
    }

    private fun inbounds(): JsonArray {
        val sniffing = buildJsonObject {
            put("enabled", true)

            put(
                "destOverride",
                buildJsonArray {
                    add(JsonPrimitive("http"))
                    add(JsonPrimitive("tls"))
                    add(JsonPrimitive("quic"))
                }
            )
        }

        val tproxy = buildJsonObject {
            put("listen", settings.tproxyAddress)
            put("port", settings.tproxyPort)
            put("protocol", "dokodemo-door")

            put(
                "settings",
                buildJsonObject {
                    put("network", "tcp,udp")
                    put("followRedirect", true)
                }
            )

            put("sniffing", sniffing)

            put(
                "streamSettings",
                buildJsonObject {
                    put(
                        "sockopt",
                        buildJsonObject {
                            put("tproxy", "tproxy")
                        }
                    )
                }
            )

            put("tag", "all-in")
        }

        val socksSettings = buildJsonObject {
            put("udp", true)

            if (
                settings.socksUsername.trim().isNotEmpty() &&
                settings.socksPassword.trim().isNotEmpty()
            ) {
                put("auth", "password")

                put(
                    "accounts",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("user", settings.socksUsername)
                                put("pass", settings.socksPassword)
                            }
                        )
                    }
                )
            }
        }

        val socks = buildJsonObject {
            put("listen", settings.socksAddress)
            put("port", settings.socksPort.toInt())
            put("protocol", "socks")
            put("settings", socksSettings)
            put("sniffing", sniffing)
            put("tag", "socks")
        }

        return buildJsonArray {
            when (settings.transparentProxy) {
                true -> add(tproxy)
                false -> add(socks)
            }
        }
    }

    private fun outbounds(targetOutbound: JsonObject): JsonArray {
        defaultRemark = targetOutbound["sendThrough"]
            ?.jsonPrimitive
            ?.contentOrNull ?: REMARK_DEFAULT

        val proxy = buildJsonObject {
            for ((key, value) in targetOutbound) {
                if (key != "sendThrough" && key != "tag") {
                    put(key, value)
                }
            }

            put("tag", "proxy")
        }

        val direct = buildJsonObject {
            put("protocol", "freedom")
            put("tag", "direct")
        }

        val block = buildJsonObject {
            put("protocol", "blackhole")
            put("tag", "block")
        }

        val dns = buildJsonObject {
            put("protocol", "dns")
            put("tag", "dns-out")
        }

        return buildJsonArray {
            add(proxy)
            add(direct)
            add(block)
            if (settings.transparentProxy) add(dns)
        }
    }

    private fun routing(): JsonObject {
        val proxyDns = buildJsonObject {
            if (settings.transparentProxy) {
                put("network", "udp")
                put("port", 53)
                put(
                    "inboundTag",
                    buildJsonArray {
                        add(JsonPrimitive("all-in"))
                    }
                )
                put("outboundTag", "dns-out")
            } else {
                put(
                    "ip",
                    buildJsonArray {
                        add(JsonPrimitive(settings.primaryDns))
                        add(JsonPrimitive(settings.secondaryDns))
                    }
                )
                put("port", 53)
                put("outboundTag", "proxy")
            }
        }

        val directPrivate = buildJsonObject {
            put(
                "ip",
                buildJsonArray {
                    add(JsonPrimitive("geoip:private"))
                }
            )
            put("outboundTag", "direct")
        }

        return buildJsonObject {
            put("domainStrategy", "IPIfNonMatch")
            put(
                "rules",
                buildJsonArray {
                    add(proxyDns)
                    add(directPrivate)
                }
            )
        }
    }

    private fun config(targetOutbound: JsonObject): JsonObject {
        return buildJsonObject {
            put("log", log())
            put("dns", dns())
            put("inbounds", inbounds())
            put("outbounds", outbounds(targetOutbound))
            put("routing", routing())
        }
    }
}
