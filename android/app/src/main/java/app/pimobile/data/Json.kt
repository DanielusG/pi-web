package app.pimobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Lenient accessors for JSON trees coming from pi-web.

fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

fun JsonObject.str(key: String): String? = primitive(key)?.content
fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull
fun JsonObject.long(key: String): Long? = primitive(key)?.longOrNull
fun JsonObject.double(key: String): Double? = primitive(key)?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

val JsonObject.type: String? get() = str("type")

fun JsonArray?.strings(): List<String> =
    this?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }.orEmpty()
