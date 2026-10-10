package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement
import java.security.MessageDigest

@JvmInline
internal value class BrokerWorkspaceId private constructor(val value: String) {
    enum class Failure {
        INVALID
    }

    companion object {
        private const val SHA256_DIGEST_HEX_LENGTH = 64

        /** Detached identity ingress; canonical lowercase digest syntax conveys no native readiness. */
        fun parse(raw: String): Refinement<BrokerWorkspaceId, Failure> =
            if (raw.length == SHA256_DIGEST_HEX_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' })
                Refinement.Refined(BrokerWorkspaceId(raw))
            else Refinement.Rejected(Failure.INVALID)

        fun derive(root: CanonicalBrokerDirectory): BrokerWorkspaceId =
            BrokerWorkspaceId(
                MessageDigest.getInstance("SHA-256")
                    .digest(root.path.toString().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            )
    }
}
