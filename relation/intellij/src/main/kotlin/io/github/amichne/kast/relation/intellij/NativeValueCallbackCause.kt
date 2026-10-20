package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowFailure
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowObligationFailure
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueSite

internal fun nativeValueCallbackUnavailable(
    site: ValueSite,
    cause: CallbackInvocationFlowCause,
): Refinement<ValueFlowObligation, ValueFlowObligationFailure> =
    ValueFlowObligation.fromCallbackUnavailable(site, cause)

internal fun nativeValueCallbackContractRejected(
    site: ValueSite,
    cause: CallbackInvocationFlowFailure,
): Refinement<ValueFlowObligation, ValueFlowRejection> =
    when (val value = ValueFlowObligation.fromCallbackContractRejected(site, cause)) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowRejection.STALE_SITE)
    }
