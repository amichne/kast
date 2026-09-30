package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement

/** Pure callers commit after projection; the host commits after final freshness, drainage and deadline admission. */
fun interface DiagnosticExecutionPublication {
    fun prepare(
        store: DiagnosticCheckpointStore,
        claim: DiagnosticExecutionClaim,
        page: DiagnosticPublishedPage,
    ): DiagnosticExecutionPublicationResult

    data object Immediate : DiagnosticExecutionPublication {
        override fun prepare(
            store: DiagnosticCheckpointStore,
            claim: DiagnosticExecutionClaim,
            page: DiagnosticPublishedPage,
        ) =
            when (val committed = store.commit(claim, page)) {
                is Refinement.Refined -> DiagnosticExecutionPublicationResult.COMMITTED
                is Refinement.Rejected -> DiagnosticExecutionPublicationResult.Rejected(committed.failure.rejection())
            }
    }
}

sealed interface DiagnosticExecutionPublicationResult {
    data object COMMITTED : DiagnosticExecutionPublicationResult

    data object PREPARED : DiagnosticExecutionPublicationResult

    data class Rejected(val reason: io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection) :
        DiagnosticExecutionPublicationResult
}
