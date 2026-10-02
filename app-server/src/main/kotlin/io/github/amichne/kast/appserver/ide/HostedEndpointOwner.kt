package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement

@JvmInline
value class HostedEndpointOwnerPid private constructor(val value: Long) {
    companion object {
        fun parse(raw: String): Refinement<HostedEndpointOwnerPid, ExistingIdeFailure> =
            raw.toLongOrNull()?.takeIf { it > 0 }?.let { Refinement.Refined(HostedEndpointOwnerPid(it)) }
                ?: Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
    }
}

internal enum class HostedEndpointOwnerObservation {
    ALIVE,
    ABSENT,
    UNAVAILABLE,
}

internal fun interface HostedEndpointOwnerProbe {
    fun observe(owner: HostedEndpointOwnerPid): HostedEndpointOwnerObservation
}

internal object ProcessHostedEndpointOwnerProbe : HostedEndpointOwnerProbe {
    override fun observe(owner: HostedEndpointOwnerPid): HostedEndpointOwnerObservation =
        try {
            val process = ProcessHandle.of(owner.value)
            if (process.isEmpty || !process.get().isAlive) HostedEndpointOwnerObservation.ABSENT
            else HostedEndpointOwnerObservation.ALIVE
        } catch (_: SecurityException) {
            HostedEndpointOwnerObservation.UNAVAILABLE
        } catch (_: UnsupportedOperationException) {
            HostedEndpointOwnerObservation.UNAVAILABLE
        } catch (_: IllegalArgumentException) {
            HostedEndpointOwnerObservation.UNAVAILABLE
        }
}
