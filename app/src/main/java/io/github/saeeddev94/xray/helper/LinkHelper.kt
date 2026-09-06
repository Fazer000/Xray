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
            if (tag in listOf("direct", "block", "dns-out", "direct-fragment", "fragment")) return false
            return protocol.isNotEmpty()
        }

        fun cleanServerName(rawName: String): String {
            if (rawName.isBlank()) return "🌐 Сервер"

            var name = runCatching {
                java.net.URLDecoder.decode(rawName, "UTF-8")
            }.getOrNull() ?: rawName

            name = name.trim()

            val prefixesToRemove = listOf(
                "vless://", "vmess://", "trojan://", "ss://", "shadowsocks://",
                "[VLESS]", "[VMESS]", "[TROJAN]", "[SS]", "[CF]", "[CDN]", "[DIRECT]"
            )
            for (prefix in prefixesToRemove) {
                if (name.startsWith(prefix, ignoreCase = true)) {
                    name = name.substring(prefix.length).trim()
                }
            }

            val techGarbagePatterns = listOf(
                Regex("""(?i)\s*\|\s*(vless|vmess|trojan|ss|shadowsocks|ws|reality|xhttp|grpc|h2|tcp|udp).*$"""),
                Regex("""(?i)\s*-\s*🌐\s*mobile-cf-\d+.*$"""),
                Regex("""(?i)\s*-\s*mobile-cf-\d+.*$"""),
                Regex("""(?i)\s*xray-\w+-.*$"""),
                Regex("""(?i)\s*node-\d+.*$"""),
                Regex("""\s*\[(fast|low|high|premium|cf|cdn)\]""")
            )
            for (pattern in techGarbagePatterns) {
                name = pattern.replace(name, "").trim()
            }

            var existingFlag = ""
            val flagRegex = Regex("""[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]""")
            val flagMatch = flagRegex.find(name)
            if (flagMatch != null) {
                existingFlag = flagMatch.value
                name = name.replace(flagRegex, "").trim()
            }

            data class CountryInfo(val flag: String, val nameRu: String, val keywords: List<String>)
            val countryTable = listOf(
                CountryInfo("🇩🇪", "Германия", listOf("germany", "deutschland", "frankfurt", "berlin", "munich", "münchen", "de")),
                CountryInfo("🇺🇸", "США", listOf("usa", "united states", "america", "new york", "los angeles", "miami", "chicago", "dallas", "us")),
                CountryInfo("🇫🇮", "Финляндия", listOf("finland", "helsinki", "fi")),
                CountryInfo("🇳🇱", "Нидерланды", listOf("netherlands", "holland", "amsterdam", "nl")),
                CountryInfo("🇹🇷", "Турция", listOf("turkey", "türkiye", "istanbul", "ankara", "tr")),
                CountryInfo("🇫🇷", "Франция", listOf("france", "paris", "fr")),
                CountryInfo("🇬🇧", "Великобритания", listOf("united kingdom", "great britain", "london", "uk", "gb")),
                CountryInfo("🇷🇺", "Россия", listOf("russia", "moscow", "spb", "petersburg", "ru")),
                CountryInfo("🇪🇸", "Испания", listOf("spain", "madrid", "barcelona", "es")),
                CountryInfo("🇮🇹", "Италия", listOf("italy", "rome", "milan", "it")),
                CountryInfo("🇸🇬", "Сингапур", listOf("singapore", "sg")),
                CountryInfo("🇯🇵", "Япония", listOf("japan", "tokyo", "jp")),
                CountryInfo("🇸🇪", "Швеция", listOf("sweden", "stockholm", "se")),
                CountryInfo("🇵🇱", "Польша", listOf("poland", "warsaw", "pl")),
                CountryInfo("🇨🇦", "Канада", listOf("canada", "toronto", "montreal", "ca")),
                CountryInfo("🇺🇦", "Украина", listOf("ukraine", "kyiv", "kiev", "ua")),
                CountryInfo("🇰🇿", "Казахстан", listOf("kazakhstan", "almaty", "astana", "kz")),
                CountryInfo("🇬🇪", "Грузия", listOf("georgia", "tbilisi", "ge")),
                CountryInfo("🇦🇲", "Армения", listOf("armenia", "yerevan", "am")),
                CountryInfo("🇦🇪", "ОАЭ", listOf("uae", "dubai", "ae")),
                CountryInfo("🇨🇭", "Швейцария", listOf("switzerland", "zurich", "ch")),
                CountryInfo("🇦Т", "Австрия", listOf("austria", "vienna", "at")),
                CountryInfo("🇨🇿", "Чехия", listOf("czech", "prague", "cz")),
                CountryInfo("🇭🇰", "Гонконг", listOf("hong kong", "hongkong", "hk")),
                CountryInfo("🇰🇷", "Южная Корея", listOf("south korea", "korea", "seoul", "kr")),
                CountryInfo("🇦🇺", "Австралия", listOf("australia", "sydney", "au"))
            )

            var matchedCountry: CountryInfo? = null
            val lowerName = name.lowercase()

            for (country in countryTable) {
                if (existingFlag == country.flag) {
                    matchedCountry = country
                    break
                }
                val found = country.keywords.any { kw ->
                    when {
                        kw.length <= 3 -> {
                            lowerName.contains("[${kw}]") || lowerName.contains("(${kw})") ||
                            lowerName.contains(" ${kw} ") || lowerName.startsWith("${kw}-") ||
                            lowerName.startsWith("${kw}_") || lowerName.contains("-${kw}-") ||
                            lowerName.contains("_${kw}_")
                        }
                        else -> lowerName.contains(kw)
                    }
                }
                if (found) {
                    matchedCountry = country
                    break
                }
            }

            val finalFlag = existingFlag.ifEmpty { matchedCountry?.flag ?: "🌐" }

            var label = name
            matchedCountry?.let { c ->
                val toRemove = (c.keywords + listOf(c.nameRu.lowercase(), "germany", "russia", "turkey", "finland", "netherlands", "usa", "france")).distinct()
                for (kw in toRemove) {
                    if (kw.length > 3) {
                        label = Regex("(?i)\\b" + Regex.escape(kw) + "\\b").replace(label, "").trim()
                    }
                }
            }

            label = label.replace(Regex("""^[-\s_|\.:;()\[\]#]+"""), "")
                         .replace(Regex("""[-\s_|\.:;()\[\]#]+$"""), "")
                         .trim()

            if (label.isBlank() || label.length > 45 || label.startsWith("http", ignoreCase = true)) {
                val countryName = matchedCountry?.nameRu ?: ""
                return if (countryName.isNotBlank()) "$finalFlag $countryName" else "$finalFlag Сервер"
            }

            val countryName = matchedCountry?.nameRu ?: ""
            return when {
                countryName.isNotBlank() && !label.contains(countryName, ignoreCase = true) -> {
                    "$finalFlag $countryName · $label"
                }
                else -> "$finalFlag $label"
            }
        }

        fun addFlagEmoji(name: String): String {
            return cleanServerName(name)
        }

        fun generateServerName(outbound: JsonObject, parentRemark: String = ""): String {
            val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            val sendThrough = outbound["sendThrough"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
            val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: ""
            val streamSettings = outbound["streamSettings"] as? JsonObject

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
                ?: ""

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

            var rawLabel = when {
                tag.isNotBlank() && !tag.equals("proxy", ignoreCase = true) && !tag.startsWith("proxy-", ignoreCase = true) -> tag
                sendThrough.isNotBlank() -> sendThrough
                sni.isNotBlank() && address.isNotBlank() && sni != address -> "$sni ($address)"
                sni.isNotBlank() -> sni
                address.isNotBlank() -> if (port.isNotBlank()) "$address:$port" else address
                protocol.isNotBlank() -> "$protocol Server"
                else -> "Server"
            }

            if (rawLabel.equals("Server", ignoreCase = true) || 
                rawLabel.startsWith("vless-", ignoreCase = true) || 
                rawLabel.startsWith("vmess-", ignoreCase = true) || 
                rawLabel.startsWith("trojan-", ignoreCase = true) || 
                rawLabel.startsWith("shadowsocks-", ignoreCase = true)
            ) {
                rawLabel = when {
                    address.isNotBlank() -> if (sni.isNotBlank() && sni != address) "$sni ($address)" else address
                    protocol.isNotBlank() -> "$protocol Node"
                    else -> "VPN Server"
                }
            }

            return addFlagEmoji(rawLabel)
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
            put("loglevel", "debug")
            put("error", settings.xrayCoreLogs().absolutePath)
            put("access", settings.xrayCoreLogs().absolutePath)
        }
    }

    private fun dns(): JsonObject {
        return buildJsonObject {
            put(
                "servers",
                buildJsonArray {
                    add(JsonPrimitive("1.1.1.1"))
                    add(JsonPrimitive("8.8.8.8"))
                    add(JsonPrimitive("77.88.8.8"))
                    add(buildJsonObject {
                        put("address", "https://1.1.1.1/dns-query")
                        put("skipFallback", true)
                    })
                    add(buildJsonObject {
                        put("address", "https://8.8.8.8/dns-query")
                        put("skipFallback", true)
                    })
                }
            )
            put("queryStrategy", if (settings.enableIpV6) "UseIP" else "UseIPv4")
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

        val sanitizedTarget = JsonHelper.sanitizeOutbound(targetOutbound)

        val proxy = buildJsonObject {
            for ((key, value) in sanitizedTarget) {
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

        val fragment = buildJsonObject {
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
        }

        return buildJsonArray {
            add(proxy)
            add(direct)
            add(block)
            add(dns)
            add(fragment)
        }
    }

    private fun routing(): JsonObject {
        val proxyDns = buildJsonObject {
            put("port", 53)
            put("outboundTag", "dns-out")
        }

        val directPrivate = buildJsonObject {
            put(
                "ip",
                buildJsonArray {
                    add(JsonPrimitive("10.0.0.0/8"))
                    add(JsonPrimitive("172.16.0.0/12"))
                    add(JsonPrimitive("192.168.0.0/16"))
                    add(JsonPrimitive("127.0.0.0/8"))
                    add(JsonPrimitive("169.254.0.0/16"))
                    add(JsonPrimitive("fc00::/7"))
                    add(JsonPrimitive("fe80::/10"))
                    add(JsonPrimitive("::1/128"))
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
