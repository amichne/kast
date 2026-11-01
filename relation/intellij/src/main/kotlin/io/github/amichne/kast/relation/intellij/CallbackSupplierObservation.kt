package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Finite native supplier rejection vocabulary; no source payload or guessed success. */
internal fun CallbackInvocationFlowCause.supplierTermination(): IntellijReadTermination =
    when (this) {
        CallbackInvocationFlowCause.FINALLY_UNSUPPORTED -> IntellijReadTermination.CALLBACK_SUPPLIER_FINALLY_UNSUPPORTED
        CallbackInvocationFlowCause.ABRUPT_COMPLETION -> IntellijReadTermination.CALLBACK_SUPPLIER_ABRUPT_COMPLETION
        CallbackInvocationFlowCause.STORED_CALLBACK -> IntellijReadTermination.CALLBACK_SUPPLIER_STORED_CALLBACK
        CallbackInvocationFlowCause.RETURNED_CALLBACK -> IntellijReadTermination.CALLBACK_SUPPLIER_RETURNED_CALLBACK
        CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY ->
            IntellijReadTermination.CALLBACK_SUPPLIER_UNSUPPORTED_CALLBACK_SUPPLY
        CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE ->
            IntellijReadTermination.CALLBACK_SUPPLIER_ANONYMOUS_IDENTITY_UNAVAILABLE
        CallbackInvocationFlowCause.SUPPLIER_TARGET_MISMATCH ->
            IntellijReadTermination.CALLBACK_SUPPLIER_TARGET_MISMATCH
        CallbackInvocationFlowCause.SUPPLIER_INVOCATION_UNAVAILABLE ->
            IntellijReadTermination.CALLBACK_SUPPLIER_INVOCATION_UNAVAILABLE
        CallbackInvocationFlowCause.SUPPLIER_INVOCATION_MISMATCH ->
            IntellijReadTermination.CALLBACK_SUPPLIER_INVOCATION_MISMATCH
        CallbackInvocationFlowCause.SUPPLIER_BINDING_REJECTED ->
            IntellijReadTermination.CALLBACK_SUPPLIER_BINDING_REJECTED
        CallbackInvocationFlowCause.SUPPLIER_ARGUMENT_UNAVAILABLE ->
            IntellijReadTermination.CALLBACK_SUPPLIER_ARGUMENT_UNAVAILABLE
        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING ->
            IntellijReadTermination.CALLBACK_SUPPLIER_UNRESOLVED_ARGUMENT_MAPPING
        CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE ->
            IntellijReadTermination.CALLBACK_SUPPLIER_UNRESOLVED_PARAMETER_REFERENCE
        CallbackInvocationFlowCause.EXTERNAL_CALLABLE -> IntellijReadTermination.CALLBACK_SUPPLIER_EXTERNAL_CALLABLE
        CallbackInvocationFlowCause.OUTSIDE_DOMAIN -> IntellijReadTermination.CALLBACK_SUPPLIER_OUTSIDE_DOMAIN
        CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN ->
            IntellijReadTermination.CALLBACK_DEPENDENCY_DEFERRED_MODEL_UNPROVEN
        CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN ->
            IntellijReadTermination.CALLBACK_DEPENDENCY_CONTRACT_EFFECT_UNPROVEN
        CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE ->
            IntellijReadTermination.CALLBACK_DEPENDENCY_BINARY_UNAVAILABLE
        CallbackInvocationFlowCause.DEPENDENCY_BINARY_CHANGED ->
            IntellijReadTermination.CALLBACK_DEPENDENCY_BINARY_CHANGED
        CallbackInvocationFlowCause.DEPENDENCY_TARGET_UNAVAILABLE ->
            IntellijReadTermination.CALLBACK_DEPENDENCY_TARGET_UNAVAILABLE
        CallbackInvocationFlowCause.PARAMETER_ESCAPES -> IntellijReadTermination.CALLBACK_SUPPLIER_PARAMETER_ESCAPES
        CallbackInvocationFlowCause.CALLBACK_CYCLE -> IntellijReadTermination.CALLBACK_SUPPLIER_CALLBACK_CYCLE
        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION ->
            IntellijReadTermination.CALLBACK_SUPPLIER_NESTED_CALLBACK_EXECUTION
        CallbackInvocationFlowCause.NO_INVOCATION_PROVEN ->
            IntellijReadTermination.CALLBACK_SUPPLIER_NO_INVOCATION_PROVEN
        CallbackInvocationFlowCause.WORK_LIMIT_REACHED -> IntellijReadTermination.CALLBACK_SUPPLIER_WORK_LIMIT_REACHED
        CallbackInvocationFlowCause.TIME_LIMIT_REACHED -> IntellijReadTermination.CALLBACK_SUPPLIER_TIME_LIMIT_REACHED
        CallbackInvocationFlowCause.RESULT_LIMIT_REACHED ->
            IntellijReadTermination.CALLBACK_SUPPLIER_RESULT_LIMIT_REACHED
        CallbackInvocationFlowCause.BYTE_LIMIT_REACHED -> IntellijReadTermination.CALLBACK_SUPPLIER_BYTE_LIMIT_REACHED
    }
