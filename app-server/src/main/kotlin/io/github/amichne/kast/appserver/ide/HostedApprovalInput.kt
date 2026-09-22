package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import java.util.Base64

private const val MAXIMUM_ASSERTION_BYTES = 16384
private const val ED25519_SIGNATURE_BYTES = 64

/** Syntax proof only: the hosted owner remains the signature and challenge authority. */
class HostedApprovalAssertion private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HostedApprovalAssertion, ExistingIdeFailure> {
            if (raw.length !in 1..MAXIMUM_ASSERTION_BYTES || !raw.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{86}")))
                return Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
            return try {
                val parts = raw.split('.')
                val decoder = Base64.getUrlDecoder()
                val encoder = Base64.getUrlEncoder().withoutPadding()
                val payload = decoder.decode(parts[0])
                val signature = decoder.decode(parts[1])
                if (payload.isEmpty() || signature.size != ED25519_SIGNATURE_BYTES)
                    return Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
                if (encoder.encodeToString(payload) != parts[0] || encoder.encodeToString(signature) != parts[1])
                    Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
                else Refinement.Refined(HostedApprovalAssertion(raw))
            } catch (_: IllegalArgumentException) {
                Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
            }
        }
    }
}

class HostedPlanIdentity private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<HostedPlanIdentity, ExistingIdeFailure> =
            if (raw.matches(Regex("plan:[0-9a-f]{64}"))) Refinement.Refined(HostedPlanIdentity(raw))
            else Refinement.Rejected(ExistingIdeFailure.APPROVAL_REJECTED)
    }
}

@kotlinx.serialization.Serializable
enum class HostedMutationOperation(val canonical: CanonicalOperation) {
    CHANGE_APPLY(CanonicalOperation.CHANGE_APPLY),
    CHANGE_RECOVER(CanonicalOperation.CHANGE_RECOVER),
}
