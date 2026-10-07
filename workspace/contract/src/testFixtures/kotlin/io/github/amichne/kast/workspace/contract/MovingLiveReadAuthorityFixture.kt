package io.github.amichne.kast.workspace.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.UUID

/** Test-only monotonic observations through the actual original-owner admission transition. */
class MovingLiveReadAuthorityFixture(private val root: CanonicalWorkspaceRoot) {
    private data class Signal(val revision: Long, val environment: Long = 1)

    private var signal = Signal(1)
    private val source =
        ProjectReadEpoch.Source.createWithEnvironment(
            observer = { Refinement.Refined(signal) },
            beforeWriteObserver = { Refinement.Refined(signal) },
            environment = Signal::environment,
        )
    private val owner = LiveSemanticReadOwner(root, IdeReadHostLifetime.fromBoundary(UUID.randomUUID()))

    fun admit(): LiveSemanticReadAuthority {
        val epoch = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        return (owner.admit { VfsPassiveReadAdmission.Admitted(VfsPassiveReadCapability.issue(root, epoch)) }
                as Refinement.Refined)
            .value
    }

    fun advance(): LiveSemanticReadAuthority {
        signal = signal.copy(revision = Math.addExact(signal.revision, 1))
        return admit()
    }

    fun advanceEnvironment(): LiveSemanticReadAuthority {
        signal = signal.copy(environment = Math.addExact(signal.environment, 1))
        return admit()
    }

    fun retire() = owner.retire()
}
