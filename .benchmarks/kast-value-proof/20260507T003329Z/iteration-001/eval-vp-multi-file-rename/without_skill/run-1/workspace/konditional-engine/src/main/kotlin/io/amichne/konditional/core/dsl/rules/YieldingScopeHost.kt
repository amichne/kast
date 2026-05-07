package io.amichne.konditional.core.dsl.rules

import io.amichne.konditional.api.KonditionalInternalApi

@KonditionalInternalApi
interface YieldingScopeHost {
    fun registerPendingYield(token: PendingYieldToken)

    fun commitYield(token: PendingYieldToken, commit: () -> Unit)
}
