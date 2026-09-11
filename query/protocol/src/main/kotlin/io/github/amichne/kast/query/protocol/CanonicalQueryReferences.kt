package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.source.contract.*
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel

enum class CandidateSelectorIssuanceFailure {
    CANDIDATE_REJECTED,
    TOKEN_REJECTED,
}

sealed interface CandidateSelectorIssuance {
    data class Issued(val selectors: List<ProtocolText>) : CandidateSelectorIssuance

    data class Rejected(val failure: CandidateSelectorIssuanceFailure) : CandidateSelectorIssuance
}

enum class CandidateSelectorTokenIssuanceFailure {
    CANDIDATE_REJECTED,
    TOKEN_REJECTED,
}

sealed interface CandidateSelectorTokenIssuance {
    data class Issued(val selector: ProtocolText) : CandidateSelectorTokenIssuance

    data class Rejected(val failure: CandidateSelectorTokenIssuanceFailure) : CandidateSelectorTokenIssuance
}

sealed interface CandidateSelectorLookup {
    data class Found(val selector: CandidateSelector) : CandidateSelectorLookup

    data class Rejected(val reason: SelectorLookupRejection) : CandidateSelectorLookup
}

enum class SelectorLookupRejection {
    WRONG_KIND,
    MALFORMED,
    STALE,
    WORKSPACE_MISMATCH,
}

enum class ExactSelectorIssuanceFailure {
    TOKEN_REJECTED
}

sealed interface ExactSelectorIssuance {
    data class Issued(val selector: ProtocolText) : ExactSelectorIssuance

    data class Rejected(val failure: ExactSelectorIssuanceFailure) : ExactSelectorIssuance
}

sealed interface ExactSelectorLookup {
    data class Found(val selector: SymbolSelector) : ExactSelectorLookup

    data class Rejected(val reason: SelectorLookupRejection) : ExactSelectorLookup
}

enum class RelationEndpointIssuanceFailure {
    TOKEN_REJECTED
}

sealed interface RelationEndpointIssuance {
    data class Issued(val selector: ProtocolText) : RelationEndpointIssuance

    data class Rejected(val failure: RelationEndpointIssuanceFailure) : RelationEndpointIssuance
}

sealed interface RelationSubjectLookup {
    data class Selector(val selector: SymbolSelector) : RelationSubjectLookup

    data class Rejected(val reason: SelectorLookupRejection) : RelationSubjectLookup
}

/** Stateless selector transport; live restoration requires the host's current in-process admission. */
class CanonicalQueryReferences
private constructor(
    private val restoreSourceSelector:
        (SourceSelectorToken, SemanticReadAuthority) -> Refinement<SourceSelector, SourceSelectorTokenFailure>
) : QueryReferenceAuthority {
    constructor() :
        this({ token, current ->
            when (current) {
                is LiveSemanticReadAuthority -> SourceSelectorTokenCodec.decode(token, current)
                is SemanticReadLease -> SourceSelectorTokenCodec.decode(token)
            }
        })

    /** Retains the imported model supplied by the current read owner for modeled source scopes. */
    constructor(
        model: WorkspaceSearchScopeModel
    ) : this({ token, current ->
        when (current) {
            is LiveSemanticReadAuthority -> SourceSelectorTokenCodec.decode(token, current, model)
            is SemanticReadLease -> SourceSelectorTokenCodec.decode(token, model)
        }
    })

    override fun restoreSource(
        token: SourceSelectorToken,
        current: SemanticReadAuthority,
    ): Refinement<SourceSelector, SourceSelectorTokenFailure> {
        val decoded = restoreSourceSelector(token, current)
        return when (decoded) {
            is Refinement.Rejected -> decoded
            is Refinement.Refined ->
                if (decoded.value.snapshot.lease == current) decoded
                else Refinement.Rejected(SourceSelectorTokenFailure.SELECTOR_REJECTED)
        }
    }

    override fun restoreCandidate(
        token: ProtocolText,
        current: SemanticReadAuthority,
    ): CanonicalSelectorDecoding<CandidateSelector> = CanonicalSelectorCodec.decodeCandidate(token, current)

    override fun restoreExact(
        token: ProtocolText,
        current: SemanticReadAuthority,
    ): CanonicalSelectorDecoding<SymbolSelector> = CanonicalSelectorCodec.decodeExact(token, current)

    /**
     * Proof transition: `SymbolDiscoveryBatch -> CandidateSelectorIssuance`.
     *
     * Issues one deterministic self-describing token for every source-located discovery candidate. No variant acquires
     * exact source or compiler authority at this transition.
     */
    override fun issueCandidates(batch: SymbolDiscoveryBatch): CandidateSelectorIssuance {
        val issued = mutableListOf<ProtocolText>()
        batch.candidates.forEachIndexed { ordinal, candidate ->
            val selector =
                when (candidate.location) {
                    is SymbolDiscoveryCandidateLocation.Declaration -> {
                        val selection =
                            when (val selected = SymbolDiscoverySelection.select(batch, ordinal)) {
                                is Refinement.Refined -> selected.value
                                is Refinement.Rejected ->
                                    return CandidateSelectorIssuance.Rejected(
                                        CandidateSelectorIssuanceFailure.CANDIDATE_REJECTED
                                    )
                            }
                        when (val candidateSelector = CandidateSelector.declaration(selection)) {
                            is Refinement.Refined -> candidateSelector.value
                            is Refinement.Rejected ->
                                return CandidateSelectorIssuance.Rejected(
                                    CandidateSelectorIssuanceFailure.CANDIDATE_REJECTED
                                )
                        }
                    }
                    is SymbolDiscoveryCandidateLocation.File ->
                        when (val selected = CandidateSelector.file(batch, ordinal)) {
                            is Refinement.Refined -> selected.value
                            is Refinement.Rejected ->
                                return CandidateSelectorIssuance.Rejected(
                                    CandidateSelectorIssuanceFailure.CANDIDATE_REJECTED
                                )
                        }
                    is SymbolDiscoveryCandidateLocation.Text ->
                        when (val selected = CandidateSelector.range(batch, ordinal)) {
                            is Refinement.Refined -> selected.value
                            is Refinement.Rejected ->
                                return CandidateSelectorIssuance.Rejected(
                                    CandidateSelectorIssuanceFailure.CANDIDATE_REJECTED
                                )
                        }
                }
            when (val encoded = issueCandidate(selector)) {
                is CandidateSelectorTokenIssuance.Issued -> issued += encoded.selector
                is CandidateSelectorTokenIssuance.Rejected ->
                    return CandidateSelectorIssuance.Rejected(CandidateSelectorIssuanceFailure.TOKEN_REJECTED)
            }
        }
        return CandidateSelectorIssuance.Issued(issued)
    }

    /** Issues the declaration-candidate family for one already selected query item. */
    override fun issueDeclarationCandidate(selection: SymbolDiscoverySelection): CandidateSelectorTokenIssuance =
        when (val selector = CandidateSelector.declaration(selection)) {
            is Refinement.Refined -> issueCandidate(selector.value)
            is Refinement.Rejected ->
                CandidateSelectorTokenIssuance.Rejected(CandidateSelectorTokenIssuanceFailure.CANDIDATE_REJECTED)
        }

    /**
     * Refines retained semantic location evidence into the same candidate token family used by discovery. Empty ranges
     * remain valid because compiler diagnostics may identify insertion points; reversed, external, or unencodable
     * locations fail closed.
     */
    override fun issueRangeCandidate(
        lease: SemanticReadAuthority,
        file: SymbolDiscoveryFileIdentity,
        rawStartInclusive: Int,
        rawEndExclusive: Int,
    ): CandidateSelectorTokenIssuance {
        val workspaceFile =
            file as? SymbolDiscoveryFileIdentity.Workspace
                ?: return CandidateSelectorTokenIssuance.Rejected(
                    CandidateSelectorTokenIssuanceFailure.CANDIDATE_REJECTED
                )
        val selector =
            when (
                val restored =
                    CandidateSelector.restoreRange(
                        lease,
                        workspaceFile,
                        rawStartInclusive,
                        rawEndExclusive,
                    )
            ) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected ->
                    return CandidateSelectorTokenIssuance.Rejected(
                        CandidateSelectorTokenIssuanceFailure.CANDIDATE_REJECTED
                    )
            }
        return issueCandidate(selector)
    }

    /** Restores candidate authority from token facts without process-local retained state. */
    fun candidate(selector: ProtocolText): CandidateSelectorLookup =
        when (val decoded = CanonicalSelectorCodec.decodeCandidate(selector)) {
            is CanonicalSelectorDecoding.Decoded -> CandidateSelectorLookup.Found(decoded.value)
            is CanonicalSelectorDecoding.Rejected ->
                CandidateSelectorLookup.Rejected(selector.lookupRejection(expectedExact = false))
        }

    private fun issueCandidate(selector: CandidateSelector): CandidateSelectorTokenIssuance =
        when (val encoded = CanonicalSelectorCodec.encodeCandidate(selector)) {
            is CanonicalSelectorEncoding.Encoded -> CandidateSelectorTokenIssuance.Issued(encoded.token)
            is CanonicalSelectorEncoding.Rejected ->
                CandidateSelectorTokenIssuance.Rejected(CandidateSelectorTokenIssuanceFailure.TOKEN_REJECTED)
        }

    /** Issues one self-describing exact selector token. */
    override fun issueExact(selector: SymbolSelector): ExactSelectorIssuance =
        when (val encoded = CanonicalSelectorCodec.encodeExact(selector)) {
            is CanonicalSelectorEncoding.Encoded -> ExactSelectorIssuance.Issued(encoded.token)
            is CanonicalSelectorEncoding.Rejected ->
                ExactSelectorIssuance.Rejected(ExactSelectorIssuanceFailure.TOKEN_REJECTED)
        }

    /** Restores exact selector authority and verifies its deterministic fingerprint. */
    fun exact(selector: ProtocolText): ExactSelectorLookup =
        when (val decoded = CanonicalSelectorCodec.decodeExact(selector)) {
            is CanonicalSelectorDecoding.Decoded -> ExactSelectorLookup.Found(decoded.value)
            is CanonicalSelectorDecoding.Rejected ->
                ExactSelectorLookup.Rejected(selector.lookupRejection(expectedExact = true))
        }

    /**
     * Converts every compiler-grounded relation endpoint into the same exact selector family used by describe,
     * relation, and traversal consumers.
     */
    override fun issueEndpoint(endpoint: RelationEndpoint): RelationEndpointIssuance {
        val selector =
            when (endpoint) {
                is RelationEndpoint.Subject -> endpoint.selector
                is RelationEndpoint.Resolved ->
                    SymbolSelector.issue(endpoint.lease, endpoint.scope, endpoint.evidence, endpoint.constraints)
            }
        return when (val issued = issueExact(selector)) {
            is ExactSelectorIssuance.Issued -> RelationEndpointIssuance.Issued(issued.selector)
            is ExactSelectorIssuance.Rejected ->
                RelationEndpointIssuance.Rejected(RelationEndpointIssuanceFailure.TOKEN_REJECTED)
        }
    }

    /** Exact relation subjects use the canonical exact selector family; no third handle exists. */
    fun relationSubject(selector: ProtocolText): RelationSubjectLookup =
        when (val exact = exact(selector)) {
            is ExactSelectorLookup.Found -> RelationSubjectLookup.Selector(exact.selector)
            is ExactSelectorLookup.Rejected -> RelationSubjectLookup.Rejected(exact.reason)
        }
}

private fun ProtocolText.lookupRejection(expectedExact: Boolean): SelectorLookupRejection {
    val belongsToAnotherFamily =
        if (expectedExact) {
            value.startsWith("candidate:") ||
                value.startsWith("source-selector-v1:") ||
                value.startsWith("source-selector-v2:")
        } else {
            value.startsWith("exact:") ||
                value.startsWith("source-selector-v1:") ||
                value.startsWith("source-selector-v2:")
        }
    return if (belongsToAnotherFamily) {
        SelectorLookupRejection.WRONG_KIND
    } else {
        SelectorLookupRejection.MALFORMED
    }
}
