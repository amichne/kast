package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactProducerExecutionFailureDocument
import io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.relation.contract.ValueProducerExecutionFailure
import io.github.amichne.kast.relation.contract.ValueProducerSeedDiagnostic
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead

internal fun ValueProducerSeedRead.Rejected.producerFailure(position: Int): Refinement.Rejected<QueryRunRejection> =
    when (val diagnostic = diagnostic) {
        ValueProducerSeedDiagnostic.NotAcquired -> impactFailure(cause.impactFailure(), position)
        is ValueProducerSeedDiagnostic.InitialExecution ->
            when (val projected = diagnostic.impactDocument(cause.impactFailure(), position)) {
                is Refinement.Refined -> Refinement.Rejected(QueryRunRejection.ImpactSourceRejected(projected.value))
                is Refinement.Rejected -> Refinement.Rejected(projected.failure.presentationRejection())
            }
    }

private fun ValueProducerSeedDiagnostic.InitialExecution.impactDocument(
    cause: io.github.amichne.kast.protocol.contract.QueryImpactSourceFailureCode,
    position: Int,
): ImpactProjected<QueryImpactSourceFailureDocument.ProducerExecution> =
    enclosing.impactDeclaration().impactZip(anchor.impactDocument()).impactZip(failure.impactDocument()).impactMap {
        (site, failure) ->
        QueryImpactSourceFailureDocument.ProducerExecution(
            cause,
            queryPosition(position),
            site.first,
            site.second,
            failure,
        )
    }

private fun ValueProducerExecutionFailure.impactDocument(): ImpactProjected<ImpactProducerExecutionFailureDocument> =
    when (this) {
        is ValueProducerExecutionFailure.Native ->
            Refinement.Refined(ImpactProducerExecutionFailureDocument.Native(cause.impactDocument()))
        is ValueProducerExecutionFailure.Unsupported ->
            callbackFailures
                .impactEach { it.impactDocument() }
                .impactMap { ImpactProducerExecutionFailureDocument.Unsupported(cause.impactDocument(), it) }
    }
