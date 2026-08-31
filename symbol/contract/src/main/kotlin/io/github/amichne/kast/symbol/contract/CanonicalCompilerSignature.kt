package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val CANONICAL_SIGNATURE_VERSION = "canonical-signature-v1"
private const val CANONICAL_IDENTITY_PREFIX = "canonical-signature-sha256-v1|"
private const val FUNCTION_SIGNATURE_KIND = "function"
private const val PROPERTY_SIGNATURE_KIND = "property-v2"
private const val TYPE_ALIAS_SIGNATURE_KIND = "type-alias"
private const val CLASS_LIKE_SIGNATURE_KIND = "class-like"
private const val RECEIVER_ABSENT = "receiver-absent"
private const val RECEIVER_PRESENT = "receiver-present"
private const val HEX_RADIX = 16

enum class CanonicalCompilerSignatureFailure {
    INVALID_QUALIFIED_IDENTITY,
    INVALID_RECEIVER_TYPE,
    INVALID_CONTEXT_RECEIVER_TYPE,
    INVALID_VALUE_PARAMETER_TYPE,
    INVALID_TYPE_PARAMETER_COUNT,
    INVALID_RETURN_TYPE,
    INVALID_CANONICAL_ENCODING,
    UNSUPPORTED_CANONICAL_VERSION,
    UNSUPPORTED_SIGNATURE_KIND,
}

/** Canonical, compiler-owned qualified declaration identity. */
@JvmInline
value class CanonicalCompilerQualifiedIdentity internal constructor(
    val value: String,
)

/** Canonical K2 type rendering with insignificant whitespace removed. */
@JvmInline
value class CanonicalCompilerType internal constructor(
    val value: String,
)

/** Proven non-negative count of type parameters in a compiler function signature. */
@JvmInline
value class CanonicalTypeParameterCount internal constructor(
    val value: Int,
)

/** Closed receiver state retained from a native compiler function signature. */
sealed interface CanonicalCompilerReceiver {
    data object Absent : CanonicalCompilerReceiver

    data class Present(
        val type: CanonicalCompilerType,
    ) : CanonicalCompilerReceiver
}

/** Versioned canonical encoding used only at persistence and transport boundaries. */
@JvmInline
value class CanonicalCompilerSignatureEncoding internal constructor(
    val value: String,
)

/**
 * Structured, unambiguous native compiler signature owned by the symbol contract.
 *
 * Each variant retains the compiler facts from which [CompilerSymbolIdentity] is derived. The
 * The canonical framing remains `canonical-signature-v1`; receiver-complete properties use the
 * closed `property-v2` kind so legacy receiver-less property encodings fail closed.
 */
sealed interface CanonicalCompilerSignature {
    val qualifiedIdentity: CanonicalCompilerQualifiedIdentity

    @ConsistentCopyVisibility
    data class Function internal constructor(
        override val qualifiedIdentity: CanonicalCompilerQualifiedIdentity,
        val receiver: CanonicalCompilerReceiver,
        val contextReceivers: List<CanonicalCompilerType>,
        val valueParameters: List<CanonicalCompilerType>,
        val typeParameterCount: CanonicalTypeParameterCount,
    ) : CanonicalCompilerSignature

    @ConsistentCopyVisibility
    data class Property internal constructor(
        override val qualifiedIdentity: CanonicalCompilerQualifiedIdentity,
        val receiver: CanonicalCompilerReceiver,
        val contextReceivers: List<CanonicalCompilerType>,
        val returnType: CanonicalCompilerType,
    ) : CanonicalCompilerSignature

    data class TypeAlias internal constructor(
        override val qualifiedIdentity: CanonicalCompilerQualifiedIdentity,
    ) : CanonicalCompilerSignature

    data class ClassLike internal constructor(
        override val qualifiedIdentity: CanonicalCompilerQualifiedIdentity,
    ) : CanonicalCompilerSignature

    /** Explicit projection for a persistence or transport boundary. */
    fun canonicalEncoding(): CanonicalCompilerSignatureEncoding =
        CanonicalCompilerSignatureEncoding(encodeCanonicalSignature())

    companion object {
        /**
         * Establishes a structured function signature with a non-blank qualified identity,
         * canonical receiver and parameter types, and a non-negative type-parameter count.
         */
        fun function(
            rawQualifiedIdentity: String,
            rawReceiverType: String?,
            rawContextReceiverTypes: List<String>,
            rawValueParameterTypes: List<String>,
            rawTypeParameterCount: Int,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = canonicalQualifiedIdentity(rawQualifiedIdentity)
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY,
                )
            val receiver = when (rawReceiverType) {
                null -> CanonicalCompilerReceiver.Absent
                else -> CanonicalCompilerReceiver.Present(
                    canonicalType(rawReceiverType)
                        ?: return Refinement.Rejected(
                            CanonicalCompilerSignatureFailure.INVALID_RECEIVER_TYPE,
                        ),
                )
            }
            val contextReceiverTypes = rawContextReceiverTypes.canonicalTypes()
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_CONTEXT_RECEIVER_TYPE,
                )
            val valueParameterTypes = rawValueParameterTypes.canonicalTypes()
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_VALUE_PARAMETER_TYPE,
                )
            if (rawTypeParameterCount < 0) {
                return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_TYPE_PARAMETER_COUNT,
                )
            }
            return Refinement.Refined(
                Function(
                    qualifiedIdentity = qualifiedIdentity,
                    receiver = receiver,
                    contextReceivers = contextReceiverTypes,
                    valueParameters = valueParameterTypes,
                    typeParameterCount = CanonicalTypeParameterCount(rawTypeParameterCount),
                ),
            )
        }

        /**
         * Establishes a structured property signature with canonical extension/context receivers
         * and return type. Receiver proof is part of identity so same-name extension properties
         * cannot collide.
         */
        fun property(
            rawQualifiedIdentity: String,
            rawReceiverType: String?,
            rawContextReceiverTypes: List<String>,
            rawReturnType: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = canonicalQualifiedIdentity(rawQualifiedIdentity)
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY,
                )
            val receiver = when (rawReceiverType) {
                null -> CanonicalCompilerReceiver.Absent
                else -> CanonicalCompilerReceiver.Present(
                    canonicalType(rawReceiverType)
                        ?: return Refinement.Rejected(
                            CanonicalCompilerSignatureFailure.INVALID_RECEIVER_TYPE,
                        ),
                )
            }
            val contextReceivers = rawContextReceiverTypes.canonicalTypes()
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_CONTEXT_RECEIVER_TYPE,
                )
            val returnType = canonicalType(rawReturnType)
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_RETURN_TYPE,
                )
            return Refinement.Refined(
                Property(qualifiedIdentity, receiver, contextReceivers, returnType),
            )
        }

        /** Establishes a structured type-alias signature with a non-blank qualified identity. */
        fun typeAlias(
            rawQualifiedIdentity: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
            when (val qualifiedIdentity = canonicalQualifiedIdentity(rawQualifiedIdentity)) {
                null -> Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY,
                )
                else -> Refinement.Refined(TypeAlias(qualifiedIdentity))
            }

        /** Establishes a structured class-like signature with a non-blank qualified identity. */
        fun classLike(
            rawQualifiedIdentity: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
            when (val qualifiedIdentity = canonicalQualifiedIdentity(rawQualifiedIdentity)) {
                null -> Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY,
                )
                else -> Refinement.Refined(ClassLike(qualifiedIdentity))
            }

        /**
         * Restores only exact `canonical-signature-v1` encodings. Unsupported, malformed, or
         * non-canonical encodings fail closed as finite data.
         */
        fun restoreCanonicalEncoding(
            raw: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val fields = decodeCanonicalFields(raw)
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING,
                )
            if (fields.firstOrNull() != CANONICAL_SIGNATURE_VERSION) {
                return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.UNSUPPORTED_CANONICAL_VERSION,
                )
            }
            val cursor = CanonicalFieldCursor(fields.drop(1))
            val restored = when (cursor.next()) {
                FUNCTION_SIGNATURE_KIND -> restoreFunction(cursor)
                PROPERTY_SIGNATURE_KIND -> restoreProperty(cursor)
                TYPE_ALIAS_SIGNATURE_KIND -> restoreTypeAlias(cursor)
                CLASS_LIKE_SIGNATURE_KIND -> restoreClassLike(cursor)
                else -> Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.UNSUPPORTED_SIGNATURE_KIND,
                )
            }
            return restored.requireExactEncoding(raw, cursor)
        }

        private fun restoreFunction(
            cursor: CanonicalFieldCursor,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = cursor.next() ?: return invalidCanonicalEncoding()
            val receiverType = when (cursor.next()) {
                RECEIVER_ABSENT -> null
                RECEIVER_PRESENT -> cursor.next() ?: return invalidCanonicalEncoding()
                else -> return invalidCanonicalEncoding()
            }
            val contextReceivers = cursor.nextValues() ?: return invalidCanonicalEncoding()
            val valueParameters = cursor.nextValues() ?: return invalidCanonicalEncoding()
            val typeParameterCount = cursor.next()?.toIntOrNull()
                ?: return invalidCanonicalEncoding()
            return function(
                rawQualifiedIdentity = qualifiedIdentity,
                rawReceiverType = receiverType,
                rawContextReceiverTypes = contextReceivers,
                rawValueParameterTypes = valueParameters,
                rawTypeParameterCount = typeParameterCount,
            )
        }

        private fun restoreProperty(
            cursor: CanonicalFieldCursor,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = cursor.next() ?: return invalidCanonicalEncoding()
            val receiverType = when (cursor.next()) {
                RECEIVER_ABSENT -> null
                RECEIVER_PRESENT -> cursor.next() ?: return invalidCanonicalEncoding()
                else -> return invalidCanonicalEncoding()
            }
            val contextReceivers = cursor.nextValues() ?: return invalidCanonicalEncoding()
            val returnType = cursor.next() ?: return invalidCanonicalEncoding()
            return property(
                qualifiedIdentity,
                receiverType,
                contextReceivers,
                returnType,
            )
        }

        private fun restoreTypeAlias(
            cursor: CanonicalFieldCursor,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = cursor.next() ?: return invalidCanonicalEncoding()
            return typeAlias(qualifiedIdentity)
        }

        private fun restoreClassLike(
            cursor: CanonicalFieldCursor,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = cursor.next() ?: return invalidCanonicalEncoding()
            return classLike(qualifiedIdentity)
        }

        private fun invalidCanonicalEncoding(): Refinement.Rejected<CanonicalCompilerSignatureFailure> =
            Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    }
}

/** Derives a stable identity without discarding the structured compiler signature. */
fun CompilerSymbolIdentity.Companion.fromCanonicalSignature(
    signature: CanonicalCompilerSignature,
): CompilerSymbolIdentity {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(signature.canonicalEncoding().value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(HEX_RADIX).padStart(2, '0')
        }
    return when (val identity = CompilerSymbolIdentity.parse(CANONICAL_IDENTITY_PREFIX + digest)) {
        is Refinement.Refined -> identity.value
        is Refinement.Rejected -> error("SHA-256 projection is a canonical compiler identity")
    }
}

private fun CanonicalCompilerSignature.encodeCanonicalSignature(): String = buildString {
    appendCanonicalField(CANONICAL_SIGNATURE_VERSION)
    when (val signature = this@encodeCanonicalSignature) {
        is CanonicalCompilerSignature.Function -> {
            appendCanonicalField(FUNCTION_SIGNATURE_KIND)
            appendCanonicalField(signature.qualifiedIdentity.value)
            when (val receiver = signature.receiver) {
                CanonicalCompilerReceiver.Absent -> appendCanonicalField(RECEIVER_ABSENT)
                is CanonicalCompilerReceiver.Present -> {
                    appendCanonicalField(RECEIVER_PRESENT)
                    appendCanonicalField(receiver.type.value)
                }
            }
            appendCanonicalFields(signature.contextReceivers.map(CanonicalCompilerType::value))
            appendCanonicalFields(signature.valueParameters.map(CanonicalCompilerType::value))
            appendCanonicalField(signature.typeParameterCount.value.toString())
        }
        is CanonicalCompilerSignature.Property -> {
            appendCanonicalField(PROPERTY_SIGNATURE_KIND)
            appendCanonicalField(signature.qualifiedIdentity.value)
            when (val receiver = signature.receiver) {
                CanonicalCompilerReceiver.Absent -> appendCanonicalField(RECEIVER_ABSENT)
                is CanonicalCompilerReceiver.Present -> {
                    appendCanonicalField(RECEIVER_PRESENT)
                    appendCanonicalField(receiver.type.value)
                }
            }
            appendCanonicalFields(signature.contextReceivers.map(CanonicalCompilerType::value))
            appendCanonicalField(signature.returnType.value)
        }
        is CanonicalCompilerSignature.TypeAlias -> {
            appendCanonicalField(TYPE_ALIAS_SIGNATURE_KIND)
            appendCanonicalField(signature.qualifiedIdentity.value)
        }
        is CanonicalCompilerSignature.ClassLike -> {
            appendCanonicalField(CLASS_LIKE_SIGNATURE_KIND)
            appendCanonicalField(signature.qualifiedIdentity.value)
        }
    }
}

private fun Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>.requireExactEncoding(
    raw: String,
    cursor: CanonicalFieldCursor,
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> = when (this) {
    is Refinement.Rejected -> this
    is Refinement.Refined -> if (
        cursor.isExhausted && value.canonicalEncoding().value == raw
    ) {
        this
    } else {
        Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
    }
}

private class CanonicalFieldCursor(
    private val fields: List<String>,
) {
    private var index: Int = 0

    val isExhausted: Boolean
        get() = index == fields.size

    fun next(): String? = fields.getOrNull(index++)

    fun nextValues(): List<String>? {
        val count = next()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        if (count > fields.size - index) return null
        return List(count) { next() ?: return null }
    }
}

private fun decodeCanonicalFields(raw: String): List<String>? {
    val bytes = raw.toByteArray(StandardCharsets.UTF_8)
    val fields = mutableListOf<String>()
    var offset = 0
    while (offset < bytes.size) {
        val lengthStart = offset
        while (offset < bytes.size && bytes[offset] != ':'.code.toByte()) {
            if (bytes[offset] !in '0'.code.toByte()..'9'.code.toByte()) return null
            offset += 1
        }
        if (offset == lengthStart || offset >= bytes.size) return null
        val length = String(bytes, lengthStart, offset - lengthStart, StandardCharsets.US_ASCII)
            .toIntOrNull()
            ?: return null
        offset += 1
        if (length > bytes.size - offset) return null
        val fieldBytes = bytes.copyOfRange(offset, offset + length)
        val field = String(fieldBytes, StandardCharsets.UTF_8)
        if (!field.toByteArray(StandardCharsets.UTF_8).contentEquals(fieldBytes)) return null
        fields += field
        offset += length
    }
    return fields
}

private fun canonicalQualifiedIdentity(raw: String): CanonicalCompilerQualifiedIdentity? =
    raw.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        ?.let(::CanonicalCompilerQualifiedIdentity)

private fun canonicalType(raw: String): CanonicalCompilerType? {
    val canonical = raw.filterNot(Char::isWhitespace)
    return canonical.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        ?.let(::CanonicalCompilerType)
}

private fun List<String>.canonicalTypes(): List<CanonicalCompilerType>? {
    val canonical = map { raw -> canonicalType(raw) ?: return null }
    return java.util.List.copyOf(canonical)
}

private fun StringBuilder.appendCanonicalFields(values: List<String>) {
    appendCanonicalField(values.size.toString())
    values.forEach(::appendCanonicalField)
}

private fun StringBuilder.appendCanonicalField(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}
