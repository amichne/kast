package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.SemanticReadLease

internal enum class CandidateSelectorIssuanceFailure {
    CANDIDATE_REJECTED,
    TOKEN_REJECTED,
}

internal sealed interface CandidateSelectorIssuance {
    data class Issued(
        val selectors: List<ProtocolText>,
    ) : CandidateSelectorIssuance

    data class Rejected(
        val failure: CandidateSelectorIssuanceFailure,
    ) : CandidateSelectorIssuance
}

internal enum class CandidateSelectorTokenIssuanceFailure {
    CANDIDATE_REJECTED,
    TOKEN_REJECTED,
}

internal sealed interface CandidateSelectorTokenIssuance {
    data class Issued(
        val selector: ProtocolText,
    ) : CandidateSelectorTokenIssuance

    data class Rejected(
        val failure: CandidateSelectorTokenIssuanceFailure,
    ) : CandidateSelectorTokenIssuance
}

internal sealed interface CandidateSelectorLookup {
    data class Found(
        val selector: CandidateSelector,
    ) : CandidateSelectorLookup

    data class Rejected(val reason: SelectorLookupRejection) : CandidateSelectorLookup
}

internal enum class SelectorLookupRejection {
    WRONG_KIND,
    MALFORMED,
}

internal enum class ExactSelectorIssuanceFailure {
    TOKEN_REJECTED,
}

internal sealed interface ExactSelectorIssuance {
    data class Issued(
        val selector: ProtocolText,
    ) : ExactSelectorIssuance

    data class Rejected(
        val failure: ExactSelectorIssuanceFailure,
    ) : ExactSelectorIssuance
}

internal sealed interface ExactSelectorLookup {
    data class Found(
        val selector: SymbolSelector,
    ) : ExactSelectorLookup

    data class Rejected(val reason: SelectorLookupRejection) : ExactSelectorLookup
}

internal enum class RelationEndpointIssuanceFailure {
    TOKEN_REJECTED,
}

internal sealed interface RelationEndpointIssuance {
    data class Issued(
        val selector: ProtocolText,
    ) : RelationEndpointIssuance

    data class Rejected(
        val failure: RelationEndpointIssuanceFailure,
    ) : RelationEndpointIssuance
}

internal sealed interface RelationSubjectLookup {
    data class Selector(
        val selector: SymbolSelector,
    ) : RelationSubjectLookup

    data class Rejected(val reason: SelectorLookupRejection) : RelationSubjectLookup
}

/** Stateless protocol authority over self-describing, generation-bound selector documents. */
internal class CanonicalProtocolAuthority {
    /**
     * Proof transition: `SymbolDiscoveryBatch -> CandidateSelectorIssuance`.
     *
     * Issues one deterministic self-describing token for every source-located discovery candidate.
     * No variant acquires exact source or compiler authority at this transition.
     */
    fun issueCandidates(batch: SymbolDiscoveryBatch): CandidateSelectorIssuance {
        val issued = mutableListOf<ProtocolText>()
        batch.candidates.forEachIndexed { ordinal, candidate ->
            val selector = when (candidate.location) {
                is SymbolDiscoveryCandidateLocation.Declaration -> {
                    val selection = when (
                        val selected = SymbolDiscoverySelection.select(batch, ordinal)
                    ) {
                        is Refinement.Refined -> selected.value
                        is Refinement.Rejected -> return CandidateSelectorIssuance.Rejected(
                            CandidateSelectorIssuanceFailure.CANDIDATE_REJECTED,
                        )
                    }
                    when (val candidateSelector = CandidateSelector.declaration(selection)) {
                        is Refinement.Refined -> candidateSelector.value
                        is Refinement.Rejected -> return CandidateSelectorIssuance.Rejected(
                            CandidateSelectorIssuanceFailure.CANDIDATE_REJECTED,
                        )
                    }
                }
                is SymbolDiscoveryCandidateLocation.File -> when (
                    val selected = CandidateSelector.file(candidate)
                ) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return CandidateSelectorIssuance.Rejected(
                        CandidateSelectorIssuanceFailure.CANDIDATE_REJECTED,
                    )
                }
                is SymbolDiscoveryCandidateLocation.Text -> when (
                    val selected = CandidateSelector.range(candidate)
                ) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return CandidateSelectorIssuance.Rejected(
                        CandidateSelectorIssuanceFailure.CANDIDATE_REJECTED,
                    )
                }
            }
            when (val encoded = issueCandidate(selector)) {
                is CandidateSelectorTokenIssuance.Issued -> issued += encoded.selector
                is CandidateSelectorTokenIssuance.Rejected ->
                    return CandidateSelectorIssuance.Rejected(
                        CandidateSelectorIssuanceFailure.TOKEN_REJECTED,
                    )
            }
        }
        return CandidateSelectorIssuance.Issued(issued)
    }

    /** Issues the declaration-candidate family for one already selected query item. */
    fun issueDeclarationCandidate(
        selection: SymbolDiscoverySelection,
    ): CandidateSelectorTokenIssuance = when (val selector = CandidateSelector.declaration(selection)) {
        is Refinement.Refined -> issueCandidate(selector.value)
        is Refinement.Rejected -> CandidateSelectorTokenIssuance.Rejected(
            CandidateSelectorTokenIssuanceFailure.CANDIDATE_REJECTED,
        )
    }

    /**
     * Refines retained semantic location evidence into the same candidate token family used by
     * discovery. Empty ranges remain valid because compiler diagnostics may identify insertion
     * points; reversed, external, or unencodable locations fail closed.
     */
    fun issueRangeCandidate(
        lease: SemanticReadLease,
        file: SymbolDiscoveryFileIdentity,
        rawStartInclusive: Int,
        rawEndExclusive: Int,
    ): CandidateSelectorTokenIssuance {
        val workspaceFile = file as? SymbolDiscoveryFileIdentity.Workspace
            ?: return CandidateSelectorTokenIssuance.Rejected(
                CandidateSelectorTokenIssuanceFailure.CANDIDATE_REJECTED,
            )
        val selector = when (
            val restored = CandidateSelector.restoreRange(
                lease,
                workspaceFile,
                rawStartInclusive,
                rawEndExclusive,
            )
        ) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return CandidateSelectorTokenIssuance.Rejected(
                CandidateSelectorTokenIssuanceFailure.CANDIDATE_REJECTED,
            )
        }
        return issueCandidate(selector)
    }

    /** Restores candidate authority from token facts without process-local retained state. */
    fun candidate(selector: ProtocolText): CandidateSelectorLookup = when (
        val decoded = CanonicalSelectorCodec.decodeCandidate(selector)
    ) {
        is CanonicalSelectorDecoding.Decoded -> CandidateSelectorLookup.Found(decoded.value)
        is CanonicalSelectorDecoding.Rejected -> CandidateSelectorLookup.Rejected(
            selector.lookupRejection(expectedExact = false),
        )
    }

    private fun issueCandidate(selector: CandidateSelector): CandidateSelectorTokenIssuance = when (
        val encoded = CanonicalSelectorCodec.encodeCandidate(selector)
    ) {
        is CanonicalSelectorEncoding.Encoded -> CandidateSelectorTokenIssuance.Issued(encoded.token)
        is CanonicalSelectorEncoding.Rejected -> CandidateSelectorTokenIssuance.Rejected(
            CandidateSelectorTokenIssuanceFailure.TOKEN_REJECTED,
        )
    }

    /** Issues one self-describing exact selector token. */
    fun issueExact(selector: SymbolSelector): ExactSelectorIssuance = when (
        val encoded = CanonicalSelectorCodec.encodeExact(selector)
    ) {
        is CanonicalSelectorEncoding.Encoded -> ExactSelectorIssuance.Issued(encoded.token)
        is CanonicalSelectorEncoding.Rejected -> ExactSelectorIssuance.Rejected(
            ExactSelectorIssuanceFailure.TOKEN_REJECTED,
        )
    }

    /** Restores exact selector authority and verifies its deterministic fingerprint. */
    fun exact(selector: ProtocolText): ExactSelectorLookup = when (
        val decoded = CanonicalSelectorCodec.decodeExact(selector)
    ) {
        is CanonicalSelectorDecoding.Decoded -> ExactSelectorLookup.Found(decoded.value)
        is CanonicalSelectorDecoding.Rejected -> ExactSelectorLookup.Rejected(
            selector.lookupRejection(expectedExact = true),
        )
    }

    /**
     * Converts every compiler-grounded relation endpoint into the same exact selector family used
     * by describe, relation, and traversal consumers.
     */
    fun issueEndpoint(endpoint: RelationEndpoint): RelationEndpointIssuance {
        val selector = when (endpoint) {
            is RelationEndpoint.Subject -> endpoint.selector
            is RelationEndpoint.Resolved ->
                SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence)
        }
        return when (val issued = issueExact(selector)) {
            is ExactSelectorIssuance.Issued -> RelationEndpointIssuance.Issued(issued.selector)
            is ExactSelectorIssuance.Rejected ->
                RelationEndpointIssuance.Rejected(RelationEndpointIssuanceFailure.TOKEN_REJECTED)
        }
    }

    /** Exact relation subjects use the canonical exact selector family; no third handle exists. */
    fun relationSubject(selector: ProtocolText): RelationSubjectLookup = when (val exact = exact(selector)) {
        is ExactSelectorLookup.Found -> RelationSubjectLookup.Selector(exact.selector)
        is ExactSelectorLookup.Rejected -> RelationSubjectLookup.Rejected(exact.reason)
    }
}

private fun ProtocolText.lookupRejection(expectedExact: Boolean): SelectorLookupRejection {
    val belongsToAnotherFamily = if (expectedExact) {
        value.startsWith("candidate:v2:") || value.startsWith("source-selector-v1:")
    } else {
        value.startsWith("exact:v2:") || value.startsWith("source-selector-v1:")
    }
    return if (belongsToAnotherFamily) {
        SelectorLookupRejection.WRONG_KIND
    } else {
        SelectorLookupRejection.MALFORMED
    }
}
