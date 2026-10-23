package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStepFailure
import io.github.amichne.kast.relation.contract.ValueFlowSuspensionCause
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Diagnostic projection only: finite causes, no source payload, and no change to semantic evidence or counters. */
internal fun IntellijReadObservation.valueFlowRead(result: ValueFlowRead) {
    when (result) {
        is ValueFlowRead.Observed -> {
            result.step.obligations.forEach { terminated(it.cause.valueFlowTermination()) }
            when (result.step.terminal) {
                ValueFlowTerminal.SupportedDomainExhausted ->
                    terminated(IntellijReadTermination.VALUE_FLOW_SUPPORTED_DOMAIN_EXHAUSTED)
                ValueFlowTerminal.Unresolved,
                ValueFlowTerminal.ResourceSuspended -> Unit
            }
        }
        is ValueFlowRead.Suspended -> {
            result.step.obligations.forEach { terminated(it.cause.valueFlowTermination()) }
            terminated(result.cause.valueFlowTermination())
        }
        is ValueFlowRead.Rejected -> {
            count(IntellijReadCounter.VALUE_FLOW_REJECTIONS)
            terminated(result.cause.valueFlowTermination())
        }
        is ValueFlowRead.ContractRejected -> {
            count(IntellijReadCounter.VALUE_FLOW_REJECTIONS)
            terminated(result.cause.valueFlowTermination())
        }
    }
}

private fun ValueFlowUnsupportedCause.valueFlowTermination(): IntellijReadTermination =
    when (this) {
        ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED -> IntellijReadTermination.VALUE_FLOW_FINALLY_UNSUPPORTED
        ValueFlowUnsupportedCause.ABRUPT_COMPLETION -> IntellijReadTermination.VALUE_FLOW_ABRUPT_COMPLETION
        ValueFlowUnsupportedCause.EXTERNAL_CALL -> IntellijReadTermination.VALUE_FLOW_EXTERNAL_CALL
        ValueFlowUnsupportedCause.UNMODELED_CALL -> IntellijReadTermination.VALUE_FLOW_UNMODELED_CALL
        ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW -> IntellijReadTermination.VALUE_FLOW_MUTABLE_CONTROL_FLOW
        ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION -> IntellijReadTermination.VALUE_FLOW_UNSUPPORTED_EXPRESSION
        ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE -> IntellijReadTermination.VALUE_FLOW_UNRESOLVED_REFERENCE
        ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY -> IntellijReadTermination.VALUE_FLOW_UNSUPPORTED_PROPERTY
        ValueFlowUnsupportedCause.UNSUPPORTED_RETURN -> IntellijReadTermination.VALUE_FLOW_UNSUPPORTED_RETURN
        ValueFlowUnsupportedCause.NESTED_EXECUTION -> IntellijReadTermination.VALUE_FLOW_NESTED_EXECUTION
        ValueFlowUnsupportedCause.OUTSIDE_DOMAIN -> IntellijReadTermination.VALUE_FLOW_OUTSIDE_DOMAIN
        ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED -> IntellijReadTermination.VALUE_FLOW_RESULT_LIMIT_REACHED
        ValueFlowUnsupportedCause.WORK_LIMIT_REACHED -> IntellijReadTermination.VALUE_FLOW_WORK_LIMIT_REACHED
        ValueFlowUnsupportedCause.TIME_LIMIT_REACHED -> IntellijReadTermination.VALUE_FLOW_TIME_LIMIT_REACHED
        ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED -> IntellijReadTermination.VALUE_FLOW_BYTE_LIMIT_REACHED
        ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED ->
            IntellijReadTermination.VALUE_FLOW_ACCESS_PATH_DEPTH_EXCEEDED
        ValueFlowUnsupportedCause.CALL_CONTEXT_DEPTH_EXCEEDED ->
            IntellijReadTermination.VALUE_FLOW_CALL_CONTEXT_DEPTH_EXCEEDED
        ValueFlowUnsupportedCause.RECURSIVE_SOURCE_CALL -> IntellijReadTermination.VALUE_FLOW_RECURSIVE_SOURCE_CALL
    }

private fun ValueFlowRejection.valueFlowTermination(): IntellijReadTermination =
    when (this) {
        ValueFlowRejection.STALE_SITE -> IntellijReadTermination.VALUE_FLOW_REJECTED_STALE_SITE
        ValueFlowRejection.OUTSIDE_DOMAIN -> IntellijReadTermination.VALUE_FLOW_REJECTED_OUTSIDE_DOMAIN
        ValueFlowRejection.OWNER_UNAVAILABLE -> IntellijReadTermination.VALUE_FLOW_REJECTED_OWNER_UNAVAILABLE
        ValueFlowRejection.AUTHORITY_MOVED -> IntellijReadTermination.VALUE_FLOW_REJECTED_AUTHORITY_MOVED
        ValueFlowRejection.UNSUPPORTED_SEED -> IntellijReadTermination.VALUE_FLOW_REJECTED_UNSUPPORTED_SEED
        ValueFlowRejection.UNRESOLVED_SEED -> IntellijReadTermination.VALUE_FLOW_REJECTED_UNRESOLVED_SEED
        ValueFlowRejection.NATIVE_UNAVAILABLE -> IntellijReadTermination.VALUE_FLOW_REJECTED_NATIVE_UNAVAILABLE
        ValueFlowRejection.GRANT_TOO_SMALL -> IntellijReadTermination.VALUE_FLOW_REJECTED_GRANT_TOO_SMALL
        ValueFlowRejection.NESTED_EXECUTION -> IntellijReadTermination.VALUE_FLOW_REJECTED_NESTED_EXECUTION
    }

private fun ValueFlowStepFailure.valueFlowTermination(): IntellijReadTermination =
    when (this) {
        ValueFlowStepFailure.INVALID_PROGRESS -> IntellijReadTermination.VALUE_FLOW_CONTRACT_INVALID_PROGRESS
        ValueFlowStepFailure.WORK_LIMIT_EXCEEDED -> IntellijReadTermination.VALUE_FLOW_CONTRACT_WORK_LIMIT_EXCEEDED
        ValueFlowStepFailure.RESULT_LIMIT_EXCEEDED -> IntellijReadTermination.VALUE_FLOW_CONTRACT_RESULT_LIMIT_EXCEEDED
        ValueFlowStepFailure.DETACHED_CAPACITY_EXCEEDED ->
            IntellijReadTermination.VALUE_FLOW_CONTRACT_DETACHED_CAPACITY_EXCEEDED
        ValueFlowStepFailure.DOMAIN_MISMATCH -> IntellijReadTermination.VALUE_FLOW_CONTRACT_DOMAIN_MISMATCH
        ValueFlowStepFailure.SOURCE_MISMATCH -> IntellijReadTermination.VALUE_FLOW_CONTRACT_SOURCE_MISMATCH
        ValueFlowStepFailure.BASIS_MISMATCH -> IntellijReadTermination.VALUE_FLOW_CONTRACT_BASIS_MISMATCH
        ValueFlowStepFailure.UNRESOLVED_OBLIGATIONS ->
            IntellijReadTermination.VALUE_FLOW_CONTRACT_UNRESOLVED_OBLIGATIONS
        ValueFlowStepFailure.MISSING_OBLIGATION -> IntellijReadTermination.VALUE_FLOW_CONTRACT_MISSING_OBLIGATION
    }

private fun ValueFlowSuspensionCause.valueFlowTermination(): IntellijReadTermination =
    when (this) {
        ValueFlowSuspensionCause.WORK_LIMIT_REACHED -> IntellijReadTermination.VALUE_FLOW_SUSPENDED_WORK_LIMIT_REACHED
        ValueFlowSuspensionCause.RESULT_LIMIT_REACHED ->
            IntellijReadTermination.VALUE_FLOW_SUSPENDED_RESULT_LIMIT_REACHED
        ValueFlowSuspensionCause.BYTE_LIMIT_REACHED -> IntellijReadTermination.VALUE_FLOW_SUSPENDED_BYTE_LIMIT_REACHED
        ValueFlowSuspensionCause.TIME_LIMIT_REACHED -> IntellijReadTermination.VALUE_FLOW_SUSPENDED_TIME_LIMIT_REACHED
    }
