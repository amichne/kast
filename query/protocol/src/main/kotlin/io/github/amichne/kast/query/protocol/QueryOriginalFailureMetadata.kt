package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryRunRejection

/** Absence is optional metadata; an incompatible policy failure remains an exact standalone rejection. */
internal fun optionalOriginalFailure(
    original: QueryRunRejection?
): Refinement<io.github.amichne.kast.protocol.contract.QueryOriginalFailureDocument?, QueryRunRejection> =
    if (original == null) Refinement.Refined(null)
    else
        when (val admitted = original.originalFailure()) {
            is Refinement.Refined -> Refinement.Refined(admitted.value)
            is Refinement.Rejected -> Refinement.Rejected(original)
        }
