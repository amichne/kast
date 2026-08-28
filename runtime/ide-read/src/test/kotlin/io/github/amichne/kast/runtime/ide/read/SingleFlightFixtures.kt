package io.github.amichne.kast.runtime.ide.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import java.nio.file.Path

internal class FreshnessFixture(
    path: String = "/tmp/kast-single-flight",
) {
    private val root = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(path))) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("test root rejected: ${admitted.failure}")
    }
    private var state = 1
    private val source = ProjectReadEpoch.Source.create<Int> { Refinement.Refined(state) }

    fun advance() {
        state += 1
    }

    fun capability(): VfsPassiveReadCapability {
        val epoch = when (val observed = source.observe()) {
            is ProjectReadEpochObservation.Observed -> observed.epoch
            is ProjectReadEpochObservation.Rejected -> error(
                "test epoch rejected: ${observed.failure}",
            )
        }
        return VfsPassiveReadCapability.issue(root, epoch)
    }
}

internal fun active(admission: ProjectReadAdmission): ProjectReadPermit =
    (admission as ProjectReadAdmission.Active).permit

internal fun queued(admission: ProjectReadAdmission): QueuedProjectReadRequest =
    (admission as ProjectReadAdmission.Queued).request

/** Test-only construction of the pre-hosted state machine whose production constructor is closed. */
internal fun controller(
    initialFreshness: VfsPassiveReadCapability,
): ProjectReadSingleFlight {
    val constructor = ProjectReadSingleFlight::class.java.getDeclaredConstructor(
        VfsPassiveReadCapability::class.java,
    )
    constructor.isAccessible = true
    return constructor.newInstance(initialFreshness)
}
