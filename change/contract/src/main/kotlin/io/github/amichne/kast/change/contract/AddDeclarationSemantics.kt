package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class AddDeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM_CLASS,
    ANNOTATION_CLASS,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
}

enum class ExpectedAddDeclarationDeltaFailure {
    PACKAGE_NAME_INVALID,
    DECLARATION_NAME_INVALID,
}

@Serializable
@ConsistentCopyVisibility
data class ExpectedAddDeclarationDelta private constructor(
    val packageName: String,
    val declarationName: String,
    val declarationKind: AddDeclarationKind,
) {
    companion object {
        /**
         * Proof transition:
         * Raw semantic fields to Refinement of ExpectedAddDeclarationDelta or
         * ExpectedAddDeclarationDeltaFailure.
         *
         * Establishes the exact operation-specific declaration identity expected after apply.
         * ExpectedAddDeclarationDeltaFailure is the closed expected failure. Raw semantic strings
         * may be extracted only by the compiler-backed planning adapter.
         */
        fun admit(
            packageName: String,
            declarationName: String,
            declarationKind: AddDeclarationKind,
        ): Refinement<ExpectedAddDeclarationDelta, ExpectedAddDeclarationDeltaFailure> {
            if (!validPackageName(packageName)) {
                return Refinement.Rejected(ExpectedAddDeclarationDeltaFailure.PACKAGE_NAME_INVALID)
            }
            if (!canonicalName(declarationName)) {
                return Refinement.Rejected(ExpectedAddDeclarationDeltaFailure.DECLARATION_NAME_INVALID)
            }
            return Refinement.Refined(
                ExpectedAddDeclarationDelta(
                    packageName = packageName,
                    declarationName = declarationName,
                    declarationKind = declarationKind,
                ),
            )
        }
    }
}

@Serializable
enum class AddDeclarationObligation : ChangeVerificationObligation {
    TARGET_PREIMAGE_UNCHANGED,
    GENERATION_UNCHANGED,
    OWNER_AND_PROVENANCE_UNCHANGED,
    DECLARED_WRITE_SET_CLOSED,
    EXPECTED_POSTIMAGE_OBSERVED,
    DECLARATION_IDENTITY_OBSERVED,
    COMPILER_COLLISION_REMAINS_ABSENT,
    OUTBOUND_BINDINGS_PRESERVED,
    EXISTING_BINDINGS_PRESERVED,
    COMPILER_DIAGNOSTICS_CLEAR,
    RESULT_GENERATION_PUBLISHED,
}

@Serializable
@JvmInline
value class AddDeclarationGeneration private constructor(val value: Long) {
    companion object {
        internal fun of(generation: EvidenceGeneration): AddDeclarationGeneration =
            AddDeclarationGeneration(generation.value)
    }
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationVerificationContract private constructor(
    val requiredGeneration: AddDeclarationGeneration,
    val obligations: List<AddDeclarationObligation>,
) {
    companion object {
        /**
         * Proof transition:
         * EvidenceGeneration to AddDeclarationVerificationContract.
         *
         * Establishes the complete closed verification obligation set bound to the exact planning
         * generation. There is no expected failure because EvidenceGeneration already proves a
         * non-negative value. Raw generation values may be extracted only by workspace publication.
         */
        fun forGeneration(generation: EvidenceGeneration): AddDeclarationVerificationContract =
            AddDeclarationVerificationContract(
                requiredGeneration = AddDeclarationGeneration.of(generation),
                obligations = AddDeclarationObligation.entries,
            )
    }
}

enum class DetachedCompilerEvidenceFailure {
    NOT_JSON,
}

@Serializable
@ConsistentCopyVisibility
data class DetachedCompilerEvidence private constructor(
    val canonicalJson: String,
    val sha256: String,
) {
    init {
        require(canonicalJsonOrNull(canonicalJson) == canonicalJson)
        require(sha256Hex(canonicalJson.toByteArray()) == sha256)
    }

    companion object {
        /**
         * Proof transition:
         * String to Refinement of DetachedCompilerEvidence or DetachedCompilerEvidenceFailure.
         *
         * Establishes canonical detached JSON and its exact SHA-256 so every legacy compiler proof
         * fact survives compatibility projection. DetachedCompilerEvidenceFailure is the closed
         * expected failure. Raw JSON may be extracted only at the named legacy transport boundary.
         */
        fun admit(
            rawJson: String,
        ): Refinement<DetachedCompilerEvidence, DetachedCompilerEvidenceFailure> {
            val canonical = canonicalJsonOrNull(rawJson)
                            ?: return Refinement.Rejected(DetachedCompilerEvidenceFailure.NOT_JSON)
            return Refinement.Refined(
                DetachedCompilerEvidence(canonical, sha256Hex(canonical.toByteArray())),
            )
        }
    }
}

internal fun canonicalJsonOrNull(raw: String): String? = runCatching {
    Json.parseToJsonElement(raw).canonical().toString()
}.getOrNull()

private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries.sortedBy { entry -> entry.key }.associate { entry ->
            entry.key to entry.value.canonical()
        },
    )
    is JsonArray -> JsonArray(map(JsonElement::canonical))
    else -> this
}

private fun canonicalName(raw: String): Boolean =
    raw.isNotBlank() && raw == raw.trim() && raw.none(Char::isISOControl)

private fun validPackageName(raw: String): Boolean =
    raw.isEmpty() || raw.split('.').all(::canonicalName)
