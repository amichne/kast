package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

internal fun hostedTraversalOutputPages(limits: ReadLimits, clock: () -> Long = System::nanoTime) =
    HostedOutputPages(
        CanonicalOperationWireBindings.traversalRun,
        TraversalContinuationDocument.OUTPUT_PREFIX,
        limits,
        normalize = { request: TraversalRunRequest ->
            request.copy(
                position = TraversalRunPositionDocument.Start,
                maximumResults = (ProtocolCount.parse(1) as Refinement.Refined).value,
                executionBudget = null,
            )
        },
        unavailable = TraversalRunRejection.CONTINUATION_UNAVAILABLE,
        mismatch = TraversalRunRejection.CONTINUATION_REQUEST_MISMATCH,
        clock = clock,
    )
