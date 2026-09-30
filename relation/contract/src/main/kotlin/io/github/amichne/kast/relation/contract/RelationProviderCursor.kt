package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.nio.charset.StandardCharsets

enum class RelationProviderPositionFailure {
    NEGATIVE
}

@JvmInline
value class RelationProviderPosition private constructor(val value: Long) {
    companion object {
        val Zero: RelationProviderPosition = RelationProviderPosition(0L)

        /**
         * Proof transition: `Long -> Refinement<RelationProviderPosition, RelationProviderPositionFailure>`.
         *
         * Establishes a non-negative native enumeration position. [RelationProviderPositionFailure] is the closed
         * expected failure. Raw positions may be extracted only by the relation compiler collector and continuation
         * codec.
         */
        fun parse(raw: Long): Refinement<RelationProviderPosition, RelationProviderPositionFailure> =
            if (raw >= 0L) {
                Refinement.Refined(RelationProviderPosition(raw))
            } else {
                Refinement.Rejected(RelationProviderPositionFailure.NEGATIVE)
            }
    }
}

/** Stable provider inventories; V2 binds their detached canonical order before applying semantic bounds. */
enum class RelationProviderKind {
    INTELLIJ_REFERENCES_V2,
    INTELLIJ_DEFINITIONS_V2,
    INTELLIJ_CALLEES_V2,
    PUBLISHED_TOPOLOGY_V1;

    /** Published facts require published authority; native families retain their exact semantic meaning. */
    fun supports(meaning: RelationMeaning, authority: SemanticReadIdentity): Boolean =
        when (this) {
            PUBLISHED_TOPOLOGY_V1 -> authority is SemanticReadIdentity.Published
            INTELLIJ_REFERENCES_V2,
            INTELLIJ_DEFINITIONS_V2,
            INTELLIJ_CALLEES_V2 -> this == forMeaning(meaning)
        }

    companion object {
        fun forMeaning(meaning: RelationMeaning): RelationProviderKind =
            when (meaning) {
                RelationMeaning.References,
                RelationMeaning.Callers,
                RelationMeaning.TypeUses -> INTELLIJ_REFERENCES_V2
                RelationMeaning.Implementations,
                RelationMeaning.Inheritors,
                RelationMeaning.Overrides -> INTELLIJ_DEFINITIONS_V2
                RelationMeaning.Callees -> INTELLIJ_CALLEES_V2
            }
    }
}

enum class RelationProviderItemDescriptorFailure {
    BLANK
}

/** Stable detached identity of one provider item. */
@JvmInline
value class RelationProviderItemDescriptor private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<RelationProviderItemDescriptor, RelationProviderItemDescriptorFailure> =
            if (raw.isBlank()) {
                Refinement.Rejected(RelationProviderItemDescriptorFailure.BLANK)
            } else {
                Refinement.Refined(RelationProviderItemDescriptor(raw))
            }
    }
}

enum class RelationProviderPrefixDigestFailure {
    INVALID_SHA256
}

@JvmInline
value class RelationProviderPrefixDigest private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<RelationProviderPrefixDigest, RelationProviderPrefixDigestFailure> =
            if (raw.isCanonicalSha256()) {
                Refinement.Refined(RelationProviderPrefixDigest(raw))
            } else {
                Refinement.Rejected(RelationProviderPrefixDigestFailure.INVALID_SHA256)
            }

        internal fun digest(bytes: ByteArray): RelationProviderPrefixDigest =
            RelationProviderPrefixDigest(bytes.sha256())
    }
}

/** Inventory preparation and consumed-item identity ledger; this proof is never verified by native prefix replay. */
data class RelationProviderCursor
private constructor(
    val provider: RelationProviderKind,
    val nextPosition: RelationProviderPosition,
    val consumedPrefixDigest: RelationProviderPrefixDigest,
) {
    fun advance(item: RelationProviderItemDescriptor): RelationProviderCursor {
        check(nextPosition.value < Long.MAX_VALUE) { "Relation provider position overflow" }
        val canonical = buildString {
            appendContinuationField(consumedPrefixDigest.value)
            appendContinuationField(item.value)
        }
        return RelationProviderCursor(
            provider,
            RelationProviderPosition.parse(nextPosition.value + 1L).refinedInvariant(),
            RelationProviderPrefixDigest.digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    companion object {
        fun start(provider: RelationProviderKind): RelationProviderCursor {
            val canonical = "kast-relation-provider-prefix-v1:${provider.name}"
            return RelationProviderCursor(
                provider,
                RelationProviderPosition.Zero,
                RelationProviderPrefixDigest.digest(canonical.toByteArray(StandardCharsets.UTF_8)),
            )
        }

        fun restore(
            provider: RelationProviderKind,
            nextPosition: RelationProviderPosition,
            consumedPrefixDigest: RelationProviderPrefixDigest,
        ): RelationProviderCursor =
            RelationProviderCursor(
                provider,
                nextPosition,
                consumedPrefixDigest,
            )
    }
}
