package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** Source response encoding boundary; the complete canonical envelope owns the byte measurement. */
internal fun encodeHostedSourceResponse(
    semantic: HostedSourceOutcome,
    limits: ReadLimits,
    maximumBytes: ReturnedByteLimit,
): HostedResponse =
    HostedResponse.Canonical.encode(CanonicalOperationWireBindings.sourceRead, semantic, limits, maximumBytes)
