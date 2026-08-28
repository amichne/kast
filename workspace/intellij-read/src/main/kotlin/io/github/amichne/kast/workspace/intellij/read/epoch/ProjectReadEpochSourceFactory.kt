package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch

/** Internal typed installation failure for the one epoch source retained by an admission. */
internal sealed interface ExistingProjectReadEpochSourceInstallationFailure {
    data object ProjectDisposed : ExistingProjectReadEpochSourceInstallationFailure
}

internal fun interface ExistingProjectReadEpochSourceFactory {
    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) ->
     * Refinement<ProjectReadEpoch.Source<*>,
     * ExistingProjectReadEpochSourceInstallationFailure>`.
     *
     * Establishes one source identity retained for the admitted Project/runtime or closes a
     * disposal race. Raw Project and listener construction remain in `:workspace:intellij-read`.
     */
    fun create(
        project: Project,
        root: CanonicalWorkspaceRoot,
    ): Refinement<ProjectReadEpoch.Source<*>, ExistingProjectReadEpochSourceInstallationFailure>
}
