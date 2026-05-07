package io.amichne.konditional.core.dsl.rules

import io.amichne.konditional.api.KonditionalInternalApi

@KonditionalInternalApi
class PendingYieldToken internal constructor(
    val callSite: String?,
)
