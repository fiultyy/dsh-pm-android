package dev.dshpm.proto.codec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * Tolerant JSON extraction/put helpers.
 *
 * Tolerance rules (spec §0 consumer duty): absent field → null; JsonNull → null;
 * unknown-typed value → null (never throw). Numeric fields accept both int and
 * float literals ("5" and "5.0").
 */
internal fun JsonObject?.optString(name: String): String? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject?.optBoolean(name: String): Boolean? =
    (this?.get(name) as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject?.optDouble(name: String): Double? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

internal fun JsonObject?.optLong(name: String): Long? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

internal fun JsonObject?.optPrimitive(name: String): JsonPrimitive? =
    (this?.get(name) as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonObject?.optElement(name: String): JsonElement? =
    this?.get(name)?.takeIf { it !is JsonNull }

internal fun JsonObject?.optArray(name: String): JsonArray? =
    this?.get(name) as? JsonArray

internal fun JsonObject?.optObject(name: String): JsonObject? =
    this?.get(name) as? JsonObject

internal fun JsonObject?.optStringList(name: String): List<String>? =
    optArray(name)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** put-if-not-null (kotlinx `put(key, null)` would insert JsonNull — we want omission). */
internal fun JsonObjectBuilder.putOpt(name: String, value: String?) {
    if (value != null) put(name, value)
}

internal fun JsonObjectBuilder.putOpt(name: String, value: Boolean?) {
    if (value != null) put(name, value)
}

internal fun JsonObjectBuilder.putOpt(name: String, value: Long?) {
    if (value != null) put(name, value)
}

internal fun JsonObjectBuilder.putOpt(name: String, value: Double?) {
    if (value != null) put(name, value)
}

internal fun JsonObjectBuilder.putOpt(name: String, value: List<String>?) {
    if (value != null) putJsonArray(name) { value.forEach { add(it) } }
}

internal fun JsonObjectBuilder.putOptElement(name: String, value: JsonElement?) {
    if (value != null) put(name, value)
}

/** JsonElement field whose null renders as an explicit JSON null (Python `None` semantics). */
internal fun JsonObjectBuilder.putElementOrNull(name: String, value: JsonElement?) {
    put(name, value ?: JsonNull)
}

internal fun jsonObjectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) put(k, v ?: JsonNull)
}
