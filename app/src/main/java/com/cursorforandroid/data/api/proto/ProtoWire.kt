package com.cursorforandroid.data.api.proto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/**
 * The protobuf binary wire format, read against a hand-written [Schema] into the proto3 JSON mapping — lowerCamelCase
 * field names, a oneof's members as plain fields, `int64`/`uint64` as decimal strings, `bytes` as base64, enums by
 * number — which is the shape the account's Connect-JSON answers and the documented stream's SDK-shape events
 * already arrive in, so everything downstream reads a decoded blob as it reads those.
 *
 * The account's record of a chat is blob-backed (`GetBlobForAgentKV`: `agent.v1.ConversationTurnStructure`,
 * `agent.v1.ConversationStep`, `agent.v1.UserMessage`, serialized protobuf), and Cursor's own client decodes them
 * with generated classes; this app has no protobuf runtime, so the few messages it reads are described in
 * [AgentSchemas] from the descriptors of the desktop bundle (Cursor 3.21.16). A field the schema does not name is
 * kept as `_unknownFields` — its number and wire type — so the transcript diagnostics say what the server has
 * started sending that this build does not read, rather than the field disappearing without a word.
 */
object ProtoWire {

    /** One message type: its fields by number. [name] is the fully qualified proto name, for the diagnostics. */
    class Schema(val name: String, fields: List<Field>) {
        val fields: Map<Int, Field> = fields.associateBy { it.number }
    }

    enum class Kind { STRING, BYTES, BOOL, INT32, UINT32, INT64, UINT64, DOUBLE, FLOAT, ENUM, MESSAGE, MAP }

    /**
     * One field: its number, its JSON name (lowerCamelCase), its kind, and for a message field the message's schema
     * — given lazily, so schemas may refer to each other and to themselves. A [MAP] field's [message] is its entry
     * (field 1 the key, field 2 the value); the JSON is an object keyed by the entries' keys.
     */
    class Field(
        val number: Int,
        val name: String,
        val kind: Kind,
        val repeated: Boolean = false,
        private val schema: (() -> Schema)? = null,
    ) {
        val message: Schema? get() = schema?.invoke()
    }

    /** A field the schema does not know, as the diagnostics name it: `no:wireType`, with the length of a length-delimited one. */
    const val UNKNOWN_FIELDS = "_unknownFields"

    class MalformedException(message: String) : IllegalArgumentException(message)

    /** [bytes] read as a [schema] message. Throws [MalformedException] when the bytes are not a protobuf message at all. */
    fun decode(bytes: ByteArray, schema: Schema): JsonObject = decodeMessage(bytes, 0, bytes.size, schema, depth = 0)

    private fun decodeMessage(bytes: ByteArray, start: Int, end: Int, schema: Schema, depth: Int): JsonObject {
        if (depth > MAX_DEPTH) throw MalformedException("${schema.name}: nested deeper than $MAX_DEPTH")
        val out = LinkedHashMap<String, JsonElement>()
        val repeated = LinkedHashMap<String, ArrayList<JsonElement>>()
        val maps = LinkedHashMap<String, LinkedHashMap<String, JsonElement>>()
        var unknown: ArrayList<String>? = null
        var pos = start
        while (pos < end) {
            val (tag, afterTag) = varint(bytes, pos, end)
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()
            if (number <= 0) throw MalformedException("${schema.name}: field number $number at $pos")
            pos = afterTag
            val field = schema.fields[number]
            when (wireType) {
                WIRE_VARINT -> {
                    val (value, next) = varint(bytes, pos, end)
                    pos = next
                    if (field == null) { (unknown ?: ArrayList<String>().also { unknown = it }) += "$number:varint"; continue }
                    put(out, repeated, field, scalar(field, value))
                }
                WIRE_FIXED64 -> {
                    if (pos + 8 > end) throw MalformedException("${schema.name}: fixed64 past the end")
                    var value = 0L
                    for (i in 7 downTo 0) value = (value shl 8) or (bytes[pos + i].toLong() and 0xFF)
                    pos += 8
                    if (field == null) { (unknown ?: ArrayList<String>().also { unknown = it }) += "$number:fixed64"; continue }
                    put(out, repeated, field, if (field.kind == Kind.DOUBLE) JsonPrimitive(java.lang.Double.longBitsToDouble(value)) else JsonPrimitive(value.toString()))
                }
                WIRE_LENGTH -> {
                    val (length, next) = varint(bytes, pos, end)
                    val from = next
                    val to = from + length.toInt()
                    if (length < 0 || to > end) throw MalformedException("${schema.name}: length $length past the end at $pos")
                    pos = to
                    if (field == null) { (unknown ?: ArrayList<String>().also { unknown = it }) += "$number:len=$length"; continue }
                    when (field.kind) {
                        Kind.STRING -> put(out, repeated, field, JsonPrimitive(String(bytes, from, to - from, Charsets.UTF_8)))
                        Kind.BYTES -> put(out, repeated, field, JsonPrimitive(Base64.getEncoder().encodeToString(bytes.copyOfRange(from, to))))
                        Kind.MESSAGE -> {
                            val nested = field.message ?: throw MalformedException("${schema.name}.${field.name}: a message field without a schema")
                            put(out, repeated, field, decodeMessage(bytes, from, to, nested, depth + 1))
                        }
                        Kind.MAP -> {
                            val entry = field.message ?: throw MalformedException("${schema.name}.${field.name}: a map field without an entry schema")
                            val decoded = decodeMessage(bytes, from, to, entry, depth + 1)
                            val key = (decoded["key"] as? JsonPrimitive)?.content ?: ""
                            maps.getOrPut(field.name) { LinkedHashMap() }[key] = decoded["value"] ?: JsonNull
                        }
                        // A packed repeated scalar: the values one after another.
                        Kind.BOOL, Kind.INT32, Kind.UINT32, Kind.INT64, Kind.UINT64, Kind.ENUM -> {
                            var p = from
                            while (p < to) {
                                val (value, n) = varint(bytes, p, to)
                                p = n
                                put(out, repeated, field, scalar(field, value))
                            }
                        }
                        Kind.DOUBLE -> {
                            var p = from
                            while (p + 8 <= to) {
                                var value = 0L
                                for (i in 7 downTo 0) value = (value shl 8) or (bytes[p + i].toLong() and 0xFF)
                                put(out, repeated, field, JsonPrimitive(java.lang.Double.longBitsToDouble(value)))
                                p += 8
                            }
                        }
                        Kind.FLOAT -> {
                            var p = from
                            while (p + 4 <= to) {
                                put(out, repeated, field, JsonPrimitive(java.lang.Float.intBitsToFloat(fixed32(bytes, p)).toDouble()))
                                p += 4
                            }
                        }
                    }
                }
                WIRE_FIXED32 -> {
                    if (pos + 4 > end) throw MalformedException("${schema.name}: fixed32 past the end")
                    val value = fixed32(bytes, pos)
                    pos += 4
                    if (field == null) { (unknown ?: ArrayList<String>().also { unknown = it }) += "$number:fixed32"; continue }
                    put(out, repeated, field, if (field.kind == Kind.FLOAT) JsonPrimitive(java.lang.Float.intBitsToFloat(value).toDouble()) else JsonPrimitive(value.toLong() and 0xFFFFFFFFL))
                }
                // Groups (3, 4) are not in proto3; anything else is not protobuf.
                else -> throw MalformedException("${schema.name}: wire type $wireType at $pos")
            }
        }
        repeated.forEach { (name, values) -> out[name] = JsonArray(values) }
        maps.forEach { (name, entries) -> out[name] = JsonObject(entries) }
        unknown?.let { out[UNKNOWN_FIELDS] = JsonPrimitive(it.joinToString(",")) }
        return JsonObject(out)
    }

    private fun put(out: MutableMap<String, JsonElement>, repeated: MutableMap<String, ArrayList<JsonElement>>, field: Field, value: JsonElement) {
        if (field.repeated) repeated.getOrPut(field.name) { ArrayList() } += value else out[field.name] = value
    }

    /** A varint field as its kind reads it: `int64`/`uint64` as decimal strings, as proto3 JSON writes them. */
    private fun scalar(field: Field, value: Long): JsonElement = when (field.kind) {
        Kind.BOOL -> JsonPrimitive(value != 0L)
        Kind.INT32, Kind.ENUM -> JsonPrimitive(value.toInt())
        Kind.UINT32 -> JsonPrimitive(value and 0xFFFFFFFFL)
        Kind.INT64 -> JsonPrimitive(value.toString())
        Kind.UINT64 -> JsonPrimitive(java.lang.Long.toUnsignedString(value))
        // A varint for a field the schema calls something else: kept as its number, so nothing is lost.
        else -> JsonPrimitive(value.toString())
    }

    private fun fixed32(bytes: ByteArray, pos: Int): Int {
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[pos + i].toInt() and 0xFF)
        return value
    }

    /** The varint at [pos], and the position after it. */
    private fun varint(bytes: ByteArray, pos: Int, end: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var p = pos
        while (p < end && shift < 64) {
            val b = bytes[p].toLong()
            result = result or ((b and 0x7F) shl shift)
            p++
            if (b and 0x80 == 0L) return result to p
            shift += 7
        }
        throw MalformedException("varint at $pos runs past the end")
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH = 2
    private const val WIRE_FIXED32 = 5
    private const val MAX_DEPTH = 24
}
