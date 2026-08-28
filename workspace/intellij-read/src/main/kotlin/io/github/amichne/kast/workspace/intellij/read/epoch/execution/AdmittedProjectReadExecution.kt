package io.github.amichne.kast.workspace.intellij.read.epoch.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import io.github.amichne.kast.workspace.intellij.read.AdmittedProjectReadExecutionProof

/** Finite fail-fast rejections before semantic work may produce a value. */
internal enum class AdmittedProjectReadExecutionFailure {
    WRONG_THREAD,
    EXISTING_READ_ACCESS,
    PROJECT_DISPOSED,
    PROJECT_NOT_OPEN,
    DUMB_MODE,
}

/** Finite failures joining one freshness capability to the retained admitted Project. */
internal sealed interface AdmittedProjectReadExecutionAdmissionFailure {
    data object WrongProject : AdmittedProjectReadExecutionAdmissionFailure
    data class FreshnessRejected(val cause: VfsPassiveReadAdmissionFailure) :
        AdmittedProjectReadExecutionAdmissionFailure
}

/** Closed result of joining same-source freshness to the retained admitted Project. */
internal sealed interface AdmittedProjectReadExecutionAdmission {
    data class Admitted(
        val execution: AdmittedProjectReadExecution,
        val freshness: VfsPassiveReadCapability,
    ) : AdmittedProjectReadExecutionAdmission

    data class Rejected(val failure: AdmittedProjectReadExecutionAdmissionFailure) :
        AdmittedProjectReadExecutionAdmission
}

/** Closed result of one admitted, cancellable Project read. */
internal sealed interface AdmittedProjectReadExecutionResult<out Value : Any> {
    data class Completed<Value : Any>(val value: Value) :
        AdmittedProjectReadExecutionResult<Value>

    data class Rejected(val failure: AdmittedProjectReadExecutionFailure) :
        AdmittedProjectReadExecutionResult<Nothing>
}

/**
 * Internal semantic computation invoked only while the exact admitted Project read is held.
 *
 * Implementations must be bounded and side-effect-free because IntelliJ cancellation may stop
 * them at any progress check. The live [Project] is available only at this adapter boundary.
 */
internal fun interface AdmittedProjectReadComputation<out Value : Any> {
    fun compute(project: Project): Value
}

/**
 * State-specific live authority retained from one `AdmittedIdeProject`.
 *
 * Construction is confined to the admitted-Project owner; this value never exposes its Project.
 */
internal class AdmittedProjectReadExecution private constructor(
    private val project: Project,
) {
    /**
     * Proof transition: `(AdmittedProjectReadExecution, AdmittedProjectReadComputation<Value>) ->
     * AdmittedProjectReadExecutionResult<Value>`.
     *
     * Establishes background-thread, open, undisposed, smart, write-priority cancellable execution
     * against the exact admitted Project. [AdmittedProjectReadExecutionFailure] closes expected
     * lifecycle rejection. Raw Project extraction is permitted only for [computation] during this
     * IDEA 262 adapter call. Every platform cancellation propagates unchanged.
     */
    fun <Value : Any> execute(
        computation: AdmittedProjectReadComputation<Value>,
    ): AdmittedProjectReadExecutionResult<Value> {
        when (val preflight = observePreflightState()) {
            ProjectReadExecutionState.READY -> Unit
            is ProjectReadExecutionState.REJECTED -> return preflight.result
        }
        return ReadAction.computeCancellable<
            AdmittedProjectReadExecutionResult<Value>,
            RuntimeException,
        > {
            ProgressManager.checkCanceled()
            when (val insideRead = observeLifecycleState()) {
                ProjectReadExecutionState.READY ->
                    AdmittedProjectReadExecutionResult.Completed(computation.compute(project))
                is ProjectReadExecutionState.REJECTED -> insideRead.result
            }
        }
    }

    /** Rejects caller context that would bypass the write-priority read primitive. */
    private fun observePreflightState(): ProjectReadExecutionState = when {
        ApplicationManager.getApplication().isDispatchThread -> rejected(
            AdmittedProjectReadExecutionFailure.WRONG_THREAD,
        )
        ApplicationManager.getApplication().isReadAccessAllowed -> rejected(
            AdmittedProjectReadExecutionFailure.EXISTING_READ_ACCESS,
        )
        else -> observeLifecycleState()
    }

    /** Rechecks only lifecycle state after the platform primitive has acquired read access. */
    private fun observeLifecycleState(): ProjectReadExecutionState = when {
        project.isDisposed -> rejected(AdmittedProjectReadExecutionFailure.PROJECT_DISPOSED)
        !project.isOpen -> rejected(AdmittedProjectReadExecutionFailure.PROJECT_NOT_OPEN)
        DumbService.isDumb(project) -> rejected(AdmittedProjectReadExecutionFailure.DUMB_MODE)
        else -> ProjectReadExecutionState.READY
    }

    companion object {
        /**
         * Proof transition: `(Project, AdmittedProjectReadExecutionProof) ->
         * AdmittedProjectReadExecution`.
         *
         * Retains the already-admitted exact Project without revalidating or exposing it. Only
         * `AdmittedIdeProject` may call this after project admission admission.
         */
        fun bind(
            project: Project,
            @Suppress("UNUSED_PARAMETER") proof: AdmittedProjectReadExecutionProof,
        ): AdmittedProjectReadExecution =
            AdmittedProjectReadExecution(project)
    }
}

/** Strong state produced by the fail-fast platform lifecycle observation. */
private sealed interface ProjectReadExecutionState {
    data object READY : ProjectReadExecutionState
    data class REJECTED(
        val result: AdmittedProjectReadExecutionResult.Rejected,
    ) : ProjectReadExecutionState
}

/** Refines a finite lifecycle cause into the corresponding rejected state. */
private fun rejected(
    failure: AdmittedProjectReadExecutionFailure,
): ProjectReadExecutionState.REJECTED = ProjectReadExecutionState.REJECTED(
    AdmittedProjectReadExecutionResult.Rejected(failure),
)
