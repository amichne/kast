package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease

enum class RelationProviderElementClassFailure {
    BLANK
}

/** Exact native PSI implementation identity, detached only at the provider boundary. */
@JvmInline
value class RelationProviderElementClass private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<RelationProviderElementClass, RelationProviderElementClassFailure> =
            if (raw.isBlank()) Refinement.Rejected(RelationProviderElementClassFailure.BLANK)
            else Refinement.Refined(RelationProviderElementClass(raw))
    }
}

enum class PublishedRelationFactFailure {
    LIVE_AUTHORITY,
    AUTHORITY_MISMATCH,
}

enum class RelationPublishedSnapshotIdentityFailure {
    INVALID_SHA256
}

/** Exact published lease and detached content digest retained by every published relation locator. */
@ConsistentCopyVisibility
data class RelationPublishedSnapshotIdentity private constructor(val lease: SemanticReadLease, val digest: String) {
    companion object {
        fun admit(
            lease: SemanticReadLease,
            digest: String,
        ): Refinement<RelationPublishedSnapshotIdentity, RelationPublishedSnapshotIdentityFailure> =
            if (digest.isCanonicalSha256()) Refinement.Refined(RelationPublishedSnapshotIdentity(lease, digest))
            else Refinement.Rejected(RelationPublishedSnapshotIdentityFailure.INVALID_SHA256)
    }
}

/** Detached native discovery evidence or an already confirmed fact from an exact published snapshot. */
sealed interface RelationProviderLocator {
    val file: SymbolDiscoveryFileIdentity
    val range: ExactDeclarationTextRange
    val descriptor: RelationProviderItemDescriptor

    val retainedBytes: Long
        get() = PROVIDER_LOCATOR_STRUCTURE_BYTES + detachedTextUnits() * PROVIDER_LOCATOR_UTF16_UNIT_BYTES

    data class Reference(
        override val file: SymbolDiscoveryFileIdentity,
        override val range: ExactDeclarationTextRange,
        override val descriptor: RelationProviderItemDescriptor,
    ) : RelationProviderLocator

    class PublishedFact
    private constructor(val publication: RelationPublishedSnapshotIdentity, val fact: RelationFact) :
        RelationProviderLocator {
        override val file: SymbolDiscoveryFileIdentity = fact.occurrence.file
        override val range: ExactDeclarationTextRange = fact.occurrence.range
        override val descriptor: RelationProviderItemDescriptor =
            RelationProviderItemDescriptor.parse(fact.canonicalProjection()).refinedInvariant()
        override val retainedBytes: Long
            get() = PUBLISHED_LOCATOR_STRUCTURE_BYTES + detachedTextUnits() * PUBLISHED_LOCATOR_TEXT_BYTES

        companion object {
            /** Preserves a confirmed edge's exact published authority without claiming native discovery. */
            fun admit(
                publication: RelationPublishedSnapshotIdentity,
                fact: RelationFact,
            ): Refinement<PublishedFact, PublishedRelationFactFailure> =
                when (val authority = fact.authority) {
                    is SemanticReadIdentity.Published ->
                        if (authority.lease == publication.lease) Refinement.Refined(PublishedFact(publication, fact))
                        else Refinement.Rejected(PublishedRelationFactFailure.AUTHORITY_MISMATCH)
                    is SemanticReadIdentity.Live -> Refinement.Rejected(PublishedRelationFactFailure.LIVE_AUTHORITY)
                }
        }
    }

    sealed interface Definition : RelationProviderLocator {
        val elementClass: RelationProviderElementClass
        val providerFile: SymbolDiscoveryFileIdentity

        data class Normalized(
            override val file: SymbolDiscoveryFileIdentity,
            override val range: ExactDeclarationTextRange,
            override val descriptor: RelationProviderItemDescriptor,
            override val elementClass: RelationProviderElementClass,
            override val providerFile: SymbolDiscoveryFileIdentity,
        ) : Definition

        data class Unsupported(
            override val file: SymbolDiscoveryFileIdentity,
            override val range: ExactDeclarationTextRange,
            override val descriptor: RelationProviderItemDescriptor,
            override val elementClass: RelationProviderElementClass,
            override val providerFile: SymbolDiscoveryFileIdentity,
        ) : Definition
    }

    sealed interface Callee : RelationProviderLocator {
        data class Reference(
            override val file: SymbolDiscoveryFileIdentity,
            override val range: ExactDeclarationTextRange,
            override val descriptor: RelationProviderItemDescriptor,
        ) : Callee

        data class UnresolvedCall(
            override val file: SymbolDiscoveryFileIdentity,
            override val range: ExactDeclarationTextRange,
            override val descriptor: RelationProviderItemDescriptor,
            val elementClass: RelationProviderElementClass,
        ) : Callee
    }
}

internal fun RelationProviderLocator.canonicalIdentity(): String {
    val kind =
        when (this) {
            is RelationProviderLocator.Reference -> "reference"
            is RelationProviderLocator.PublishedFact ->
                "published-fact:${publication.digest}:${fact.canonicalProjection()}"
            is RelationProviderLocator.Definition.Normalized ->
                "normalized-definition:${elementClass.value}:${providerFile.stableValue}"
            is RelationProviderLocator.Definition.Unsupported ->
                "unsupported-definition:${elementClass.value}:${providerFile.stableValue}"
            is RelationProviderLocator.Callee.Reference -> "callee-reference"
            is RelationProviderLocator.Callee.UnresolvedCall -> "unresolved-call:${elementClass.value}"
        }
    return "$kind:${file.stableValue}:${range.startInclusive}:${range.endExclusive}:${descriptor.value}"
}

internal fun RelationProviderLocator.detachedTextUnits(): Long =
    file.stableValue.length.toLong() +
        descriptor.value.length +
        when (this) {
            is RelationProviderLocator.Definition ->
                elementClass.value.length.toLong() + providerFile.stableValue.length
            is RelationProviderLocator.Callee.UnresolvedCall -> elementClass.value.length.toLong()
            is RelationProviderLocator.PublishedFact ->
                publication.digest.length +
                    fact.subject.detachedTextUnits() +
                    fact.source.detachedTextUnits() +
                    fact.target.detachedTextUnits()
            is RelationProviderLocator.Reference,
            is RelationProviderLocator.Callee.Reference -> 0L
        }

// Quota estimate for the detached locator, range, two file identities, four String/backing-array headers,
// references, collection slot and alignment. Strings are charged independently even when storage is shared.
// This bounded field accounting is not a JVM heap measurement; JSON escaping belongs to projection budgets.
private const val PROVIDER_LOCATOR_STRUCTURE_BYTES = 512L
private const val PROVIDER_LOCATOR_UTF16_UNIT_BYTES = 2L
private const val PUBLISHED_LOCATOR_STRUCTURE_BYTES = 1_024L
private const val PUBLISHED_LOCATOR_TEXT_BYTES = 24L
