package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector

internal enum class CandidateSelectorIssuanceFailure {
    NON_DECLARATION_CANDIDATE,
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

internal sealed interface CandidateSelectorLookup {
    data class Found(
        val selection: SymbolDiscoverySelection,
    ) : CandidateSelectorLookup

    data object Missing : CandidateSelectorLookup
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

    data object Missing : ExactSelectorLookup
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

    data object Missing : RelationSubjectLookup
}

/** Stateless protocol authority over self-describing, generation-bound selector documents. */
internal class CanonicalProtocolAuthority {
    /**
     * Proof transition: `SymbolDiscoveryBatch -> CandidateSelectorIssuance`.
     *
     * Issues one deterministic self-describing token for every declaration candidate. File and
     * text evidence remain structured result variants and never acquire exact-selector authority.
     */
    fun issueCandidates(batch: SymbolDiscoveryBatch): CandidateSelectorIssuance {
        val issued = mutableListOf<ProtocolText>()
        batch.candidates.forEachIndexed { ordinal, candidate ->
            if (candidate.location !is SymbolDiscoveryCandidateLocation.Declaration) return@forEachIndexed
            val selection = when (val selected = SymbolDiscoverySelection.select(batch, ordinal)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return CandidateSelectorIssuance.Rejected(
                    CandidateSelectorIssuanceFailure.NON_DECLARATION_CANDIDATE,
                )
            }
            when (val encoded = CanonicalSelectorCodec.encodeCandidate(selection)) {
                is CanonicalSelectorEncoding.Encoded -> issued += encoded.token
                is CanonicalSelectorEncoding.Rejected ->
                    return CandidateSelectorIssuance.Rejected(
                        CandidateSelectorIssuanceFailure.TOKEN_REJECTED,
                    )
            }
        }
        return CandidateSelectorIssuance.Issued(issued)
    }

    /** Restores candidate authority from token facts without process-local retained state. */
    fun candidate(selector: ProtocolText): CandidateSelectorLookup = when (
        val decoded = CanonicalSelectorCodec.decodeCandidate(selector)
    ) {
        is CanonicalSelectorDecoding.Decoded -> CandidateSelectorLookup.Found(decoded.value)
        is CanonicalSelectorDecoding.Rejected -> CandidateSelectorLookup.Missing
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
        is CanonicalSelectorDecoding.Rejected -> ExactSelectorLookup.Missing
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
        ExactSelectorLookup.Missing -> RelationSubjectLookup.Missing
    }
}
