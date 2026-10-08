package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.core.BrokerFailure
import io.github.amichne.kast.appserver.core.BrokerLimit
import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.core.ProviderFailureCode
import io.github.amichne.kast.protocol.registry.OperationEffect

/** Only an output-contract rejection after ProviderCall.Completed can use this effect proof. */
internal fun BrokerFailure.OutputContractRejected.certainty(): InvocationCertainty =
    when (val settled = settledEffect) {
        BrokerOperationEffect.Unknown -> InvocationCertainty.UNCERTAIN
        is BrokerOperationEffect.Canonical ->
            when (settled.effect) {
                OperationEffect.NONE,
                OperationEffect.INTELLIJ_READ -> InvocationCertainty.KNOWN
                OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
                OperationEffect.INTELLIJ_WRITE,
                OperationEffect.FILESYSTEM_WRITE,
                OperationEffect.PERSISTENCE_WRITE,
                OperationEffect.WORKSPACE_MODEL_WRITE,
                OperationEffect.PROCESS_CONTROL -> InvocationCertainty.UNCERTAIN
            }
    }

internal fun BrokerFailure.certainty(): InvocationCertainty =
    when (this) {
        is BrokerFailure.WorkspacePreparationRejected,
        is BrokerFailure.UnknownNamespace,
        is BrokerFailure.UnknownTool,
        is BrokerFailure.InvalidArguments,
        is BrokerFailure.ProviderStartupRejected -> InvocationCertainty.KNOWN
        is BrokerFailure.Overloaded ->
            if (limit == BrokerLimit.MAXIMUM_TOOL_RESULT_BYTES) InvocationCertainty.UNCERTAIN
            else InvocationCertainty.KNOWN
        is BrokerFailure.OutputContractRejected -> certainty()
        is BrokerFailure.ProviderInvocationRejected ->
            when (code) {
                ProviderFailureCode.WORKSPACE_START_UNAVAILABLE,
                ProviderFailureCode.WORKSPACE_START_NOT_DIRECTORY,
                ProviderFailureCode.WORKSPACE_ROOT_MARKER_NOT_FOUND,
                ProviderFailureCode.WORKSPACE_INVALID_ROOT_MARKER -> InvocationCertainty.KNOWN
                ProviderFailureCode.UNEXPECTED_FAILURE,
                ProviderFailureCode.IDE_CONFIGURATION_REJECTED,
                ProviderFailureCode.IDE_INVALID_NAME,
                ProviderFailureCode.IDE_INVALID_REQUEST,
                ProviderFailureCode.IDE_HOST_UNAVAILABLE,
                ProviderFailureCode.IDE_DESCRIPTOR_REJECTED,
                ProviderFailureCode.IDE_RESPONSE_REJECTED,
                ProviderFailureCode.IDE_REQUEST_TOO_LARGE,
                ProviderFailureCode.IDE_DEADLINE_EXCEEDED,
                ProviderFailureCode.IDE_TRANSPORT_REJECTED,
                ProviderFailureCode.IDE_SCHEMA_UNAVAILABLE,
                ProviderFailureCode.IDE_COMPATIBILITY_REJECTED,
                ProviderFailureCode.IDE_OPERATION_UNSUPPORTED,
                ProviderFailureCode.IDE_APPROVAL_REQUIRED,
                ProviderFailureCode.IDE_APPROVAL_REJECTED,
                ProviderFailureCode.TIMED_OUT,
                ProviderFailureCode.IO_REJECTED,
                ProviderFailureCode.OUTPUT_LIMIT,
                ProviderFailureCode.SPAWN_FAILED,
                ProviderFailureCode.TERMINATED,
                ProviderFailureCode.KAST_QUALIFICATION_FAILED,
                ProviderFailureCode.KAST_CONTRACT_CHANGED,
                ProviderFailureCode.KAST_ARGUMENT_NOT_SCALAR,
                ProviderFailureCode.MALFORMED_KAST_OUTPUT,
                ProviderFailureCode.APPROVAL_REQUIRED,
                ProviderFailureCode.APPROVAL_BINDING_REJECTED,
                ProviderFailureCode.GRADLE_WRAPPER_UNAVAILABLE -> InvocationCertainty.UNCERTAIN
            }
        is BrokerFailure.InvocationCancelled -> InvocationCertainty.UNCERTAIN
    }
