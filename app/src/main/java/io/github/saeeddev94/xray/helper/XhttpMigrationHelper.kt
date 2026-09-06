package io.github.saeeddev94.xray.helper

import io.github.saeeddev94.xray.extensions.encodeToString
import io.github.saeeddev94.xray.extensions.putValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object XhttpMigrationHelper {

    /**
     * Checks whether an outbound or profile config contains WebSocket transport that can be migrated to XHTTP.
     */
    fun canMigrate(configJson: String): Boolean {
        val jsonObject = runCatching { JsonHelper.makeObject(configJson) }.getOrNull() ?: return false
        return canMigrate(jsonObject)
    }

    fun canMigrate(configObj: JsonObject): Boolean {
        val outbounds = JsonHelper.getArray(configObj, "outbounds")
        if (outbounds.isNotEmpty()) {
            return outbounds.any { element ->
                val outbound = element as? JsonObject ?: return@any false
                isWebSocketOutbound(outbound)
            }
        }
        return isWebSocketOutbound(configObj)
    }

    private fun isWebSocketOutbound(outbound: JsonObject): Boolean {
        val streamSettings = outbound["streamSettings"] as? JsonObject ?: return false
        val network = streamSettings["network"]?.jsonPrimitive?.contentOrNull
        return network == "ws" || streamSettings.containsKey("wsSettings")
    }

    /**
     * Migrates WebSocket (ws) transport in all outbounds of a config to XHTTP (H2/H3).
     */
    fun migrate(configJson: String): String {
        val jsonObject = runCatching { JsonHelper.makeObject(configJson) }.getOrNull() ?: return configJson
        val migratedObj = migrate(jsonObject)
        return migratedObj.encodeToString()
    }

    fun migrate(configObj: JsonObject): JsonObject {
        val outbounds = JsonHelper.getArray(configObj, "outbounds")
        if (outbounds.isNotEmpty()) {
            val newOutbounds = buildJsonArray {
                for (element in outbounds) {
                    if (element is JsonObject) {
                        add(migrateOutbound(element))
                    } else {
                        add(element)
                    }
                }
            }
            return configObj.putValue("outbounds", newOutbounds)
        }

        if (isWebSocketOutbound(configObj)) {
            return migrateOutbound(configObj)
        }

        return configObj
    }

    /**
     * Migrates a single outbound JsonObject from wsSettings -> xhttpSettings.
     */
    fun migrateOutbound(outbound: JsonObject): JsonObject {
        val streamSettings = outbound["streamSettings"] as? JsonObject ?: return outbound
        val network = streamSettings["network"]?.jsonPrimitive?.contentOrNull
        if (network != "ws" && !streamSettings.containsKey("wsSettings")) {
            return outbound
        }

        val wsSettings = streamSettings["wsSettings"] as? JsonObject ?: JsonObject(emptyMap())

        val path = wsSettings["path"]?.jsonPrimitive?.contentOrNull ?: "/"
        var host = wsSettings["host"]?.jsonPrimitive?.contentOrNull
        val headersObj = wsSettings["headers"] as? JsonObject

        val extraHeaders = mutableMapOf<String, JsonElement>()
        if (headersObj != null) {
            for ((hKey, hVal) in headersObj) {
                if (hKey.equals("host", ignoreCase = true)) {
                    if (host.isNullOrBlank()) {
                        host = hVal.jsonPrimitive.contentOrNull
                    }
                } else {
                    extraHeaders[hKey] = hVal
                }
            }
        }

        val xhttpSettings = buildJsonObject {
            put("path", JsonPrimitive(path))
            if (!host.isNullOrBlank()) {
                put("host", JsonPrimitive(host))
            }
            put("mode", JsonPrimitive("auto"))
            if (extraHeaders.isNotEmpty()) {
                put("headers", JsonObject(extraHeaders))
            }
        }

        val newStreamSettingsMap = mutableMapOf<String, JsonElement>()
        streamSettings.forEach { (k, v) ->
            if (k != "wsSettings") {
                newStreamSettingsMap[k] = v
            }
        }
        newStreamSettingsMap["network"] = JsonPrimitive("xhttp")
        newStreamSettingsMap["xhttpSettings"] = xhttpSettings

        return outbound.putValue("streamSettings", JsonObject(newStreamSettingsMap))
    }
}
