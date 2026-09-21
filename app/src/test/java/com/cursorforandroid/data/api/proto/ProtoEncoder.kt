package com.cursorforandroid.data.api.proto

import com.cursorforandroid.data.api.proto.ProtoWire.Field
import com.cursorforandroid.data.api.proto.ProtoWire.Kind
import com.cursorforandroid.data.api.proto.ProtoWire.Schema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The inverse of [ProtoWire] for the fixtures: a proto3-JSON object written as protobuf binary against a [Schema],
 * so the fault server can hand out blobs the way the account does. A key the schema does not name is left out
 * (the fixtures' legacy shapes carry keys the `agent.v1` messages never had); [unknown] writes extra fields the
 * schema does not know, for the drift tests.
 */
object ProtoEncoder {

    fun encode(json: JsonObject, schema: Schema, unknown: List<Triple<Int, Kind, JsonElement>> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        val byName = schema.fields.values.associateBy { it.name }
        for ((key, value) in json) {
            val field = byName[key] ?: continue
            if (value is JsonNull) continue
            if (field.repeated) {
                (value as? JsonArray)?.forEach { writeField(out, field, it) }
            } else if (field.kind == Kind.MAP) {
                val entry = field.message ?: continue
                (value as? JsonObject)?.forEach { (k, v) ->
                    val bytes = encode(JsonObject(mapOf("key" to JsonPrimitive(k), "value" to v)), entry)
                    tag(out, field.number, 2); varint(out, bytes.size.toLong()); out.write(bytes)
                }
            } else {
                writeField(out, field, value)
            }
        }
        for ((number, kind, value) in unknown) writeField(out, Field(number, "unknown$number", kind), value)
        return out.toByteArray()
    }

    private fun writeField(out: ByteArrayOutputStream, field: Field, value: JsonElement) {
        when (field.kind) {
            Kind.STRING -> { val bytes = ((value as JsonPrimitive).content).toByteArray(Charsets.UTF_8); tag(out, field.number, 2); varint(out, bytes.size.toLong()); out.write(bytes) }
            Kind.BYTES -> { val bytes = Base64.getDecoder().decode((value as JsonPrimitive).content); tag(out, field.number, 2); varint(out, bytes.size.toLong()); out.write(bytes) }
            Kind.BOOL -> { tag(out, field.number, 0); varint(out, if ((value as JsonPrimitive).booleanOrNull == true) 1L else 0L) }
            Kind.INT32, Kind.UINT32, Kind.INT64, Kind.UINT64, Kind.ENUM -> { tag(out, field.number, 0); varint(out, (value as JsonPrimitive).longOrNull ?: value.content.toLong()) }
            Kind.DOUBLE -> { tag(out, field.number, 1); val bits = java.lang.Double.doubleToLongBits((value as JsonPrimitive).doubleOrNull ?: 0.0); for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt()) }
            Kind.FLOAT -> { tag(out, field.number, 5); val bits = java.lang.Float.floatToIntBits(((value as JsonPrimitive).doubleOrNull ?: 0.0).toFloat()); for (i in 0 until 4) out.write(((bits ushr (8 * i)) and 0xFF)) }
            Kind.MESSAGE -> { val bytes = encode(value as JsonObject, field.message!!); tag(out, field.number, 2); varint(out, bytes.size.toLong()); out.write(bytes) }
            Kind.MAP -> error("maps are written by encode")
        }
    }

    private fun tag(out: ByteArrayOutputStream, number: Int, wireType: Int) = varint(out, ((number.toLong() shl 3) or wireType.toLong()))

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) { out.write(v.toInt()); return }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}
