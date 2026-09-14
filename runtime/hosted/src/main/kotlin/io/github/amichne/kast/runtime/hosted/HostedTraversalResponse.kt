package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

internal fun encodeHostedTraversalResponse(
    semantic: HostedTraversalOutcome,
    limits: ReadLimits,
    maximumResults: ResultLimit,
    maximumBytes: ReturnedByteLimit,
    retain: (HostedTraversalOutcome) -> HostedOutputRetention,
): HostedResponse =
    HostedResponse.Canonical.encode(
        CanonicalOperationWireBindings.traversalRun,
        semantic,
        limits,
        maximumBytes,
    )
