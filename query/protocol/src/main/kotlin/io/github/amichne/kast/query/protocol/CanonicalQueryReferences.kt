package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.source.contract.*
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel

enum class CandidateSelectorTokenIssuanceFailure {
    CANDIDATE_REJECTED,
    TOKEN_REJECTED,
}

sealed interface CandidateSelectorTokenIssuance {
    data class Issued(val selector: ProtocolText) : CandidateSelectorTokenIssuance

    data class Rejected(val failure: CandidateSelectorTokenIssuanceFailure) : CandidateSelectorTokenIssuance
}

enum class ExactSelectorIssuanceFailure {
    TOKEN_REJECTED
}

sealed interface ExactSelectorIssuance {
    data class Issued(val selector: ProtocolText) : ExactSelectorIssuance

    data class Rejected(val failure: ExactSelectorIssuanceFailure) : ExactSelectorIssuance
}

enum class RelationEndpointIssuanceFailure {
    TOKEN_REJECTED
}

sealed interface RelationEndpointIssuance {
    data class Issued(val selector: ProtocolText) : RelationEndpointIssuance

    data class Rejected(val failure: RelationEndpointIssuanceFailure) : RelationEndpointIssuance
}

/** Stateless selector transport; live restoration requires the host's current in-process admission. */
class CanonicalQueryReferences
private constructor(
    private val transport: QueryReferenceTransport,
    private val exactIssued: (SymbolSelector, ProtocolText, ProtocolText) -> Unit,
    private val restoreSourceSelector:
        (SourceSelectorToken, SemanticReadAuthority) -> Refinement<SourceSelector, SourceSelectorTokenFailure>,
) : QueryReferenceAuthority {
    constructor(
        transport: QueryReferenceTransport = QueryReferenceTransport.Inline
    ) : this(
        transport,
        { _, _, _ -> },
        { token, current ->
            when (current) {
                is LiveSemanticReadAuthority -> SourceSelectorTokenCodec.decode(token, current)
                is SemanticReadLease -> SourceSelectorTokenCodec.decode(token)
            }
        },
    )

    /** Retains the imported model supplied by the current read owner for modeled source scopes. */
    constructor(
        model: WorkspaceSearchScopeModel,
        transport: QueryReferenceTransport = QueryReferenceTransport.Inline,
        exactIssued: (SymbolSelector, ProtocolText, ProtocolText) -> Unit = { _, _, _ -> },
    ) : this(
        transport,
        exactIssued,
        { token, current ->
            when (current) {
                is LiveSemanticReadAuthority -> SourceSelectorTokenCodec.decode(token, current, model)
                is SemanticReadLease -> SourceSelectorTokenCodec.decode(token, model)
            }
        },
    )

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
    ): CanonicalSelectorDecoding<CandidateSelector> =
        when (val restored = transport.restore(token)) {
            is CanonicalSelectorDecoding.Decoded -> CanonicalSelectorCodec.decodeCandidate(restored.value, current)
            is CanonicalSelectorDecoding.Rejected -> restored
        }

    override fun restoreExact(
        token: ProtocolText,
        current: SemanticReadAuthority,
    ): CanonicalSelectorDecoding<SymbolSelector> =
        when (val restored = transport.restore(token)) {
            is CanonicalSelectorDecoding.Decoded -> CanonicalSelectorCodec.decodeExact(restored.value, current)
            is CanonicalSelectorDecoding.Rejected -> restored
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

    private fun issueCandidate(selector: CandidateSelector): CandidateSelectorTokenIssuance =
        when (val encoded = CanonicalSelectorCodec.encodeCandidate(selector)) {
            is CanonicalSelectorEncoding.Encoded ->
                CandidateSelectorTokenIssuance.Issued(transport.issue(encoded.token))
            is CanonicalSelectorEncoding.Rejected ->
                CandidateSelectorTokenIssuance.Rejected(CandidateSelectorTokenIssuanceFailure.TOKEN_REJECTED)
        }

    /** Issues one self-describing exact selector token. */
    override fun issueExact(selector: SymbolSelector): ExactSelectorIssuance =
        when (val encoded = CanonicalSelectorCodec.encodeExact(selector)) {
            is CanonicalSelectorEncoding.Encoded -> {
                val token = transport.issue(encoded.token)
                exactIssued(selector, token, encoded.token)
                ExactSelectorIssuance.Issued(token)
            }
            is CanonicalSelectorEncoding.Rejected ->
                ExactSelectorIssuance.Rejected(ExactSelectorIssuanceFailure.TOKEN_REJECTED)
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
}
