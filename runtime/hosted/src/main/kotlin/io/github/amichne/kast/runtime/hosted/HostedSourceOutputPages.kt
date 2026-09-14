package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings

/** Detached output uses the shared hosted suffix policy, independently of native source cursor retention. */
internal fun hostedSourceOutputPages(limits: ReadLimits) =
    HostedOutputPages(
        CanonicalOperationWireBindings.sourceRead,
        SOURCE_OUTPUT_PREFIX,
        limits,
        normalize = { request: SourceReadRequest ->
            request.copy(
                page = SourceReadPageDocument.First,
                entityLimit = (SourceEntityLimitDocument.parse(1) as Refinement.Refined).value,
                textByteLimit = (SourceTextByteLimitDocument.parse(1) as Refinement.Refined).value,
                executionBudget = null,
            )
        },
        unavailable = SourceReadRejection.CONTINUATION_UNAVAILABLE,
        mismatch = SourceReadRejection.CONTINUATION_REQUEST_MISMATCH,
    )
