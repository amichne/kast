package io.github.amichne.kast.topology.intellij.historical

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalCapturePlan
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureOutcome
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCapturePort
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel

/**
 * Explicit capture admission for an already prepared Project. No live request invokes this adapter. Current native
 * owners cannot exclude external resolution-input movement throughout extraction. The adapter preserves that
 * implementation gap as a finite rejection before semantic extraction or publication.
 */
class IntellijHistoricalCaptureAdapter(
    private val project: Project,
    private val authority: LiveSemanticReadAuthority,
    private val model: WorkspaceSearchScopeModel,
    private val limits: ReadLimits = ReadLimits.Default,
) : HistoricalNativeCapturePort {
    // Only write-priority read preemption becomes data. Other cancellation propagates.
    @Suppress("IncorrectCancellationExceptionHandling")
    override fun capture(plan: HistoricalCapturePlan): HistoricalNativeCaptureOutcome {
        if (ApplicationManager.getApplication().isDispatchThread)
            return rejected(HistoricalNativeCaptureFailure.WRONG_THREAD)
        return try {
            ReadAction.computeCancellable<HistoricalNativeCaptureOutcome, RuntimeException> { admitInsideRead(plan) }
        } catch (_: ReadAction.CannotReadException) {
            rejected(HistoricalNativeCaptureFailure.READ_PREEMPTED)
        }
    }

    private fun admitInsideRead(plan: HistoricalCapturePlan): HistoricalNativeCaptureOutcome {
        if (project.isDisposed || !project.isOpen || !project.isInitialized)
            return rejected(HistoricalNativeCaptureFailure.PROJECT_UNAVAILABLE)
        if (DumbService.isDumb(project)) return rejected(HistoricalNativeCaptureFailure.PROJECT_UNAVAILABLE)
        if (model.workspaceRoot != authority.workspaceRoot || project.basePath != model.workspaceRoot.value)
            return rejected(HistoricalNativeCaptureFailure.MODEL_MISMATCH)
        if (authority.withCurrentOwner { Unit } is Refinement.Rejected)
            return rejected(HistoricalNativeCaptureFailure.AUTHORITY_MOVED)
        if (plan.modules.size > limits[ReadLimitParameter.MODEL_MODULES].value)
            return rejected(HistoricalNativeCaptureFailure.CAPACITY_EXCEEDED)
        // Historical module identity has no admitted complete imported-model projection yet.
        // Source-root membership and display-name equality cannot establish that projection.
        // An IDEA read action excludes IDEA writes. Root-filtered epochs do not cover mutable external inputs.
        // Equal before/after digests cannot detect an external change followed by a revert.
        return rejected(HistoricalNativeCaptureFailure.EXTERNAL_INPUT_STABILITY_UNPROVEN)
    }

    private fun rejected(cause: HistoricalNativeCaptureFailure) = HistoricalNativeCaptureOutcome.Rejected(cause)
}
