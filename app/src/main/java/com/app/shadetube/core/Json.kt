package com.app.shadetube.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * nlohmann::json-style helpers over kotlinx.serialization's JsonElement tree, so the C++ parsing code ports almost
 * line by line. Every accessor is null-safe and type-tolerant (a missing key or a wrong type gives the default).
 *
 *   val name = item.str("name")                       // j.value("name", "")
 *   val w = j.at("data", "me", "profile").str("name") // j["data"]["me"]["profile"]["name"]
 *   for (row in j.at("items").arr) ...                // j.value("items", json::array())
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** Strict parser (no lenient unquoted values), so HTML error pages are not mistaken for JSON. */
private val StrictJson: Json = Json { ignoreUnknownKeys = true }

/** Parse, or null when the text is not JSON (nlohmann's parse(..., nullptr, false) + is_discarded()). Strict. */
fun parseJsonOrNull(text: String): JsonElement? = runCatching { StrictJson.parseToJsonElement(text) }.getOrNull()

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
val JsonElement?.isNull: Boolean get() = this == null || this is JsonNull
val JsonElement?.isObject: Boolean get() = this is JsonObject
val JsonElement?.isArray: Boolean get() = this is JsonArray
val JsonElement?.isString: Boolean get() = this is JsonPrimitive && this.isString

/** Child by key (objects) or index (arrays); null otherwise. */
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

/** Walks a path of String keys and Int indices: j.at("data", "items", 0, "name"). */
fun JsonElement?.at(vararg path: Any): JsonElement? {
    var cur: JsonElement? = this
    for (p in path) {
        cur = when (p) {
            is String -> cur[p]
            is Int -> cur[p]
            else -> null
        } ?: return null
    }
    return cur
}

private val JsonElement?.prim: JsonPrimitive? get() = this as? JsonPrimitive

/** String value of a primitive (numbers/bools are stringified), else null. */
val JsonElement?.stringOrNull: String? get() = this.prim?.takeIf { it !is JsonNull }?.contentOrNull
val JsonElement?.longOrNull: Long? get() = this.prim?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.toLongOrNull() }
val JsonElement?.doubleOrNull: Double? get() = this.prim?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
val JsonElement?.boolOrNull: Boolean? get() = this.prim?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

fun JsonElement?.asString(default: String = ""): String = stringOrNull ?: default
fun JsonElement?.asLong(default: Long = 0): Long = longOrNull ?: default
fun JsonElement?.asInt(default: Int = 0): Int = longOrNull?.toInt() ?: default
fun JsonElement?.asDouble(default: Double = 0.0): Double = doubleOrNull ?: default
fun JsonElement?.asBool(default: Boolean = false): Boolean = boolOrNull ?: default

/** j.value(key, default) equivalents. */
fun JsonElement?.str(key: String, default: String = ""): String = this[key].stringOrNull ?: default
fun JsonElement?.int(key: String, default: Int = 0): Int = this[key].longOrNull?.toInt() ?: default
fun JsonElement?.long(key: String, default: Long = 0): Long = this[key].longOrNull ?: default
fun JsonElement?.double(key: String, default: Double = 0.0): Double = this[key].doubleOrNull ?: default
fun JsonElement?.float(key: String, default: Float = 0f): Float = this[key].doubleOrNull?.toFloat() ?: default
fun JsonElement?.bool(key: String, default: Boolean = false): Boolean = this[key].boolOrNull ?: default
fun JsonElement?.has(key: String): Boolean = (this as? JsonObject)?.containsKey(key) == true
fun JsonElement?.strings(key: String): List<String> = this[key].arr.mapNotNull { it.stringOrNull }

/** Compact JSON text of any element (nlohmann's dump()). */
fun JsonElement.dump(): String = AppJson.encodeToString(JsonElement.serializer(), this)
private val PrettyJson = Json(AppJson) { prettyPrint = true }
fun JsonElement.dumpPretty(): String = PrettyJson.encodeToString(JsonElement.serializer(), this)

// ---- building -------------------------------------------------------------------------------------------------------

/** jsonOf("a" to 1, "b" to listOf("x")) — values: null, String, Number, Boolean, JsonElement, Map, Iterable, Array. */
fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to toJsonElement(v) })

fun jsonArrayOf(vararg items: Any?): JsonArray = JsonArray(items.map { toJsonElement(it) })

fun toJsonElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Enum<*> -> JsonPrimitive(v.name)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJsonElement(x) })
    is Iterable<*> -> JsonArray(v.map { toJsonElement(it) })
    is Array<*> -> JsonArray(v.map { toJsonElement(it) })
    is FloatArray -> JsonArray(v.map { JsonPrimitive(it) })
    is IntArray -> JsonArray(v.map { JsonPrimitive(it) })
    is LongArray -> JsonArray(v.map { JsonPrimitive(it) })
    else -> JsonPrimitive(v.toString())
}

private val STRICT_NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

/**
 * nlohmann's `json::parse(text, nullptr, false)`: null unless the text is valid JSON. kotlinx' tree parser accepts
 * unquoted tokens ("<html>..." parses as a primitive), so every unquoted literal is checked to be a number / true /
 * false / null.
 */
fun parseStrictJson(text: String): JsonElement? {
    val e = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
    return if (strictValid(e)) e else null
}

private fun strictValid(e: JsonElement): Boolean = when (e) {
    is JsonNull -> true
    is JsonPrimitive -> e.isString || e.content == "true" || e.content == "false" || STRICT_NUMBER.matches(e.content)
    is JsonObject -> e.values.all { strictValid(it) }
    is JsonArray -> e.all { strictValid(it) }
}
