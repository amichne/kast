package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.AdmittedDiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.AdmittedQueryRunRejection
import io.github.amichne.kast.protocol.contract.AdmittedRelationReadRejection
import io.github.amichne.kast.protocol.contract.AdmittedSourceReadRejection
import io.github.amichne.kast.protocol.contract.AdmittedTraversalRunRejection
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRejection
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.QueryRunFailure
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.RelationReadFailure
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadFailure
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.reason

/** Typed binding of a report to one operation's closed rejection family. */
internal class RejectionBudgetCodec<Value>(
    val project: (Value) -> ExecutionBudgetPresence = { ExecutionBudgetPresence.Absent },
    val admit: (Value, ExecutionBudgetReport) -> WireDocumentConversion<Value> = { _, _ ->
        WireDocumentConversion.Rejected
    },
)

internal object ReadRejectionBudgets {
    val diagnostic =
        RejectionBudgetCodec<DiagnosticCheckFailure>(
            { value ->
                when (value) {
                    is DiagnosticCheckRejection -> ExecutionBudgetPresence.Absent
                    is AdmittedDiagnosticCheckRejection -> ExecutionBudgetPresence.Present(value.executionBudget)
                }
            },
            { reason, report ->
                WireDocumentConversion.Converted(AdmittedDiagnosticCheckRejection(reason.reason(), report))
            },
        )

    val source =
        RejectionBudgetCodec<SourceReadFailure>(
            {
                when (it) {
                    is io.github.amichne.kast.protocol.contract.SourceReadCause -> ExecutionBudgetPresence.Absent
                    is AdmittedSourceReadRejection -> ExecutionBudgetPresence.Present(it.executionBudget)
                }
            },
            { reason, report ->
                WireDocumentConversion.Converted(AdmittedSourceReadRejection(reason.reason(), report))
            },
        )
    val relation =
        RejectionBudgetCodec<RelationReadFailure>(
            {
                when (it) {
                    is RelationReadRejection -> ExecutionBudgetPresence.Absent
                    is AdmittedRelationReadRejection -> ExecutionBudgetPresence.Present(it.executionBudget)
                }
            },
            { reason, report ->
                WireDocumentConversion.Converted(AdmittedRelationReadRejection(reason.reason(), report))
            },
        )
    val traversal =
        RejectionBudgetCodec<TraversalRunFailure>(
            {
                when (it) {
                    is TraversalRunRejection -> ExecutionBudgetPresence.Absent
                    is AdmittedTraversalRunRejection -> ExecutionBudgetPresence.Present(it.executionBudget)
                }
            },
            { reason, report ->
                WireDocumentConversion.Converted(AdmittedTraversalRunRejection(reason.reason(), report))
            },
        )
    val query =
        RejectionBudgetCodec<QueryRunFailure>(
            {
                when (it) {
                    is QueryRunRejection -> ExecutionBudgetPresence.Absent
                    is AdmittedQueryRunRejection -> ExecutionBudgetPresence.Present(it.executionBudget)
                }
            },
            { reason, report -> WireDocumentConversion.Converted(AdmittedQueryRunRejection(reason.reason(), report)) },
        )
}
