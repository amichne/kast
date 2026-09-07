@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
    kotlinx.serialization.SealedSerializationApi::class,
)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** JSON-schema evidence retained by the serializer that enforces the same string constraint. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class ProtocolStringConstraint(
    val minimumLength: Int = 0,
    val maximumLength: Int = Int.MAX_VALUE,
    val pattern: String = "",
)

/** JSON-schema evidence retained by the serializer that enforces the same integer constraint. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class ProtocolIntegerConstraint(
    val minimum: Long = Long.MIN_VALUE,
    val maximum: Long = Long.MAX_VALUE,
)

/** JSON-schema evidence retained by a bounded collection serializer. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class ProtocolCollectionConstraint(
    val minimumItems: Int = 0,
    val maximumItems: Int = Int.MAX_VALUE,
    val uniqueItems: Boolean = false,
)

/** Restricts enum values in a property or in the elements of a collection property. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ProtocolAllowedValues(vararg val values: String)

/** Requires every item to use the same closed serializer variant. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ProtocolHomogeneousCollection

internal fun constrainedStringDescriptor(
    serialName: String,
    minimumLength: Int,
    maximumLength: Int,
    pattern: String = "",
): SerialDescriptor = annotatedDescriptor(
    PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING),
    ProtocolStringConstraint(minimumLength, maximumLength, pattern),
)

internal fun constrainedIntegerDescriptor(
    serialName: String,
    primitiveKind: PrimitiveKind,
    minimum: Long,
    maximum: Long = Long.MAX_VALUE,
): SerialDescriptor = annotatedDescriptor(
    PrimitiveSerialDescriptor(serialName, primitiveKind),
    ProtocolIntegerConstraint(minimum, maximum),
)

internal fun annotatedDescriptor(
    descriptor: SerialDescriptor,
    vararg annotations: Annotation,
): SerialDescriptor = object : SerialDescriptor by descriptor {
    override val annotations: List<Annotation> = descriptor.annotations + annotations
}

internal abstract class RefiningStringSerializer<Value>(
    serialName: String,
    minimumLength: Int,
    maximumLength: Int,
    pattern: String = "",
) : KSerializer<Value> {
    final override val descriptor: SerialDescriptor = constrainedStringDescriptor(
        serialName,
        minimumLength,
        maximumLength,
        pattern,
    )

    protected abstract fun raw(value: Value): String

    protected abstract fun refine(raw: String): Refinement<Value, *>

    final override fun serialize(encoder: Encoder, value: Value) {
        encoder.encodeString(raw(value))
    }

    final override fun deserialize(decoder: Decoder): Value = when (
        val refinement = refine(decoder.decodeString())
    ) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> throw SerializationException(
            "${descriptor.serialName} rejected ${refinement.failure}",
        )
    }
}

internal abstract class RefiningIntSerializer<Value>(
    serialName: String,
    minimum: Long,
    maximum: Long = Long.MAX_VALUE,
) : KSerializer<Value> {
    final override val descriptor: SerialDescriptor = constrainedIntegerDescriptor(
        serialName,
        PrimitiveKind.INT,
        minimum,
        maximum,
    )

    protected abstract fun raw(value: Value): Int

    protected abstract fun refine(raw: Int): Refinement<Value, *>

    final override fun serialize(encoder: Encoder, value: Value) {
        encoder.encodeInt(raw(value))
    }

    final override fun deserialize(decoder: Decoder): Value = when (
        val refinement = refine(decoder.decodeInt())
    ) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> throw SerializationException(
            "${descriptor.serialName} rejected ${refinement.failure}",
        )
    }
}

internal abstract class RefiningLongSerializer<Value>(
    serialName: String,
    minimum: Long,
    maximum: Long = Long.MAX_VALUE,
) : KSerializer<Value> {
    final override val descriptor: SerialDescriptor = constrainedIntegerDescriptor(
        serialName,
        PrimitiveKind.LONG,
        minimum,
        maximum,
    )

    protected abstract fun raw(value: Value): Long

    protected abstract fun refine(raw: Long): Refinement<Value, *>

    final override fun serialize(encoder: Encoder, value: Value) {
        encoder.encodeLong(raw(value))
    }

    final override fun deserialize(decoder: Decoder): Value = when (
        val refinement = refine(decoder.decodeLong())
    ) {
        is Refinement.Refined -> refinement.value
        is Refinement.Rejected -> throw SerializationException(
            "${descriptor.serialName} rejected ${refinement.failure}",
        )
    }
}
