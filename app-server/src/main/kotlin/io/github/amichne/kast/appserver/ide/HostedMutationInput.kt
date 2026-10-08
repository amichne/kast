package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation

class HostedPlanIdentity private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HostedPlanIdentity, ExistingIdeFailure> =
            if (raw.matches(Regex("plan:[0-9a-f]{64}"))) Refinement.Refined(HostedPlanIdentity(raw))
            else Refinement.Rejected(ExistingIdeFailure.INVALID_REQUEST)
    }
}

@kotlinx.serialization.Serializable
enum class HostedMutationOperation(val canonical: CanonicalOperation) {
    CHANGE_APPLY(CanonicalOperation.CHANGE_APPLY),
    CHANGE_RECOVER(CanonicalOperation.CHANGE_RECOVER),
}
