package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure

internal interface ProjectReadEpochExecution {
    fun isDispatchThread(): Boolean

    fun isWriteAccessAllowed(): Boolean = false

    fun compute(
        read: () -> Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>
    ): Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>
}

internal object IdeaProjectReadEpochExecution : ProjectReadEpochExecution {
    override fun isDispatchThread(): Boolean = ApplicationManager.getApplication().isDispatchThread

    override fun isWriteAccessAllowed(): Boolean = ApplicationManager.getApplication().isWriteAccessAllowed

    override fun compute(
        read: () -> Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>
    ): Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure> =
        ReadAction.computeCancellable<
            Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>,
            RuntimeException,
        >(
            read
        )
}
