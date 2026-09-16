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

    /** Fresh read acquisition may perform one bounded compiler lookup; restoration never does. */
    suspend fun acquireExact(
        token: ProtocolText,
        current: SemanticReadAuthority,
    ): CanonicalSelectorDecoding<SymbolSelector> = restoreExact(token, current)

    fun remainingReadBudget(
        budget: io.github.amichne.kast.kernel.ResourceBudget
    ): Refinement<io.github.amichne.kast.kernel.ResourceBudget, ReadReacquisitionBudgetFailure> =
        Refinement.Refined(budget)

    fun readAcquisitions(): io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null

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

internal fun CanonicalSelectorDecodingFailure.lookupRejection(
    token: ProtocolText,
    exact: Boolean,
): SelectorLookupRejection =
    when (this) {
        CanonicalSelectorDecodingFailure.REVALIDATION_WRONG_KIND -> SelectorLookupRejection.REVALIDATION_WRONG_KIND
        CanonicalSelectorDecodingFailure.REVALIDATION_UNRETAINED -> SelectorLookupRejection.REVALIDATION_UNRETAINED
        CanonicalSelectorDecodingFailure.REVALIDATION_EXPIRED -> SelectorLookupRejection.REVALIDATION_EXPIRED
        CanonicalSelectorDecodingFailure.REVALIDATION_CAPACITY -> SelectorLookupRejection.REVALIDATION_CAPACITY
        CanonicalSelectorDecodingFailure.REVALIDATION_WORK_LIMIT_REACHED ->
            SelectorLookupRejection.REVALIDATION_WORK_LIMIT_REACHED
        CanonicalSelectorDecodingFailure.REVALIDATION_TIME_LIMIT_REACHED ->
            SelectorLookupRejection.REVALIDATION_TIME_LIMIT_REACHED
        CanonicalSelectorDecodingFailure.REVALIDATION_RETIRED -> SelectorLookupRejection.REVALIDATION_RETIRED
        CanonicalSelectorDecodingFailure.REVALIDATION_CAPTURE_UNAVAILABLE ->
            SelectorLookupRejection.REVALIDATION_CAPTURE_UNAVAILABLE
        CanonicalSelectorDecodingFailure.REVALIDATION_WORKSPACE_MISMATCH ->
            SelectorLookupRejection.REVALIDATION_WORKSPACE_MISMATCH
        CanonicalSelectorDecodingFailure.REVALIDATION_OWNER_MISMATCH ->
            SelectorLookupRejection.REVALIDATION_OWNER_MISMATCH
        CanonicalSelectorDecodingFailure.REVALIDATION_WORKSPACE_NOT_READY ->
            SelectorLookupRejection.REVALIDATION_WORKSPACE_NOT_READY
        CanonicalSelectorDecodingFailure.REVALIDATION_BASIS_MOVED -> SelectorLookupRejection.REVALIDATION_BASIS_MOVED
        CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_CHANGED ->
            SelectorLookupRejection.REVALIDATION_CONTENT_CHANGED
        CanonicalSelectorDecodingFailure.REVALIDATION_CONTENT_UNCOMMITTED ->
            SelectorLookupRejection.REVALIDATION_CONTENT_UNCOMMITTED
        CanonicalSelectorDecodingFailure.REVALIDATION_SCOPE_REJECTED ->
            SelectorLookupRejection.REVALIDATION_SCOPE_REJECTED
        CanonicalSelectorDecodingFailure.REVALIDATION_DECLARATION_MISSING ->
            SelectorLookupRejection.REVALIDATION_DECLARATION_MISSING
        CanonicalSelectorDecodingFailure.REVALIDATION_UNSUPPORTED_DECLARATION ->
            SelectorLookupRejection.REVALIDATION_UNSUPPORTED_DECLARATION
        CanonicalSelectorDecodingFailure.REVALIDATION_AMBIGUOUS -> SelectorLookupRejection.REVALIDATION_AMBIGUOUS
        CanonicalSelectorDecodingFailure.REVALIDATION_COMPILER_IDENTITY_CHANGED ->
            SelectorLookupRejection.REVALIDATION_COMPILER_IDENTITY_CHANGED
        CanonicalSelectorDecodingFailure.REVALIDATION_COMPILER_UNAVAILABLE ->
            SelectorLookupRejection.REVALIDATION_COMPILER_UNAVAILABLE

        CanonicalSelectorDecodingFailure.INCOMPATIBLE_WORKSPACE -> SelectorLookupRejection.WORKSPACE_MISMATCH
        // Existing non-source lookup surfaces retain their compatibility classification.
        // SOURCE consumes the finite decoding cause directly.
        CanonicalSelectorDecodingFailure.UNAVAILABLE,
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

internal suspend fun QueryReferenceAuthority.acquireReadExact(
    token: ProtocolText,
    current: SemanticReadAuthority,
): ExactSelectorLookup =
    when (val acquired = acquireExact(token, current)) {
        is CanonicalSelectorDecoding.Decoded -> ExactSelectorLookup.Found(acquired.value)
        is CanonicalSelectorDecoding.Rejected ->
            ExactSelectorLookup.Rejected(acquired.failure.lookupRejection(token, true))
    }

/** Only first-page request admission receives these newly acquired capabilities. Continuations remain strict. */
internal suspend fun QueryReferenceAuthority.admitReadReferences(
    tokens: List<ProtocolText>,
    current: SemanticReadAuthority,
): QueryReferenceAuthority {
    val admitted = tokens.distinct().associateWith { acquireExact(it, current) }
    val strict = this
    return object : QueryReferenceAuthority by strict {
        override fun restoreExact(
            token: ProtocolText,
            current: SemanticReadAuthority,
        ): CanonicalSelectorDecoding<SymbolSelector> =
            if (current == admittedAuthority) admitted[token] ?: strict.restoreExact(token, current)
            else strict.restoreExact(token, current)

        private val admittedAuthority = current
    }
}

enum class ReadReacquisitionBudgetFailure {
    WORK_LIMIT_REACHED,
    TIME_LIMIT_REACHED,
}

fun ReadReacquisitionBudgetFailure.decodingFailure(): CanonicalSelectorDecodingFailure =
    when (this) {
        ReadReacquisitionBudgetFailure.WORK_LIMIT_REACHED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_WORK_LIMIT_REACHED
        ReadReacquisitionBudgetFailure.TIME_LIMIT_REACHED ->
            CanonicalSelectorDecodingFailure.REVALIDATION_TIME_LIMIT_REACHED
    }
