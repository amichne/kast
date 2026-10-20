package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.protocol.QueryInvocationFactsRetentionEstimate
import io.github.amichne.kast.query.protocol.QueryInvocationRetentionAdmission
import io.github.amichne.kast.query.protocol.QueryInvocationRetentionObservation
import io.github.amichne.kast.query.protocol.QueryInvocationRetentionStage
import io.github.amichne.kast.query.protocol.QueryPublishedPage
import io.github.amichne.kast.relation.contract.RelationProviderRetentionLedger
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull

/** Publication failure stays distinct from the native transfer assertion. */
internal class NativePacketT1PublicationCapture : QueryInvocationRetentionObservation {
    val admissions = mutableListOf<QueryInvocationRetentionAdmission>()
    val facts = mutableListOf<Facts>()
    var outcome: QueryPublishedPage? = null
        private set

    override fun observe(admission: QueryInvocationRetentionAdmission) {
        admissions += admission
        println(
            "T1_NATIVE_ONLY_RETENTION stage=${admission.stage} allowance=${admission.allowance.value} " +
                "facts=${admission.facts.value} issued=${admission.issuedState.value} " +
                "request=${admission.request.value} total=${admission.total.value} " +
                "capacity=${admission.capacity::class.simpleName}"
        )
    }

    override fun observeFacts(
        stage: QueryInvocationRetentionStage,
        estimate: QueryInvocationFactsRetentionEstimate,
        inventoryOwners: RelationProviderRetentionLedger,
    ) {
        facts += Facts(stage, estimate, inventoryOwners)
        println(
            "T1_NATIVE_ONLY_ACCEPTED_FACTS stage=$stage semantic=${estimate.semantic.value} " +
                "preview=${estimate.preview.value} rowReferences=${estimate.rowReferenceCells.value}"
        )
    }

    fun record(value: QueryPublishedPage) {
        check(outcome == null) { "The original request must use exactly one public run" }
        outcome = value
        when (value) {
            is OperationOutcome.Complete -> println("T1_NATIVE_ONLY_PUBLIC outcome=COMPLETE")
            is OperationOutcome.Qualified -> println("T1_NATIVE_ONLY_PUBLIC outcome=QUALIFIED")
            is OperationOutcome.Rejected -> recordRejection(value.reason)
        }
    }

    fun assertExpectedPublicQualification() {
        val actual = checkNotNull(outcome)
        check(actual is OperationOutcome.Rejected) { "Opaque T1 cannot claim public strict completion" }
        when (val reason = actual.reason) {
            is QueryRunRejection.ExecutionRejected ->
                assertEquals(QueryExecutionRejectionDocument.INVOCATION_RETAINED_BYTES_LIMIT, reason.reason)
            is QueryRunRejection.CompletionUnproven -> assertNull(reason.originalFailure)
            else -> error("Unexpected public rejection: ${reason::class.simpleName}")
        }
    }

    private fun recordRejection(reason: QueryRunRejection) {
        when (reason) {
            is QueryRunRejection.ExecutionRejected ->
                println("T1_NATIVE_ONLY_PUBLIC outcome=REJECTED type=ExecutionRejected reason=${reason.reason}")
            is QueryRunRejection.CompletionUnproven -> {
                val unavailable = (reason.evidence as? QueryCompletionEvidenceDocument.Unavailable)?.cause
                println(
                    "T1_NATIVE_ONLY_PUBLIC outcome=REJECTED type=CompletionUnproven cause=${reason.reason} " +
                        "stop=${reason.stop} evidence=${reason.evidence::class.simpleName} unavailable=$unavailable " +
                        "originalFailure=${reason.originalFailure?.let { failure -> failure::class.simpleName }}"
                )
            }
            else -> println("T1_NATIVE_ONLY_PUBLIC outcome=REJECTED unexpected=${reason::class.simpleName}")
        }
    }

    data class Facts(
        val stage: QueryInvocationRetentionStage,
        val estimate: QueryInvocationFactsRetentionEstimate,
        val inventoryOwners: RelationProviderRetentionLedger,
    )
}
