package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause

internal enum class CallbackExpiryAdmission {
    CURRENT,
    EXPIRED,
}

/** Check expiry before native site access. Excluded callbacks never debit eligible-work capacity. */
internal inline fun admitCallbackProviderSite(
    cancellationCheck: () -> Unit,
    expiry: () -> CallbackExpiryAdmission,
    classify: () -> RelationProviderScopeAdmission,
): Refinement<RelationProviderScopeAdmission, CallbackInvocationFlowCause> {
    cancellationCheck()
    return when (expiry()) {
        CallbackExpiryAdmission.CURRENT -> Refinement.Refined(classify())
        CallbackExpiryAdmission.EXPIRED -> Refinement.Rejected(CallbackInvocationFlowCause.TIME_LIMIT_REACHED)
    }
}
