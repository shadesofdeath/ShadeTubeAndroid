package com.app.shadetube.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Type-strict JSON accessors with the exact semantics of the small helpers at the top of SpotifyApi.cpp
 * (`at`, `str`, `integer`, `is_string()`, `is_number()`...): a value of the wrong type counts as missing. The general
 * core/Json helpers are deliberately type-tolerant (they stringify numbers), which would change which fallbacks the
 * Spotify parsers take.
 */
internal object SpJson {
    /** `at(j, key)`: the child of an object, else null. */
    fun at(j: JsonElement?, key: String): JsonElement? = (j as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }

    fun isStr(v: JsonElement?): Boolean = v is JsonPrimitive && v.isString
    fun isNum(v: JsonElement?): Boolean = v is JsonPrimitive && !v.isString && v !is JsonNull && v.doubleOrNull != null
    fun isInt(v: JsonElement?): Boolean = isNum(v) && (v as JsonPrimitive).longOrNull != null
    fun isBool(v: JsonElement?): Boolean = v is JsonPrimitive && !v.isString && v !is JsonNull && v.booleanOrNull != null
    fun isObj(v: JsonElement?): Boolean = v is JsonObject
    fun isArr(v: JsonElement?): Boolean = v is JsonArray

    /** `str(j, key)`: a string member, else "". */
    fun str(j: JsonElement?, key: String): String = strOf(at(j, key))
    fun strOf(v: JsonElement?): String = if (isStr(v)) (v as JsonPrimitive).content else ""

    /** `integer(j, key, def)`: an integer member, else [def]. */
    fun integer(j: JsonElement?, key: String, def: Int = 0): Int {
        val v = at(j, key)
        return if (isInt(v)) (v as JsonPrimitive).longOrNull!!.toInt() else def
    }

    fun long(j: JsonElement?, key: String, def: Long = 0): Long {
        val v = at(j, key)
        return if (isInt(v)) (v as JsonPrimitive).longOrNull!! else def
    }

    /** `is_number() ? get<int>()` (a float truncates). */
    fun numberAsInt(v: JsonElement?): Int? = if (isNum(v)) (v as JsonPrimitive).doubleOrNull!!.toInt() else null

    fun bool(v: JsonElement?): Boolean? = if (isBool(v)) (v as JsonPrimitive).booleanOrNull else null

    fun arr(v: JsonElement?): List<JsonElement> = (v as? JsonArray) ?: emptyList()
}
