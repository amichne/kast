package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSelectorToken
import io.github.amichne.kast.source.contract.SourceSelectorTokenFailure
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Host-supplied reference boundary; restoration consumes a current in-process admission. */
interface QueryReferenceAuthority {
    fun restoreSource(
        token: SourceSelectorToken,
        current: SemanticReadAuthority,
    ): Refinement<SourceSelector, SourceSelectorTokenFailure>

    fun issueCandidates(batch: SymbolDiscoveryBatch): CandidateSelectorIssuance

    fun restoreCandidate(
        token: ProtocolText,
        current: SemanticReadAuthority,
    ): CanonicalSelectorDecoding<CandidateSelector>

    fun restoreExact(token: ProtocolText, current: SemanticReadAuthority): CanonicalSelectorDecoding<SymbolSelector>

    fun issueDeclarationCandidate(selection: SymbolDiscoverySelection): CandidateSelectorTokenIssuance

    fun issueExact(selector: SymbolSelector): ExactSelectorIssuance

    fun issueEndpoint(endpoint: RelationEndpoint): RelationEndpointIssuance

    fun issueRangeCandidate(
        lease: SemanticReadAuthority,
        file: SymbolDiscoveryFileIdentity,
        rawStartInclusive: Int,
        rawEndExclusive: Int,
    ): CandidateSelectorTokenIssuance
}

internal fun QueryReferenceAuthority.candidate(
    token: ProtocolText,
    current: SemanticReadAuthority,
): CandidateSelectorLookup =
    when (val restored = restoreCandidate(token, current)) {
        is CanonicalSelectorDecoding.Decoded -> CandidateSelectorLookup.Found(restored.value)
        is CanonicalSelectorDecoding.Rejected ->
            CandidateSelectorLookup.Rejected(restored.failure.lookupRejection(token, false))
    }

internal fun QueryReferenceAuthority.exact(token: ProtocolText, current: SemanticReadAuthority): ExactSelectorLookup =
    when (val restored = restoreExact(token, current)) {
        is CanonicalSelectorDecoding.Decoded -> ExactSelectorLookup.Found(restored.value)
        is CanonicalSelectorDecoding.Rejected ->
            ExactSelectorLookup.Rejected(restored.failure.lookupRejection(token, true))
    }

private fun CanonicalSelectorDecodingFailure.lookupRejection(
    token: ProtocolText,
    exact: Boolean,
): SelectorLookupRejection =
    when (this) {
        CanonicalSelectorDecodingFailure.INCOMPATIBLE_WORKSPACE -> SelectorLookupRejection.WORKSPACE_MISMATCH
        CanonicalSelectorDecodingFailure.STALE_AUTHORITY,
        CanonicalSelectorDecodingFailure.INCOMPATIBLE_AUTHORITY,
        CanonicalSelectorDecodingFailure.LIVE_AUTHORITY_REQUIRED -> SelectorLookupRejection.STALE
        else ->
            if (
                token.value.startsWith(if (exact) "candidate:" else "exact:") ||
                    token.value.startsWith("source-selector-")
            )
                SelectorLookupRejection.WRONG_KIND
            else SelectorLookupRejection.MALFORMED
    }
