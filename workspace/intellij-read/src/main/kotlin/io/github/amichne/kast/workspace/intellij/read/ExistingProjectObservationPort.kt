package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot

/** Ordered observation boundary used by existing-Project admission. */
internal interface ExistingProjectObservationPort {
    fun isDisposed(project: Project): Boolean

    fun isOpen(project: Project): Boolean

    fun isInitialized(project: Project): Boolean

    fun root(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): ExistingProjectRootObservation

    fun gradleModel(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): ExistingProjectGradleModelState

    fun indexing(project: Project): ExistingProjectIndexingState

    fun kotlinMode(): ExistingProjectKotlinMode

    fun hostIdentity(): ExistingProjectHostIdentityObservation
}
