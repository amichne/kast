package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueCallbackInputFailure
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowObligationFailure
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite

/** A broad limitation accompanies, never replaces, the exact callback-reader or graph rejection. */
internal fun nativeCallbackInputObligation(
    source: ValueSite,
    failure: NativeCallbackInputFailure,
    element: PsiElement,
): Refinement<ValueFlowObligation, ValueFlowObligationFailure> {
    val detail =
        when (failure) {
            is NativeCallbackInputFailure.Value ->
                return Refinement.Refined(nativeValueObligation(source, failure.cause, element))
            is NativeCallbackInputFailure.Callback -> ValueCallbackInputFailure.Callback(failure.cause)
            is NativeCallbackInputFailure.Contract -> ValueCallbackInputFailure.Contract(failure.cause)
            is NativeCallbackInputFailure.Graph -> ValueCallbackInputFailure.Graph(failure.cause)
        }
    return ValueFlowObligation.fromCallbackInput(source, detail, detail.nativeCallbackInputCause())
}

internal fun ValueCallbackInputFailure.nativeCallbackInputCause(): ValueFlowUnsupportedCause =
    when (this) {
        is ValueCallbackInputFailure.ImplementationRejected -> ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE
        is ValueCallbackInputFailure.ImplementationIncomplete -> implementationCapacityCause(dispatch.coverage)
        is ValueCallbackInputFailure.Callback -> cause.nativeCallbackInputCause()
        is ValueCallbackInputFailure.Contract -> ValueFlowUnsupportedCause.NESTED_EXECUTION
        is ValueCallbackInputFailure.Graph -> cause.nativeCallbackInputCause()
    }

private fun StaticCallbackGraphFailure.nativeCallbackInputCause(): ValueFlowUnsupportedCause =
    when (this) {
        is StaticCallbackGraphFailure.Unavailable -> cause.nativeCallbackInputCause()
        is StaticCallbackGraphFailure.ImmutableUnresolved -> flow.obligations.callbackInputCapacityCause()
        is StaticCallbackGraphFailure.Unresolved -> flow.obligations.callbackInputCapacityCause()
        is StaticCallbackGraphFailure.UnprovenPolicy,
        is StaticCallbackGraphFailure.InvalidFlow,
        StaticCallbackGraphFailure.CyclicRoute,
        StaticCallbackGraphFailure.IncompleteScan,
        StaticCallbackGraphFailure.UnsupportedDefaultSupply,
        StaticCallbackGraphFailure.MissingNamedOwner,
        StaticCallbackGraphFailure.SupplierIdentityMismatch,
        StaticCallbackGraphFailure.OutsideWorkspace,
        StaticCallbackGraphFailure.NonCallableTarget -> ValueFlowUnsupportedCause.NESTED_EXECUTION
    }

private fun Set<CallbackInvocationFlowCause>.callbackInputCapacityCause(): ValueFlowUnsupportedCause =
    when {
        CallbackInvocationFlowCause.WORK_LIMIT_REACHED in this -> ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
        CallbackInvocationFlowCause.TIME_LIMIT_REACHED in this -> ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
        CallbackInvocationFlowCause.RESULT_LIMIT_REACHED in this -> ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED
        CallbackInvocationFlowCause.BYTE_LIMIT_REACHED in this -> ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED
        else -> ValueFlowUnsupportedCause.NESTED_EXECUTION
    }

private fun CallbackInvocationFlowCause.nativeCallbackInputCause(): ValueFlowUnsupportedCause =
    when (this) {
        CallbackInvocationFlowCause.WORK_LIMIT_REACHED -> ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
        CallbackInvocationFlowCause.TIME_LIMIT_REACHED -> ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
        CallbackInvocationFlowCause.RESULT_LIMIT_REACHED -> ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED
        CallbackInvocationFlowCause.BYTE_LIMIT_REACHED -> ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED
        CallbackInvocationFlowCause.FINALLY_UNSUPPORTED -> ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED
        CallbackInvocationFlowCause.ABRUPT_COMPLETION -> ValueFlowUnsupportedCause.ABRUPT_COMPLETION
        CallbackInvocationFlowCause.OUTSIDE_DOMAIN -> ValueFlowUnsupportedCause.OUTSIDE_DOMAIN
        CallbackInvocationFlowCause.STORED_CALLBACK,
        CallbackInvocationFlowCause.RETURNED_CALLBACK,
        CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
        CallbackInvocationFlowCause.ANONYMOUS_IDENTITY_UNAVAILABLE,
        CallbackInvocationFlowCause.UNRESOLVED_ARGUMENT_MAPPING,
        CallbackInvocationFlowCause.SUPPLIER_TARGET_MISMATCH,
        CallbackInvocationFlowCause.SUPPLIER_INVOCATION_UNAVAILABLE,
        CallbackInvocationFlowCause.SUPPLIER_INVOCATION_MISMATCH,
        CallbackInvocationFlowCause.SUPPLIER_BINDING_REJECTED,
        CallbackInvocationFlowCause.SUPPLIER_ARGUMENT_UNAVAILABLE,
        CallbackInvocationFlowCause.UNRESOLVED_PARAMETER_REFERENCE,
        CallbackInvocationFlowCause.EXTERNAL_CALLABLE,
        CallbackInvocationFlowCause.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN,
        CallbackInvocationFlowCause.DEPENDENCY_DEFERRED_MODEL_UNPROVEN,
        CallbackInvocationFlowCause.DEPENDENCY_BINARY_UNAVAILABLE,
        CallbackInvocationFlowCause.DEPENDENCY_BINARY_CHANGED,
        CallbackInvocationFlowCause.DEPENDENCY_TARGET_UNAVAILABLE,
        CallbackInvocationFlowCause.PARAMETER_ESCAPES,
        CallbackInvocationFlowCause.CALLBACK_CYCLE,
        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
        CallbackInvocationFlowCause.NO_INVOCATION_PROVEN -> ValueFlowUnsupportedCause.NESTED_EXECUTION
    }

private fun implementationCapacityCause(
    coverage: io.github.amichne.kast.relation.contract.ValueImplementationCoverage
): ValueFlowUnsupportedCause =
    when (coverage) {
        is io.github.amichne.kast.relation.contract.ValueImplementationCoverage.Exhausted ->
            ValueFlowUnsupportedCause.NESTED_EXECUTION
        is io.github.amichne.kast.relation.contract.ValueImplementationCoverage.Incomplete ->
            implementationCapacityCause(coverage.evidence.limitations.map { it.nativeImplementationCause() }.toSet())
    }

private fun implementationCapacityCause(causes: Set<ValueFlowUnsupportedCause>): ValueFlowUnsupportedCause =
    when {
        ValueFlowUnsupportedCause.WORK_LIMIT_REACHED in causes -> ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
        ValueFlowUnsupportedCause.TIME_LIMIT_REACHED in causes -> ValueFlowUnsupportedCause.TIME_LIMIT_REACHED
        ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED in causes -> ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED
        ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED in causes -> ValueFlowUnsupportedCause.BYTE_LIMIT_REACHED
        else -> ValueFlowUnsupportedCause.NESTED_EXECUTION
    }
