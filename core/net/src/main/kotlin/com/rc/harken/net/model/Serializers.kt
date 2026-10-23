// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Lenient numeric deserializers.
 *
 * NetEase serves the same logical field as a JSON number, a JSON double
 * (e.g. `1.0E7` play counts) or a numeric string (cloud-disk `size`) depending
 * on the endpoint. The iOS port tolerated this by trying several decoders;
 * these serializers centralise the same tolerance for kotlinx.serialization.
 * Any unparsable value falls back to the property default (mirroring Swift's
 * `try? decode(...) ?? default`).
 */
object LenientLongSerializer : KSerializer<Long> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.rc.harken.LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val primitive = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive
        return primitive.longOrNull
            ?: primitive.content.trim().toDoubleOrNull()?.toLong()
            ?: primitive.content.trim().toLongOrNull()
            ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

object LenientIntSerializer : KSerializer<Int> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.rc.harken.LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val primitive = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive
        return primitive.intOrNull
            ?: primitive.content.trim().toDoubleOrNull()?.toInt()
            ?: primitive.content.trim().toIntOrNull()
            ?: 0
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

/** [LenientLongSerializer] variant that accepts explicit JSON nulls. */
object LenientNullableLongSerializer : KSerializer<Long?> {

    private val delegate = LenientLongSerializer.nullable

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Long? = delegate.deserialize(decoder)

    override fun serialize(encoder: Encoder, value: Long?) = delegate.serialize(encoder, value)
}

/** [LenientIntSerializer] variant that accepts explicit JSON nulls. */
object LenientNullableIntSerializer : KSerializer<Int?> {

    private val delegate = LenientIntSerializer.nullable

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Int? = delegate.deserialize(decoder)

    override fun serialize(encoder: Encoder, value: Int?) = delegate.serialize(encoder, value)
}
