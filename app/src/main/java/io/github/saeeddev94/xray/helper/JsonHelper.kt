package io.github.saeeddev94.xray.helper

import io.github.saeeddev94.xray.extensions.decodeToJsonArray
import io.github.saeeddev94.xray.extensions.decodeToJsonObject
import io.github.saeeddev94.xray.extensions.putValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object JsonHelper {

    fun makeObject(value: String): JsonObject = value.decodeToJsonObject()

    fun makeArray(value: String): JsonArray = value.decodeToJsonArray()

    fun getObject(
        value: JsonObject,
        key: String,
    ): JsonObject = value[key] as? JsonObject ?: JsonObject(emptyMap())

    fun getArray(
        value: JsonObject,
        key: String,
    ): JsonArray = value[key] as? JsonArray ?: JsonArray(emptyList())

    fun sanitizeStreamSettings(streamSettings: JsonObject): JsonObject {
        var modifiedStreamSettings = streamSettings
        val transportKeys = listOf("wsSettings", "httpSettings", "grpcSettings", "tcpSettings", "quicSettings")

        for (transportKey in transportKeys) {
            val transportObj = streamSettings[transportKey] as? JsonObject ?: continue
            val headersObj = transportObj["headers"] as? JsonObject ?: continue

            var hostFromHeader: String? = null
            for ((hKey, hVal) in headersObj) {
                if (hKey.equals("host", ignoreCase = true) && hostFromHeader == null) {
                    hostFromHeader = hVal.jsonPrimitive.contentOrNull
                }
            }

            if (!hostFromHeader.isNullOrBlank()) {
                val newTransportMap = mutableMapOf<String, JsonElement>()
                transportObj.forEach { (k, v) -> newTransportMap[k] = v }

                val existingHost = transportObj["host"]?.jsonPrimitive?.contentOrNull
                if (existingHost.isNullOrBlank()) {
                    newTransportMap["host"] = JsonPrimitive(hostFromHeader)
                    modifiedStreamSettings = modifiedStreamSettings.putValue(transportKey, JsonObject(newTransportMap))
                }
            }
        }
        return modifiedStreamSettings
    }

    fun sanitizeOutbound(outbound: JsonObject): JsonObject {
        val streamSettings = outbound["streamSettings"] as? JsonObject ?: return outbound
        val sanitizedStream = sanitizeStreamSettings(streamSettings)
        return outbound.putValue("streamSettings", sanitizedStream)
    }

    fun mergeObjects(
        obj1: JsonObject,
        obj2: JsonObject,
    ): JsonObject = buildJsonObject {

        // Start with everything from obj1
        for ((key, value) in obj1) {
            put(key, value)
        }

        // Merge/replace with values from obj2
        for ((key, value2) in obj2) {
            when (val value1 = obj1[key]) {
                is JsonObject if value2 is JsonObject -> {
                    put(
                        key,
                        mergeObjects(value1, value2)
                    )
                }

                is JsonArray if value2 is JsonArray -> {
                    put(
                        key,
                        mergeArrays(value1, value2)
                    )
                }

                else -> {
                    put(key, value2)
                }
            }
        }
    }

    fun mergeArrays(
        arr1: JsonArray,
        arr2: JsonArray,
        mergeKey: String = "",
    ): JsonArray {
        val result = arr1.toMutableList()

        for (value2 in arr2) {

            if (value2 is JsonObject && mergeKey.isNotEmpty()) {

                val keyValue = value2[mergeKey]
                    ?.jsonPrimitive
                    ?.contentOrNull

                if (keyValue != null) {
                    var merged = false

                    for (i in result.indices) {
                        val value1 = result[i]

                        if (value1 is JsonObject) {
                            val value1Key = value1[mergeKey]
                                ?.jsonPrimitive
                                ?.contentOrNull

                            if (value1Key == keyValue) {
                                result[i] = mergeObjects(value1, value2)
                                merged = true
                                break
                            }
                        }
                    }

                    if (merged) {
                        continue
                    }
                }
            }

            result.add(value2)
        }

        return JsonArray(result)
    }
}
