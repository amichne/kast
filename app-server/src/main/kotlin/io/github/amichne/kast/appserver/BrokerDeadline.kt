package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement

internal fun brokerDeadline(value: Long): ElapsedTimeLimitMillis =
    when (val admitted = ElapsedTimeLimitMillis.parse(value)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("Invalid fixed broker deadline")
    }
