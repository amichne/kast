package io.github.amichne.kast.protocol.contract

internal fun ImpactFlowObligationEvidenceDocument.CallbackUnavailable.admitsUnavailable(
    site: ImpactValueSiteReferenceDocument,
    outerCause: ImpactFlowUnsupportedDocument,
): Boolean = cause.unavailableFlowCause() == outerCause && site.isCallbackRefusalSite()

internal fun ImpactFlowObligationEvidenceDocument.CallbackContractRejected.admitsContractRejection(
    site: ImpactValueSiteReferenceDocument,
    outerCause: ImpactFlowUnsupportedDocument,
): Boolean {
    val expected =
        if (cause == QueryCallbackFlowFailureDocument.PROOF_LIMIT_REACHED)
            ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED
        else ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN
    return outerCause == expected && site.isCallbackRefusalSite()
}

private fun ImpactValueSiteReferenceDocument.isCallbackRefusalSite(): Boolean =
    role is ImpactValueRoleDocument.FunctionArgument ||
        role == ImpactValueRoleDocument.Return &&
            (isEmptyAnonymousReturn() ||
                callStack.values.lastOrNull()?.let { frame ->
                    enclosing == frame.callee.enclosing &&
                        executionOwner == frame.callee.executionOwner &&
                        frame.target is ImpactCallTargetDocument.Callback
                } == true)

private fun ImpactValueSiteReferenceDocument.isEmptyAnonymousReturn(): Boolean =
    callStack.values.isEmpty() && executionOwner is ImpactExecutionOwnerDocument.Anonymous && admitsExecutionOwner()

private fun QueryCallbackFlowCauseDocument.unavailableFlowCause(): ImpactFlowUnsupportedDocument =
    when (this) {
        QueryCallbackFlowCauseDocument.FINALLY_UNSUPPORTED -> ImpactFlowUnsupportedDocument.FINALLY_UNSUPPORTED
        QueryCallbackFlowCauseDocument.ABRUPT_COMPLETION -> ImpactFlowUnsupportedDocument.ABRUPT_COMPLETION
        QueryCallbackFlowCauseDocument.EXTERNAL_CALLABLE -> ImpactFlowUnsupportedDocument.EXTERNAL_CALL
        QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN -> ImpactFlowUnsupportedDocument.OUTSIDE_DOMAIN
        QueryCallbackFlowCauseDocument.WORK_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.WORK_LIMIT_REACHED
        QueryCallbackFlowCauseDocument.TIME_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.TIME_LIMIT_REACHED
        QueryCallbackFlowCauseDocument.RESULT_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.RESULT_LIMIT_REACHED
        QueryCallbackFlowCauseDocument.BYTE_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.BYTE_LIMIT_REACHED
        QueryCallbackFlowCauseDocument.PROOF_LIMIT_REACHED -> ImpactFlowUnsupportedDocument.CALLBACK_PROOF_LIMIT_REACHED
        QueryCallbackFlowCauseDocument.STORED_CALLBACK,
        QueryCallbackFlowCauseDocument.RETURNED_CALLBACK,
        QueryCallbackFlowCauseDocument.UNSUPPORTED_CALLBACK_SUPPLY,
        QueryCallbackFlowCauseDocument.ANONYMOUS_IDENTITY_UNAVAILABLE,
        QueryCallbackFlowCauseDocument.UNRESOLVED_ARGUMENT_MAPPING,
        QueryCallbackFlowCauseDocument.UNRESOLVED_PARAMETER_REFERENCE,
        QueryCallbackFlowCauseDocument.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN,
        QueryCallbackFlowCauseDocument.DEPENDENCY_DEFERRED_MODEL_UNPROVEN,
        QueryCallbackFlowCauseDocument.DEPENDENCY_BINARY_UNAVAILABLE,
        QueryCallbackFlowCauseDocument.DEPENDENCY_BINARY_CHANGED,
        QueryCallbackFlowCauseDocument.DEPENDENCY_TARGET_UNAVAILABLE,
        QueryCallbackFlowCauseDocument.PARAMETER_ESCAPES,
        QueryCallbackFlowCauseDocument.CALLBACK_CYCLE,
        QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION,
        QueryCallbackFlowCauseDocument.NO_INVOCATION_PROVEN -> ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN
    }
