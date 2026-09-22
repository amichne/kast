package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.DaemonManagementRejection
import io.github.amichne.kast.appserver.DaemonManagementResponse
import io.github.amichne.kast.appserver.DaemonManagementTarget
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

internal interface DaemonWorkspacePreparation {
    fun prepare(root: String): Refinement<WorkspacePreparationDocument, DaemonManagementRejection>

    fun observe(requestId: String): Refinement<WorkspacePreparationDocument, DaemonManagementRejection>

    data object Unavailable : DaemonWorkspacePreparation {
        override fun prepare(root: String) = rejected()

        override fun observe(requestId: String) = rejected()

        private fun rejected() =
            Refinement.Rejected(DaemonManagementRejection.Preparation(WorkspacePreparationFailure.CLOSED))
    }
}

internal class ManagedDaemonWorkspacePreparation(private val owner: WorkspacePreparations) :
    DaemonWorkspacePreparation {
    override fun prepare(root: String): Refinement<WorkspacePreparationDocument, DaemonManagementRejection> {
        val path =
            try {
                Path.of(root).takeIf(Path::isAbsolute)
            } catch (_: IllegalArgumentException) {
                null
            } ?: return rejected(WorkspacePreparationFailure.ROOT_REJECTED)
        val canonical =
            when (val admitted = FilesystemCanonicalRootDiscovery.discover(path)) {
                is CanonicalRootDiscovery.Discovered -> admitted.root
                is CanonicalRootDiscovery.Rejected ->
                    return Refinement.Rejected(DaemonManagementRejection.Root(admitted.failure))
            }
        // Management prepares the supplied root, never an implicit containing workspace.
        if (canonical.path != path) return rejected(WorkspacePreparationFailure.ROOT_REJECTED)
        return project(owner.prepare(canonical))
    }

    override fun observe(requestId: String): Refinement<WorkspacePreparationDocument, DaemonManagementRejection> {
        val id =
            WorkspacePreparationId.admit(requestId) ?: return rejected(WorkspacePreparationFailure.IDENTITY_REJECTED)
        return project(owner.observe(id))
    }

    private fun project(
        result: Refinement<WorkspacePreparation, WorkspacePreparationFailure>
    ): Refinement<WorkspacePreparationDocument, DaemonManagementRejection> =
        when (result) {
            is Refinement.Refined -> Refinement.Refined(result.value.document())
            is Refinement.Rejected -> rejected(result.failure)
        }

    private fun rejected(failure: WorkspacePreparationFailure) =
        Refinement.Rejected(DaemonManagementRejection.Preparation(failure))
}

internal fun Refinement<WorkspacePreparationDocument, DaemonManagementRejection>.response(
    target: DaemonManagementTarget
): DaemonManagementResponse =
    when (this) {
        is Refinement.Refined -> DaemonManagementResponse.WorkspacePreparation(target, value)
        is Refinement.Rejected -> DaemonManagementResponse.Rejected(failure)
    }
