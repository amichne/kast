package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.*
import io.github.amichne.kast.protocol.contract.*
import io.github.amichne.kast.query.protocol.*
import io.github.amichne.kast.runtime.server.OperationHandler
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.source.contract.SourceSelectorToken
import io.github.amichne.kast.source.contract.SourceSelectorTokenCodec
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceTextByteLimit

internal class CanonicalSourceReadHandler(
    operations: SourceReadOperations,
    private val authority: CanonicalProtocolAuthority,
) : OperationHandler<SourceReadRequest, SourceReadResult, SourceReadQualification, SourceReadRejection> {
    private val protocol = CanonicalSourceReadProtocol(operations, authority)
    override suspend fun execute(request: SourceReadRequest): OperationOutcome<SourceReadResult, SourceReadQualification, SourceReadRejection> {
        val current = when (val anchor = request.anchor) {
            is SourceReadAnchorDocument.Candidate -> (authority.candidate(anchor.selector) as? CandidateSelectorLookup.Found)?.selector?.lease
            is SourceReadAnchorDocument.Symbol -> (authority.exact(anchor.selector) as? ExactSelectorLookup.Found)?.selector?.lease
            is SourceReadAnchorDocument.Source -> SourceSelectorToken.parse(anchor.selector.value).valueOrNull()?.let {
                SourceSelectorTokenCodec.decode(it).valueOrNull()?.snapshot?.lease
            }
        } ?: return OperationOutcome.Rejected(when (request.anchor) {
            is SourceReadAnchorDocument.Candidate -> SourceReadRejection.CANDIDATE_STALE
            is SourceReadAnchorDocument.Symbol -> SourceReadRejection.STALE_GENERATION
            is SourceReadAnchorDocument.Source -> SourceReadRejection.SOURCE_SELECTOR_STALE
        })
        val entities = SourceEntityLimit.parse(request.entityLimit.value).valueOrNull()
            ?: return OperationOutcome.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
        val bytes = SourceTextByteLimit.parse(request.textByteLimit.value).valueOrNull()
            ?: return OperationOutcome.Rejected(SourceReadRejection.CONTRACT_VIOLATION)
        return protocol.execute(request, current, SourceProtocolBudget(entities, bytes))
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
