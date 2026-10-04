package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.query.contract.QueryPlanSyntax

internal fun QuerySyntaxAdmission.refinePlanSyntax(): Refinement<QueryPlanSyntax, QueryRunRejection> {
    return when (this) {
        is QuerySyntaxAdmission.Admitted -> Refinement.Refined(syntax)
        is QuerySyntaxAdmission.ReferenceRejected ->
            return Refinement.Rejected(QueryRunRejection.ReferenceRejected(queryPosition(position), reason))
        is QuerySyntaxAdmission.StepReferenceRejected ->
            return Refinement.Rejected(
                QueryRunRejection.StepReferenceRejected(
                    queryPosition(stepPosition),
                    queryPosition(referencePosition),
                    reason,
                )
            )
        QuerySyntaxAdmission.RequestRejected ->
            return Refinement.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.REQUEST_REJECTED)
            )
    }
}
