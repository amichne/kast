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
