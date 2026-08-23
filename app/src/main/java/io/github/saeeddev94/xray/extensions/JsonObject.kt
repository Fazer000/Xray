package io.github.saeeddev94.xray.extensions

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

fun JsonObject.putValue(
    key: String,
    value: JsonElement,
): JsonObject = buildJsonObject {
    for ((existingKey, existingValue) in this@putValue) {
        if (existingKey != key) {
            put(existingKey, existingValue)
        }
    }
    put(key, value)
}
