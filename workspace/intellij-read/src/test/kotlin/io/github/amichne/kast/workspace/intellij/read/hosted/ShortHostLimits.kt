package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement

/** A deliberately short transport stack for virtual-time deadline cases. */
internal fun shortHostLimits(): ReadLimits =
    when (
        val admitted =
            ReadLimits.resolve(
                mapOf(
                    "KAST_READ_HOST_QUERY_MILLIS" to "4000",
                    "KAST_READ_HOST_CONNECTION_MILLIS" to "5000",
                    "KAST_READ_CLIENT_EXCHANGE_MILLIS" to "6000",
                )
            )
    ) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("Short host deadline fixture rejected: ${admitted.failure}")
    }
