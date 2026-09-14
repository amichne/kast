package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.AdmittedSourceReadRejection
import io.github.amichne.kast.protocol.contract.AdmittedTraversalRunRejection
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.SourceReadFailure
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.reason

internal typealias HostedSourceOutcome = OperationOutcome<SourceReadResult, SourceReadQualification, SourceReadFailure>

internal fun HostedSourceOutcome.withSourceBudget(report: ExecutionBudgetReport?): HostedSourceOutcome =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(evidence.copy(payload = evidence.payload.copy(executionBudget = report)))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(payload = evidence.payload.copy(executionBudget = report)),
                qualification,
            )
        is OperationOutcome.Rejected ->
            if (report == null) this
            else OperationOutcome.Rejected(AdmittedSourceReadRejection(reason.reason(), report))
    }

internal typealias HostedTraversalOutcome =
    OperationOutcome<TraversalRunResult, TraversalRunQualification, TraversalRunFailure>

internal fun HostedTraversalOutcome.withTraversalBudget(report: ExecutionBudgetReport?): HostedTraversalOutcome =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(evidence.copy(payload = evidence.payload.copy(executionBudget = report)))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(payload = evidence.payload.copy(executionBudget = report)),
                qualification,
            )
        is OperationOutcome.Rejected ->
            if (report == null) this
            else OperationOutcome.Rejected(AdmittedTraversalRunRejection(reason.reason(), report))
    }
