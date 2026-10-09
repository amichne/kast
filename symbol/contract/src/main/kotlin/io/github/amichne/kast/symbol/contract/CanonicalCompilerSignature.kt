package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val CANONICAL_SIGNATURE_VERSION = "canonical-signature-v1"
internal const val CANONICAL_IDENTITY_PREFIX = "canonical-signature-sha256-v1|"
internal const val FUNCTION_SIGNATURE_KIND = "function"
internal const val PROPERTY_SIGNATURE_KIND = "property-v2"
internal const val TYPE_ALIAS_SIGNATURE_KIND = "type-alias"
internal const val CLASS_LIKE_SIGNATURE_KIND = "class-like"
internal const val RECEIVER_ABSENT = "receiver-absent"
internal const val RECEIVER_PRESENT = "receiver-present"
private const val CANONICAL_SIGNATURE_HEX_RADIX = 16

enum class CanonicalCompilerSignatureFailure {
    INVALID_QUALIFIED_IDENTITY,
    INVALID_RECEIVER_TYPE,
    INVALID_CONTEXT_RECEIVER_TYPE,
    INVALID_VALUE_PARAMETER_TYPE,
    INVALID_TYPE_PARAMETER_COUNT,
    INVALID_RETURN_TYPE,
    INVALID_SUPERTYPE,
    INVALID_CANONICAL_ENCODING,
    UNSUPPORTED_CANONICAL_VERSION,
    UNSUPPORTED_SIGNATURE_KIND,
    LOCAL_ADDRESS_KIND_MISMATCH,
}

/** Canonical, compiler-owned qualified declaration identity. */
@JvmInline value class CanonicalCompilerQualifiedIdentity internal constructor(val value: String)

/** Canonical K2 type rendering with insignificant whitespace removed. */
@JvmInline value class CanonicalCompilerType internal constructor(val value: String)

/** Proven non-negative count of type parameters in a compiler function signature. */
@JvmInline value class CanonicalTypeParameterCount internal constructor(val value: Int)

/** Closed receiver state retained from a native compiler function signature. */
sealed interface CanonicalCompilerReceiver {
    data object Absent : CanonicalCompilerReceiver

    data class Present(val type: CanonicalCompilerType) : CanonicalCompilerReceiver
}

/** Versioned canonical encoding used only at persistence and transport boundaries. */
@JvmInline value class CanonicalCompilerSignatureEncoding internal constructor(val value: String)

/**
 * Structured, unambiguous native compiler signature owned by the symbol contract.
 *
 * Each variant retains the compiler facts from which [CompilerSymbolIdentity] is derived. The The canonical framing
 * remains `canonical-signature-v1`; receiver-complete properties use the closed `property-v2` kind so legacy
 * receiver-less property encodings fail closed.
 */
sealed interface CanonicalCompilerSignature {
    val declarationAddress: CompilerDeclarationAddress

    @ConsistentCopyVisibility
    data class Function
    internal constructor(
        val qualifiedIdentity: CanonicalCompilerQualifiedIdentity,
        override val receiver: CanonicalCompilerReceiver,
        override val contextReceivers: List<CanonicalCompilerType>,
        override val valueParameters: List<CanonicalCompilerType>,
        override val typeParameterCount: CanonicalTypeParameterCount,
    ) : CanonicalCompilerCallableSignature {
        override val declarationAddress: CompilerDeclarationAddress
            get() = CompilerDeclarationAddress.Qualified(qualifiedIdentity)
    }

    @ConsistentCopyVisibility
    data class Property
    internal constructor(
        val qualifiedIdentity: CanonicalCompilerQualifiedIdentity,
        val receiver: CanonicalCompilerReceiver,
        val contextReceivers: List<CanonicalCompilerType>,
        val returnType: CanonicalCompilerType,
    ) : CanonicalCompilerSignature {
        override val declarationAddress: CompilerDeclarationAddress
            get() = CompilerDeclarationAddress.Qualified(qualifiedIdentity)
    }

    data class TypeAlias internal constructor(val qualifiedIdentity: CanonicalCompilerQualifiedIdentity) :
        CanonicalCompilerSignature {
        override val declarationAddress: CompilerDeclarationAddress
            get() = CompilerDeclarationAddress.Qualified(qualifiedIdentity)
    }

    data class ClassLike internal constructor(val qualifiedIdentity: CanonicalCompilerQualifiedIdentity) :
        CanonicalCompilerSignature {
        override val declarationAddress: CompilerDeclarationAddress
            get() = CompilerDeclarationAddress.Qualified(qualifiedIdentity)
    }

    @ConsistentCopyVisibility
    data class LocalFunction
    internal constructor(
        val address: LocalDeclarationAddress,
        override val receiver: CanonicalCompilerReceiver,
        override val contextReceivers: List<CanonicalCompilerType>,
        override val valueParameters: List<CanonicalCompilerType>,
        override val typeParameterCount: CanonicalTypeParameterCount,
        val returnType: CanonicalCompilerType,
    ) : CanonicalCompilerCallableSignature {
        override val declarationAddress: CompilerDeclarationAddress
            get() = CompilerDeclarationAddress.Local(address)
    }

    @ConsistentCopyVisibility
    data class LocalProperty
    internal constructor(
        val address: LocalDeclarationAddress,
        val returnType: CanonicalCompilerType,
        val mutability: LocalPropertyMutability,
    ) : CanonicalCompilerSignature {
        override val declarationAddress: CompilerDeclarationAddress
            get() = CompilerDeclarationAddress.Local(address)
    }

    @ConsistentCopyVisibility
    data class AnonymousObject
    internal constructor(
        val address: LocalDeclarationAddress,
        val supertypes: List<CanonicalCompilerType>,
    ) : CanonicalCompilerSignature {
        override val declarationAddress: CompilerDeclarationAddress
            get() = CompilerDeclarationAddress.Local(address)
    }

    /** Explicit projection for a persistence or transport boundary. */
    fun canonicalEncoding(): CanonicalCompilerSignatureEncoding =
        CanonicalCompilerSignatureEncoding(encodeCanonicalSignature())

    companion object {
        fun anonymousObject(
            address: LocalDeclarationAddress,
            rawSupertypes: List<String>,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            if (address.kind != LocalDeclarationKind.ANONYMOUS_OBJECT)
                return Refinement.Rejected(CanonicalCompilerSignatureFailure.LOCAL_ADDRESS_KIND_MISMATCH)
            val types =
                rawSupertypes.canonicalTypes()
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_SUPERTYPE)
            if (types.isEmpty()) return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_SUPERTYPE)
            return Refinement.Refined(AnonymousObject(address, types))
        }

        /**
         * Establishes a structured function signature with a non-blank qualified identity, canonical receiver and
         * parameter types, and a non-negative type-parameter count.
         */
        fun function(
            rawQualifiedIdentity: String,
            rawReceiverType: String?,
            rawContextReceiverTypes: List<String>,
            rawValueParameterTypes: List<String>,
            rawTypeParameterCount: Int,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity =
                canonicalQualifiedIdentity(rawQualifiedIdentity)
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY)
            val receiver =
                when (rawReceiverType) {
                    null -> CanonicalCompilerReceiver.Absent
                    else ->
                        CanonicalCompilerReceiver.Present(
                            canonicalType(rawReceiverType)
                                ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_RECEIVER_TYPE)
                        )
                }
            val contextReceiverTypes =
                rawContextReceiverTypes.canonicalTypes()
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CONTEXT_RECEIVER_TYPE)
            val valueParameterTypes =
                rawValueParameterTypes.canonicalTypes()
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_VALUE_PARAMETER_TYPE)
            if (rawTypeParameterCount < 0) {
                return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_TYPE_PARAMETER_COUNT)
            }
            return Refinement.Refined(
                Function(
                    qualifiedIdentity = qualifiedIdentity,
                    receiver = receiver,
                    contextReceivers = contextReceiverTypes,
                    valueParameters = valueParameterTypes,
                    typeParameterCount = CanonicalTypeParameterCount(rawTypeParameterCount),
                )
            )
        }

        /**
         * Establishes a structured property signature with canonical extension/context receivers and return type.
         * Receiver proof is part of identity so same-name extension properties cannot collide.
         */
        fun property(
            rawQualifiedIdentity: String,
            rawReceiverType: String?,
            rawContextReceiverTypes: List<String>,
            rawReturnType: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity =
                canonicalQualifiedIdentity(rawQualifiedIdentity)
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY)
            val receiver =
                when (rawReceiverType) {
                    null -> CanonicalCompilerReceiver.Absent
                    else ->
                        CanonicalCompilerReceiver.Present(
                            canonicalType(rawReceiverType)
                                ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_RECEIVER_TYPE)
                        )
                }
            val contextReceivers =
                rawContextReceiverTypes.canonicalTypes()
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CONTEXT_RECEIVER_TYPE)
            val returnType =
                canonicalType(rawReturnType)
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_RETURN_TYPE)
            return Refinement.Refined(Property(qualifiedIdentity, receiver, contextReceivers, returnType))
        }

        /** Establishes a structured type-alias signature with a non-blank qualified identity. */
        fun typeAlias(
            rawQualifiedIdentity: String
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
            when (val qualifiedIdentity = canonicalQualifiedIdentity(rawQualifiedIdentity)) {
                null -> Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY)
                else -> Refinement.Refined(TypeAlias(qualifiedIdentity))
            }

        /** Establishes a structured class-like signature with a non-blank qualified identity. */
        fun classLike(
            rawQualifiedIdentity: String
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
            when (val qualifiedIdentity = canonicalQualifiedIdentity(rawQualifiedIdentity)) {
                null -> Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY)
                else -> Refinement.Refined(ClassLike(qualifiedIdentity))
            }

        fun localFunction(
            address: LocalDeclarationAddress,
            rawReceiverType: String?,
            rawContextReceiverTypes: List<String>,
            rawValueParameterTypes: List<String>,
            rawTypeParameterCount: Int,
            rawReturnType: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            if (address.kind != LocalDeclarationKind.FUNCTION)
                return Refinement.Rejected(CanonicalCompilerSignatureFailure.LOCAL_ADDRESS_KIND_MISMATCH)
            val receiver =
                when (rawReceiverType) {
                    null -> CanonicalCompilerReceiver.Absent
                    else ->
                        CanonicalCompilerReceiver.Present(
                            canonicalType(rawReceiverType)
                                ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_RECEIVER_TYPE)
                        )
                }
            val contexts =
                rawContextReceiverTypes.canonicalTypes()
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CONTEXT_RECEIVER_TYPE)
            val parameters =
                rawValueParameterTypes.canonicalTypes()
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_VALUE_PARAMETER_TYPE)
            if (rawTypeParameterCount < 0)
                return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_TYPE_PARAMETER_COUNT)
            val result =
                canonicalType(rawReturnType)
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_RETURN_TYPE)
            return Refinement.Refined(
                LocalFunction(
                    address,
                    receiver,
                    contexts,
                    parameters,
                    CanonicalTypeParameterCount(rawTypeParameterCount),
                    result,
                )
            )
        }

        fun localProperty(
            address: LocalDeclarationAddress,
            rawReturnType: String,
            mutability: LocalPropertyMutability,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            if (address.kind != LocalDeclarationKind.PROPERTY)
                return Refinement.Rejected(CanonicalCompilerSignatureFailure.LOCAL_ADDRESS_KIND_MISMATCH)
            val result =
                canonicalType(rawReturnType)
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_RETURN_TYPE)
            return Refinement.Refined(LocalProperty(address, result, mutability))
        }

        /**
         * Restores only exact `canonical-signature-v1` encodings. Unsupported, malformed, or non-canonical encodings
         * fail closed as finite data.
         */
        fun restoreCanonicalEncoding(
            raw: String
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val fields =
                decodeCanonicalFields(raw)
                    ?: return Refinement.Rejected(CanonicalCompilerSignatureFailure.INVALID_CANONICAL_ENCODING)
            if (fields.firstOrNull() != CANONICAL_SIGNATURE_VERSION) {
                return Refinement.Rejected(CanonicalCompilerSignatureFailure.UNSUPPORTED_CANONICAL_VERSION)
            }
            val cursor = CanonicalFieldCursor(fields.drop(1))
            val restored =
                when (cursor.next()) {
                    FUNCTION_SIGNATURE_KIND -> restoreFunction(cursor)
                    PROPERTY_SIGNATURE_KIND -> restoreProperty(cursor)
                    TYPE_ALIAS_SIGNATURE_KIND -> restoreTypeAlias(cursor)
                    CLASS_LIKE_SIGNATURE_KIND -> restoreClassLike(cursor)
                    "local-function-v1" -> restoreLocalFunction(cursor)
                    "local-property-v1" -> restoreLocalProperty(cursor)
                    "anonymous-object-v1" -> restoreAnonymousObject(cursor)
                    else -> Refinement.Rejected(CanonicalCompilerSignatureFailure.UNSUPPORTED_SIGNATURE_KIND)
                }
            return restored.requireExactEncoding(raw, cursor)
        }

        private fun restoreFunction(
            cursor: CanonicalFieldCursor
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = cursor.next() ?: return invalidCanonicalEncoding()
            val receiverType =
                when (cursor.next()) {
                    RECEIVER_ABSENT -> null
                    RECEIVER_PRESENT -> cursor.next() ?: return invalidCanonicalEncoding()
                    else -> return invalidCanonicalEncoding()
                }
            val contextReceivers = cursor.nextValues() ?: return invalidCanonicalEncoding()
            val valueParameters = cursor.nextValues() ?: return invalidCanonicalEncoding()
            val typeParameterCount = cursor.next()?.toIntOrNull() ?: return invalidCanonicalEncoding()
            return function(
                rawQualifiedIdentity = qualifiedIdentity,
                rawReceiverType = receiverType,
                rawContextReceiverTypes = contextReceivers,
                rawValueParameterTypes = valueParameters,
                rawTypeParameterCount = typeParameterCount,
            )
        }

        private fun restoreProperty(
            cursor: CanonicalFieldCursor
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = cursor.next() ?: return invalidCanonicalEncoding()
            val receiverType =
                when (cursor.next()) {
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
            cursor: CanonicalFieldCursor
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = cursor.next() ?: return invalidCanonicalEncoding()
            return typeAlias(qualifiedIdentity)
        }

        private fun restoreClassLike(
            cursor: CanonicalFieldCursor
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
    signature: CanonicalCompilerSignature
): CompilerSymbolIdentity {
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.canonicalEncoding().value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(CANONICAL_SIGNATURE_HEX_RADIX).padStart(2, '0')
            }
    return when (val identity = CompilerSymbolIdentity.parse(CANONICAL_IDENTITY_PREFIX + digest)) {
        is Refinement.Refined -> identity.value
        is Refinement.Rejected -> error("SHA-256 projection is a canonical compiler identity")
    }
}
