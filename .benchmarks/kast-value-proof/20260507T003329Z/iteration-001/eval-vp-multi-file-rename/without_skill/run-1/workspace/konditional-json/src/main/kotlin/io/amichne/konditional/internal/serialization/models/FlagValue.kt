@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.internal.serialization.models

import com.squareup.moshi.JsonClass
import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.core.ValueType
import io.amichne.konditional.core.types.Konstrained
import io.amichne.konditional.serialization.extractSchema
import io.amichne.konditional.serialization.SchemaValueCodec
import io.amichne.konditional.serialization.internal.toJsonValue
import io.amichne.konditional.serialization.internal.toPrimitiveMap
import io.amichne.kontracts.dsl.jsonObject
import io.amichne.kontracts.schema.ObjectSchema
import io.amichne.kontracts.value.JsonArray
import io.amichne.kontracts.value.JsonBoolean
import io.amichne.kontracts.value.JsonNumber
import io.amichne.kontracts.value.JsonObject
import io.amichne.kontracts.value.JsonString
import io.amichne.kontracts.value.JsonValue
import kotlin.reflect.KClass

/**
 * Type-safe representation of flag values that replaces the type-erased SerializableValue.
 *
 * This sealed class follows parse-don't-validate principles:
 * - No type erasure via `Any`
 * - Compile-time type safety
 * - Illegal states are unrepresentable (can't have INT type with Boolean value)
 *
 * Each subclass encodes both the value AND its type in a type-safe manner.
 *
 * Supports primitive types, enum types, and user-defined [Konstrained] types:
 * - [BooleanValue], [StringValue], [IntValue], [DoubleValue] — JSON primitives
 * - [EnumValue] — enum constants stored by name with class FQCN
 * - [DataClassValue] — object-backed [Konstrained] (data classes, etc.)
 * - [KonstrainedPrimitive] — primitive/array-backed [Konstrained] (value classes, etc.)
 */
@KonditionalInternalApi
internal sealed class FlagValue<out T : Any> {
    abstract val value: T

    /**
     * Returns the ValueType corresponding to this FlagValue subclass.
     */
    abstract fun toValueType(): ValueType

    // ========== Primitive Types ==========

    @JsonClass(generateAdapter = true)
    data class BooleanValue(
        override val value: Boolean,
    ) : FlagValue<Boolean>() {
        override fun toValueType() = ValueType.BOOLEAN
    }

    @JsonClass(generateAdapter = true)
    data class StringValue(
        override val value: String,
    ) : FlagValue<String>() {
        override fun toValueType() = ValueType.STRING
    }

    @JsonClass(generateAdapter = true)
    data class IntValue(
        override val value: Int,
    ) : FlagValue<Int>() {
        override fun toValueType() = ValueType.INT
    }

    @JsonClass(generateAdapter = true)
    data class DoubleValue(
        override val value: Double,
    ) : FlagValue<Double>() {
        override fun toValueType() = ValueType.DOUBLE
    }

    /**
     * Represents an enum value.
     * Stores the enum as a string (its name) along with the fully qualified enum class name
     * to enable proper deserialization.
     */
    @JsonClass(generateAdapter = true)
    data class EnumValue(
        override val value: String,
        val enumClassName: String,
    ) : FlagValue<String>() {
        override fun toValueType() = ValueType.ENUM
    }

    /**
     * Represents an object-backed [Konstrained] value (typically a data class with named fields).
     *
     * Stores the custom type as a map of field name to primitive value along with the fully
     * qualified class name to enable proper deserialization.
     *
     * The fields map contains the primitive representation of the custom type,
     * which can be serialized to JSON and later reconstructed.
     */
    @JsonClass(generateAdapter = true)
    data class DataClassValue(
        override val value: Map<String, Any?>,
        val dataClassName: String,
    ) : FlagValue<Map<String, Any?>>() {
        override fun toValueType() = ValueType.DATA_CLASS
    }

    /**
     * Represents a primitive/array-backed [Konstrained] value (typically a `@JvmInline value class`).
     *
     * Stores the raw primitive or list value ([String], [Boolean], [Int], [Double], `List<*>`)
     * along with the fully qualified class name of the [Konstrained] implementation to enable
     * reconstruction without an `expectedSample`.
     *
     * Wire format discriminator: `"KONSTRAINED_PRIMITIVE"`.
     */
    @JsonClass(generateAdapter = true)
    data class KonstrainedPrimitive(
        override val value: Any,
        val konstrainedClassName: String,
    ) : FlagValue<Any>() {
        override fun toValueType() = ValueType.DATA_CLASS
    }

    fun validate(schema: ObjectSchema) {
        if (this is DataClassValue) {
            validateDataClassFields(value, schema)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <V : Any> extractValue(
        expectedSample: Any? = null,
        schema: ObjectSchema? = null,
    ): V =
        when (this) {
            is EnumValue -> decodeEnum(value, expectedSample) as V
            is DataClassValue -> {
                schema?.let { validateDataClassFields(value, it) }
                decodeDataClass(
                    fields = value,
                    expectedSample = expectedSample,
                    schema = schema,
                ) as V
            }
            is KonstrainedPrimitive -> decodeKonstrainedPrimitive() as V
            else -> value as V
        }

    @Suppress("UNCHECKED_CAST")
    private fun KonstrainedPrimitive.decodeKonstrainedPrimitive(): Any {
        val kClass =
            runCatching { Class.forName(konstrainedClassName).kotlin as KClass<Any> }
                .getOrElse {
                    throw IllegalArgumentException(
                        "Cannot load class '$konstrainedClassName' for KonstrainedPrimitive decoding: ${it.message}",
                    )
                }
        return SchemaValueCodec.decodeKonstrainedPrimitive(kClass, value)
            .getOrElse { error ->
                throw IllegalArgumentException(
                    "Failed to decode KonstrainedPrimitive '$konstrainedClassName': ${error.message}",
                )
            }
    }

    companion object {
        /**
         * Creates a [FlagValue] from an untyped value by inferring its type.
         *
         * For [Konstrained] values:
         * - [Konstrained.Object] → [DataClassValue]
         * - Primitive, array, and adapted shapes → [KonstrainedPrimitive]
         */
        fun from(value: Any): FlagValue<*> =
            when (value) {
                is Boolean -> BooleanValue(value)
                is String -> StringValue(value)
                is Int -> IntValue(value)
                is Double -> DoubleValue(value)
                is Enum<*> ->
                    EnumValue(
                        value = value.name,
                        enumClassName = value.javaClass.name,
                    )
                is Konstrained -> fromKonstrained(value)
                else -> throw IllegalArgumentException(
                        "Unsupported value type: ${value::class.simpleName}. " +
                            "Supported types: Boolean, String, Int, Double, Enum, Konstrained.",
                    )
            }

        private fun fromKonstrained(value: Konstrained): FlagValue<*> =
            when {
                value is Konstrained.Object ->
                    DataClassValue(
                        value = value.toPrimitiveMap(),
                        dataClassName = value::class.java.name,
                    )
                else -> {
                    KonstrainedPrimitive(
                        value = SchemaValueCodec.encodeKonstrained(value).toPrimitiveRaw(),
                        konstrainedClassName = value::class.java.name,
                    )
                }
            }
    }
}

/**
 * Converts a [JsonValue] to its raw primitive/list representation
 * for storage in [FlagValue.KonstrainedPrimitive.value].
 */
private fun JsonValue.toPrimitiveRaw(): Any =
    when (this) {
        is JsonBoolean -> value
        is JsonString -> value
        is JsonNumber -> toInt().let { i ->
            // Preserve Int vs Double to match round-trip expectations
            if (toDouble() == i.toDouble()) i else toDouble()
        }
        is JsonArray -> elements.map { it.toPrimitiveRaw() }
        else ->
            error(
                "KonstrainedPrimitive does not support encoding to ${this::class.simpleName}. " +
                    "Supported output: Boolean, String, Int, Double, Array.",
            )
    }

private fun validateDataClassFields(
    fields: Map<String, Any?>,
    schema: ObjectSchema,
) {
    toJsonObject(fields, schema)
}

@Suppress("UseRequire")
private fun decodeEnum(
    enumConstantName: String,
    expectedSample: Any?,
): Enum<*> =
    (expectedSample as? Enum<*>)?.let { enumSample ->
        val enumClass = enumSample::class.java
        runCatching { java.lang.Enum.valueOf(enumClass, enumConstantName) }
            .getOrElse {
                throw IllegalArgumentException(
                    "Enum value '$enumConstantName' is invalid for ${enumClass.name}",
                )
            }
    } ?: run {
        throw IllegalArgumentException(
            "Missing trusted enum metadata while decoding '$enumConstantName'",
        )
    }

@Suppress("UseRequire")
private fun decodeDataClass(
    fields: Map<String, Any?>,
    expectedSample: Any?,
    schema: ObjectSchema?,
): Any =
    (expectedSample as? Konstrained.Object)?.let { expected ->
        val expectedClass = expected::class
        val expectedSchema =
            schema ?: extractSchema(expected::class)
                ?: error("Cannot extract ObjectSchema from ${expectedClass.qualifiedName}")
        val jsonObject = toJsonObject(fields, expectedSchema)
        val result = SchemaValueCodec.decode(expectedClass, jsonObject)
        if (result.isSuccess) {
            result.getOrThrow()
        } else {
            throw IllegalArgumentException(
                "Failed to decode '${expectedClass.qualifiedName}': ${result.exceptionOrNull()?.message}",
            )
        }
    } ?: run {
        throw IllegalArgumentException(
            "Missing trusted data-class metadata while decoding payload",
        )
    }

private fun toJsonObject(
    fields: Map<String, Any?>,
    schema: ObjectSchema? = null,
): JsonObject =
    jsonObject {
        this.schema = schema
        fields(fields.mapValues { (_, value) -> value.toJsonValue() })
    }
