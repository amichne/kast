package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.SourceReadQualification
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunResult

internal typealias HostedSourceOutcome =
    OperationOutcome<SourceReadResult, SourceReadQualification, SourceReadRejection>

internal fun HostedSourceOutcome.withSourceBudget(report: ExecutionBudgetReport?): HostedSourceOutcome =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(evidence.copy(payload = evidence.payload.copy(executionBudget = report)))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(payload = evidence.payload.copy(executionBudget = report)),
                qualification,
            )
        is OperationOutcome.Rejected -> this
    }

internal typealias HostedTraversalOutcome =
    OperationOutcome<TraversalRunResult, TraversalRunQualification, TraversalRunRejection>

internal fun HostedTraversalOutcome.withTraversalBudget(report: ExecutionBudgetReport?): HostedTraversalOutcome =
    when (this) {
        is OperationOutcome.Complete ->
            OperationOutcome.Complete(evidence.copy(payload = evidence.payload.copy(executionBudget = report)))
        is OperationOutcome.Qualified ->
            OperationOutcome.Qualified(
                evidence.copy(payload = evidence.payload.copy(executionBudget = report)),
                qualification,
            )
        is OperationOutcome.Rejected -> this
    }
