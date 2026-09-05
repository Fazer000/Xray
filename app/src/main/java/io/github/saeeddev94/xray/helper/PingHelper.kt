package io.github.saeeddev94.xray.helper

import io.github.saeeddev94.xray.extensions.decodeToJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.Socket

object PingHelper {

    suspend fun ping(host: String, port: Int, timeoutMs: Int = 3000): Long = withContext(Dispatchers.IO) {
        if (host.isBlank() || port <= 0) return@withContext -1L
        try {
            val startTime = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val elapsed = System.currentTimeMillis() - startTime
            socket.close()
            elapsed
        } catch (e: Exception) {
            -1L
        }
    }

    fun extractHostAndPort(configJson: String): Pair<String, Int>? {
        if (configJson.isBlank()) return null
        return try {
            val json = configJson.decodeToJsonObject()
            val outbounds = json["outbounds"]?.jsonArray ?: return null
            for (element in outbounds) {
                val outbound = element as? JsonObject ?: continue
                if (!LinkHelper.isProxyOutbound(outbound)) continue

                val streamSettings = outbound["streamSettings"] as? JsonObject
                val tlsSettings = streamSettings?.get("tlsSettings") as? JsonObject
                val realitySettings = streamSettings?.get("realitySettings") as? JsonObject
                val wsSettings = streamSettings?.get("wsSettings") as? JsonObject
                val headers = wsSettings?.get("headers") as? JsonObject

                val sni = tlsSettings?.get("serverName")?.jsonPrimitive?.contentOrNull
                    ?: realitySettings?.get("serverName")?.jsonPrimitive?.contentOrNull
                    ?: headers?.get("host")?.jsonPrimitive?.contentOrNull
                    ?: wsSettings?.get("host")?.jsonPrimitive?.contentOrNull
                    ?: ""

                val sendThrough = outbound["sendThrough"]?.jsonPrimitive?.contentOrNull ?: ""

                val settingsObj = outbound["settings"] as? JsonObject
                val vnext = settingsObj?.get("vnext")?.jsonArray?.firstOrNull() as? JsonObject
                val servers = settingsObj?.get("servers")?.jsonArray?.firstOrNull() as? JsonObject

                var address = vnext?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: servers?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: settingsObj?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: ""

                if (address.isBlank()) {
                    address = if (sendThrough.isNotBlank()) sendThrough else sni
                }

                val portStr = vnext?.get("port")?.jsonPrimitive?.contentOrNull
                    ?: servers?.get("port")?.jsonPrimitive?.contentOrNull
                    ?: settingsObj?.get("port")?.jsonPrimitive?.contentOrNull
                    ?: ""

                val port = portStr.toIntOrNull() ?: if (address.isNotBlank()) 443 else 0
                if (address.isNotBlank() && port > 0) {
                    return Pair(address, port)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun extractProtocolAndAddress(configJson: String): Pair<String, String>? {
        if (configJson.isBlank()) return null
        return try {
            val json = configJson.decodeToJsonObject()
            val outbounds = json["outbounds"]?.jsonArray ?: return null
            for (element in outbounds) {
                val outbound = element as? JsonObject ?: continue
                if (!LinkHelper.isProxyOutbound(outbound)) continue

                val protocol = outbound["protocol"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: ""
                val streamSettings = outbound["streamSettings"] as? JsonObject
                val network = streamSettings?.get("network")?.jsonPrimitive?.contentOrNull?.uppercase() ?: ""

                val tlsSettings = streamSettings?.get("tlsSettings") as? JsonObject
                val realitySettings = streamSettings?.get("realitySettings") as? JsonObject
                val wsSettings = streamSettings?.get("wsSettings") as? JsonObject
                val headers = wsSettings?.get("headers") as? JsonObject

                val sni = tlsSettings?.get("serverName")?.jsonPrimitive?.contentOrNull
                    ?: realitySettings?.get("serverName")?.jsonPrimitive?.contentOrNull
                    ?: headers?.get("host")?.jsonPrimitive?.contentOrNull
                    ?: wsSettings?.get("host")?.jsonPrimitive?.contentOrNull
                    ?: ""

                val sendThrough = outbound["sendThrough"]?.jsonPrimitive?.contentOrNull ?: ""

                val settingsObj = outbound["settings"] as? JsonObject
                val vnext = settingsObj?.get("vnext")?.jsonArray?.firstOrNull() as? JsonObject
                val servers = settingsObj?.get("servers")?.jsonArray?.firstOrNull() as? JsonObject

                var address = vnext?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: servers?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: settingsObj?.get("address")?.jsonPrimitive?.contentOrNull
                    ?: ""

                if (address.isBlank()) {
                    address = if (sendThrough.isNotBlank()) sendThrough else sni
                }

                val port = vnext?.get("port")?.jsonPrimitive?.contentOrNull
                    ?: servers?.get("port")?.jsonPrimitive?.contentOrNull
                    ?: settingsObj?.get("port")?.jsonPrimitive?.contentOrNull
                    ?: ""

                val protoLabel = if (network.isNotBlank() && network != protocol && network != "TCP") {
                    "$protocol-$network"
                } else {
                    protocol
                }

                val addrLabel = if (port.isNotBlank()) "$address:$port" else address
                return Pair(protoLabel, addrLabel)
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
