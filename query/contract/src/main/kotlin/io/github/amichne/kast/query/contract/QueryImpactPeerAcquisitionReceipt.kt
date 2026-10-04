package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

/** Completed peer effect facts. The adapter calls this only after final read freshness and completion. */
class QueryImpactPeerAcquisitionReceipt
private constructor(
    val completedAuthority: SemanticReadAuthority,
    val grant: RelationBudget,
    val examinedWorkUnits: RelationWorkCount,
    val elapsed: QueryImpactPeerElapsedNanos,
) {
    companion object {
        fun fromCompletedRead(
            completedAuthority: SemanticReadAuthority,
            grant: RelationBudget,
            examinedWorkUnits: RelationWorkCount,
            elapsed: QueryImpactPeerElapsedNanos,
        ): Refinement<QueryImpactPeerAcquisitionReceipt, QueryImpactPeerProofFailure> {
            if (examinedWorkUnits.value > grant.resources.workUnitLimit.value)
                return Refinement.Rejected(QueryImpactPeerProofFailure.WORK_RECEIPT_EXCEEDS_GRANT)
            val millis =
                elapsed.value / NANOS_PER_MILLISECOND + if (elapsed.value % NANOS_PER_MILLISECOND == 0L) 0L else 1L
            if (millis > grant.resources.elapsedTimeLimit.value)
                return Refinement.Rejected(QueryImpactPeerProofFailure.TIME_RECEIPT_EXCEEDS_GRANT)
            return Refinement.Refined(
                QueryImpactPeerAcquisitionReceipt(completedAuthority, grant, examinedWorkUnits, elapsed)
            )
        }
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
