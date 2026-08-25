package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val CANONICAL_SIGNATURE_VERSION = "canonical-signature-v1"
private const val CANONICAL_IDENTITY_PREFIX = "canonical-signature-sha256-v1|"
private const val HEX_RADIX = 16

enum class CanonicalCompilerSignatureFailure {
    INVALID_QUALIFIED_IDENTITY,
    INVALID_RECEIVER_TYPE,
    INVALID_CONTEXT_RECEIVER_TYPE,
    INVALID_VALUE_PARAMETER_TYPE,
    INVALID_TYPE_PARAMETER_COUNT,
    INVALID_RETURN_TYPE,
}

/** Versioned, unambiguous native compiler signature owned by the symbol contract. */
@JvmInline
value class CanonicalCompilerSignature private constructor(
    internal val value: String,
) {
    companion object {
        /**
         * Proof transition: `(String, String?, List<String>, List<String>, Int) -> Refinement<
         * CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>`.
         *
         * Establishes a versioned, length-prefixed function signature with a non-blank qualified
         * identity, canonical receiver and parameter types, and a non-negative type-parameter
         * count. [CanonicalCompilerSignatureFailure] is the closed expected failure. Raw K2
         * renderings may enter only from native compiler adapters; canonical text may be extracted
         * only by [CompilerSymbolIdentity.fromCanonicalSignature].
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
            val receiverType = when (rawReceiverType) {
                null -> null
                else -> canonicalType(rawReceiverType)
                    ?: return Refinement.Rejected(
                        CanonicalCompilerSignatureFailure.INVALID_RECEIVER_TYPE,
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
                signature("function") {
                    appendCanonicalField(qualifiedIdentity)
                    when (receiverType) {
                        null -> appendCanonicalField("receiver-absent")
                        else -> {
                            appendCanonicalField("receiver-present")
                            appendCanonicalField(receiverType)
                        }
                    }
                    appendCanonicalFields(contextReceiverTypes)
                    appendCanonicalFields(valueParameterTypes)
                    appendCanonicalField(rawTypeParameterCount.toString())
                },
            )
        }

        /**
         * Proof transition: `(String, String) -> Refinement<CanonicalCompilerSignature,
         * CanonicalCompilerSignatureFailure>`.
         *
         * Establishes a versioned, length-prefixed property signature with a non-blank qualified
         * identity and canonical return type. [CanonicalCompilerSignatureFailure] is the closed
         * expected failure. Raw K2 renderings may enter only from native compiler adapters;
         * canonical text may be extracted only by [CompilerSymbolIdentity.fromCanonicalSignature].
         */
        fun property(
            rawQualifiedIdentity: String,
            rawReturnType: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = canonicalQualifiedIdentity(rawQualifiedIdentity)
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY,
                )
            val returnType = canonicalType(rawReturnType)
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_RETURN_TYPE,
                )
            return Refinement.Refined(
                signature("property") {
                    appendCanonicalField(qualifiedIdentity)
                    appendCanonicalField(returnType)
                },
            )
        }

        /**
         * Proof transition: `String -> Refinement<CanonicalCompilerSignature,
         * CanonicalCompilerSignatureFailure>`.
         *
         * Establishes a versioned, length-prefixed type-alias signature with a non-blank qualified
         * identity. [CanonicalCompilerSignatureFailure] is the closed expected failure. Raw K2
         * renderings may enter only from native compiler adapters; canonical text may be extracted
         * only by [CompilerSymbolIdentity.fromCanonicalSignature].
         */
        fun typeAlias(
            rawQualifiedIdentity: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
            named("type-alias", rawQualifiedIdentity)

        /**
         * Proof transition: `String -> Refinement<CanonicalCompilerSignature,
         * CanonicalCompilerSignatureFailure>`.
         *
         * Establishes a versioned, length-prefixed class-like signature with a non-blank qualified
         * identity. [CanonicalCompilerSignatureFailure] is the closed expected failure. Raw K2
         * renderings may enter only from native compiler adapters; canonical text may be extracted
         * only by [CompilerSymbolIdentity.fromCanonicalSignature].
         */
        fun classLike(
            rawQualifiedIdentity: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
            named("class-like", rawQualifiedIdentity)

        private fun named(
            kind: String,
            rawQualifiedIdentity: String,
        ): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> {
            val qualifiedIdentity = canonicalQualifiedIdentity(rawQualifiedIdentity)
                ?: return Refinement.Rejected(
                    CanonicalCompilerSignatureFailure.INVALID_QUALIFIED_IDENTITY,
                )
            return Refinement.Refined(
                signature(kind) {
                    appendCanonicalField(qualifiedIdentity)
                },
            )
        }

        private fun signature(
            kind: String,
            fields: StringBuilder.() -> Unit,
        ): CanonicalCompilerSignature = CanonicalCompilerSignature(
            buildString {
                appendCanonicalField(CANONICAL_SIGNATURE_VERSION)
                appendCanonicalField(kind)
                fields()
            },
        )
    }
}

/**
 * Proof transition: `CanonicalCompilerSignature -> CompilerSymbolIdentity`.
 *
 * Establishes the fixed-size, versioned lowercase SHA-256 identity for one validated compiler
 * signature. The transition has no expected failure because the input already carries every
 * signature invariant and SHA-256 always produces the admitted identity form. Raw identity text
 * may be extracted only at protocol serialization and topology persistence boundaries.
 */
fun CompilerSymbolIdentity.Companion.fromCanonicalSignature(
    signature: CanonicalCompilerSignature,
): CompilerSymbolIdentity {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(signature.value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(HEX_RADIX).padStart(2, '0')
        }
    return when (val identity = CompilerSymbolIdentity.parse(CANONICAL_IDENTITY_PREFIX + digest)) {
        is Refinement.Refined -> identity.value
        is Refinement.Rejected -> error("SHA-256 projection is a canonical compiler identity")
    }
}

private fun canonicalQualifiedIdentity(raw: String): String? =
    raw.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }

private fun canonicalType(raw: String): String? {
    val canonical = raw.filterNot(Char::isWhitespace)
    return canonical.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
}

private fun List<String>.canonicalTypes(): List<String>? {
    val canonical = map { raw -> canonicalType(raw) ?: return null }
    return canonical
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
