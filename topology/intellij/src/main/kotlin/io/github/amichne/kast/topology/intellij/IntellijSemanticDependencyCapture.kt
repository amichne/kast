package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadStage
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.IntellijReadUnexpectedFailure
import io.github.amichne.kast.workspace.intellij.read.call
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.CancellationException

/** Cache eligibility rejection preserves the ordinary fresh semantic analysis path. */
enum class SemanticDependencyCaptureFailure {
    PROJECT_UNAVAILABLE,
    AUTHORITY_MOVED,
    MODULE_UNAVAILABLE,
    MODEL_ROOT_MISMATCH,
    DEPENDENCY_MODULE_UNMODELED,
    DEPENDENCY_GRAPH_REJECTED,
    DEPENDENCY_CLOSURE_REJECTED,
    SOURCE_ROOT_INVENTORY_MISMATCH,
    SOURCE_MODULE_INVENTORY_REJECTED,
    SOURCE_INVENTORY_REJECTED,
    RESOLUTION_INPUT_INVENTORY_REJECTED,
    COMPILER_CONFIGURATION_UNAVAILABLE,
    COMPILER_DISTRIBUTION_UNMODELED,
    COMPILER_PLUGIN_INPUTS_UNMODELED,
    SOURCE_UNAVAILABLE,
    SOURCE_DOCUMENT_DIRTY,
    SOURCE_DOCUMENT_UNCOMMITTED,
    INPUT_PROVIDER_UNSUPPORTED,
    INPUT_UNAVAILABLE,
    CAPACITY_EXCEEDED,
    WORK_EXHAUSTED,
    TIME_EXHAUSTED,
}

/** Measured preparation cost must be charged even when reuse admission fails. */
class SemanticDependencyCaptureCost internal constructor(val workUnits: Long, val elapsedNanos: Long) {
    companion object {
        fun fromBoundary(
            workUnits: Long,
            elapsedNanos: Long,
        ): Refinement<SemanticDependencyCaptureCost, SemanticDependencyCaptureCostFailure> =
            when {
                workUnits < 0 -> Refinement.Rejected(SemanticDependencyCaptureCostFailure.NEGATIVE_WORK)
                elapsedNanos < 0 -> Refinement.Rejected(SemanticDependencyCaptureCostFailure.NEGATIVE_ELAPSED)
                else -> Refinement.Refined(SemanticDependencyCaptureCost(workUnits, elapsedNanos))
            }
    }
}

enum class SemanticDependencyCaptureCostFailure {
    NEGATIVE_WORK,
    NEGATIVE_ELAPSED,
}

sealed interface SemanticDependencyCapture {
    val cost: SemanticDependencyCaptureCost

    data class Captured(val snapshot: SemanticDependencySnapshot, override val cost: SemanticDependencyCaptureCost) :
        SemanticDependencyCapture

    data class Unavailable(
        val cause: SemanticDependencyCaptureFailure,
        override val cost: SemanticDependencyCaptureCost,
    ) : SemanticDependencyCapture
}

/** Explicit JVM native input observation. No native object or platform callback escapes a capture. */
class IntellijSemanticDependencyCapture(
    private val limits: ReadLimits = ReadLimits.Default,
    private val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    /** Caller must keep the same native read action through fact admission and use. */
    // Native platform extensions may throw unchecked exceptions; retain bounded stage evidence at this effect boundary.
    fun capture(
        project: Project,
        authority: LiveSemanticReadAuthority,
        model: WorkspaceSearchScopeModel,
        roots: Set<WorkspaceModuleIdentity>,
        budget: ResourceBudget,
        onCost: (SemanticDependencyCaptureCost) -> Unit,
    ): SemanticDependencyCapture {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        observation.phase(IntellijReadPhase.SEMANTIC_DEPENDENCY_PREPARATION)
        val accounting = DependencyCaptureBudget(budget, System::nanoTime, ProgressManager::checkCanceled, observation)
        return observation.call(IntellijReadCall.SEMANTIC_DEPENDENCY_CAPTURE) {
            observeDependencyCaptureCost(accounting, onCost) {
                val result = captureObserved(project, authority, model, roots, accounting)
                when (result) {
                    is Refinement.Refined -> SemanticDependencyCapture.Captured(result.value, accounting.cost())
                    is Refinement.Rejected -> {
                        observation.terminated(result.failure.termination())
                        SemanticDependencyCapture.Unavailable(result.failure, accounting.cost())
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun captureObserved(
        project: Project,
        authority: LiveSemanticReadAuthority,
        model: WorkspaceSearchScopeModel,
        roots: Set<WorkspaceModuleIdentity>,
        accounting: DependencyCaptureBudget,
    ): SemanticCapture<SemanticDependencySnapshot> {
        return try {
            captureInRead(project, authority, model, roots, accounting)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
        } catch (failure: LinkageError) {
            observation.unexpected(
                IntellijReadUnexpectedFailure.capture(
                    IntellijReadStage.SEMANTIC_DEPENDENCY_PREPARATION,
                    failure,
                    limits,
                )
            )
            Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
        } catch (failure: RuntimeException) {
            observation.unexpected(
                IntellijReadUnexpectedFailure.capture(
                    IntellijReadStage.SEMANTIC_DEPENDENCY_PREPARATION,
                    failure,
                    limits,
                )
            )
            Refinement.Rejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
        }
    }

    private fun captureInRead(
        project: Project,
        authority: LiveSemanticReadAuthority,
        model: WorkspaceSearchScopeModel,
        roots: Set<WorkspaceModuleIdentity>,
        budget: DependencyCaptureBudget,
    ): SemanticCapture<SemanticDependencySnapshot> {
        if (project.isDisposed || DumbService.isDumb(project))
            return rejected(SemanticDependencyCaptureFailure.PROJECT_UNAVAILABLE)
        if (authority.workspaceRoot != model.workspaceRoot || project.basePath != model.workspaceRoot.value)
            return rejected(SemanticDependencyCaptureFailure.MODEL_ROOT_MISMATCH)
        if (authority.withCurrentOwner { Unit } is Refinement.Rejected)
            return rejected(SemanticDependencyCaptureFailure.AUTHORITY_MOVED)
        val modules =
            when (val observed = SemanticNativeModuleGraphCapture(limits, budget).capture(project, model)) {
                is Refinement.Refined -> observed.value
                is Refinement.Rejected -> return observed
            }
        val closure =
            when (val admitted = modules.graph.closure(roots)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(SemanticDependencyCaptureFailure.DEPENDENCY_CLOSURE_REJECTED)
            }
        return SemanticNativeModuleInputs(project, model, closure, limits, budget).snapshot(authority, modules.selected)
    }
}

/** Framed hashing prevents argument/root boundary ambiguity; order remains semantically significant. */
internal class SemanticInputDigest {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    fun finish(): WorkspaceSourceContentHash = parsedDigest(digest)
}

internal fun parsedDigest(digest: MessageDigest): WorkspaceSourceContentHash =
    when (val parsed = WorkspaceSourceContentHash.parse(HexFormat.of().formatHex(digest.digest()))) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("SHA-256 provider emitted invalid digest")
    }

internal class DependencyCaptureBudget(
    private val budget: ResourceBudget,
    private val nanoTime: () -> Long,
    private val checkCanceled: () -> Unit,
    val observation: IntellijReadObservation = IntellijReadObservation.None,
) {
    private val started = nanoTime()
    private var work = 0L

    fun step(): Refinement<Unit, SemanticDependencyCaptureFailure> {
        checkCanceled()
        if (work >= budget.workUnitLimit.value) return rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED)
        if ((nanoTime() - started) / NANOS_PER_MILLISECOND >= budget.elapsedTimeLimit.value)
            return rejected(SemanticDependencyCaptureFailure.TIME_EXHAUSTED)
        work += 1
        return Refinement.Refined(Unit)
    }

    fun cost() = SemanticDependencyCaptureCost(work, (nanoTime() - started).coerceAtLeast(0))
}

private fun rejected(cause: SemanticDependencyCaptureFailure) = Refinement.Rejected(cause)

internal fun SemanticDependencyCaptureFailure.termination(): IntellijReadTermination =
    when (this) {
        SemanticDependencyCaptureFailure.PROJECT_UNAVAILABLE ->
            IntellijReadTermination.SEMANTIC_INPUT_PROJECT_UNAVAILABLE
        SemanticDependencyCaptureFailure.AUTHORITY_MOVED -> IntellijReadTermination.SEMANTIC_INPUT_AUTHORITY_MOVED
        SemanticDependencyCaptureFailure.MODULE_UNAVAILABLE -> IntellijReadTermination.SEMANTIC_INPUT_MODULE_UNAVAILABLE
        SemanticDependencyCaptureFailure.MODEL_ROOT_MISMATCH ->
            IntellijReadTermination.SEMANTIC_INPUT_MODEL_ROOT_MISMATCH
        SemanticDependencyCaptureFailure.DEPENDENCY_MODULE_UNMODELED ->
            IntellijReadTermination.SEMANTIC_INPUT_DEPENDENCY_MODULE_UNMODELED
        SemanticDependencyCaptureFailure.DEPENDENCY_GRAPH_REJECTED ->
            IntellijReadTermination.SEMANTIC_INPUT_DEPENDENCY_GRAPH_REJECTED
        SemanticDependencyCaptureFailure.DEPENDENCY_CLOSURE_REJECTED ->
            IntellijReadTermination.SEMANTIC_INPUT_DEPENDENCY_CLOSURE_REJECTED
        SemanticDependencyCaptureFailure.SOURCE_ROOT_INVENTORY_MISMATCH ->
            IntellijReadTermination.SEMANTIC_INPUT_SOURCE_ROOT_INVENTORY_MISMATCH
        SemanticDependencyCaptureFailure.SOURCE_MODULE_INVENTORY_REJECTED ->
            IntellijReadTermination.SEMANTIC_INPUT_SOURCE_MODULE_INVENTORY_REJECTED
        SemanticDependencyCaptureFailure.SOURCE_INVENTORY_REJECTED ->
            IntellijReadTermination.SEMANTIC_INPUT_SOURCE_INVENTORY_REJECTED
        SemanticDependencyCaptureFailure.RESOLUTION_INPUT_INVENTORY_REJECTED ->
            IntellijReadTermination.SEMANTIC_INPUT_RESOLUTION_INVENTORY_REJECTED
        SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE ->
            IntellijReadTermination.SEMANTIC_INPUT_COMPILER_CONFIGURATION_UNAVAILABLE
        SemanticDependencyCaptureFailure.COMPILER_DISTRIBUTION_UNMODELED ->
            IntellijReadTermination.SEMANTIC_INPUT_COMPILER_DISTRIBUTION_UNMODELED
        SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED ->
            IntellijReadTermination.SEMANTIC_INPUT_COMPILER_PLUGIN_INPUTS_UNMODELED
        SemanticDependencyCaptureFailure.SOURCE_UNAVAILABLE -> IntellijReadTermination.SEMANTIC_INPUT_SOURCE_UNAVAILABLE
        SemanticDependencyCaptureFailure.SOURCE_DOCUMENT_DIRTY ->
            IntellijReadTermination.SEMANTIC_INPUT_SOURCE_DOCUMENT_DIRTY
        SemanticDependencyCaptureFailure.SOURCE_DOCUMENT_UNCOMMITTED ->
            IntellijReadTermination.SEMANTIC_INPUT_SOURCE_DOCUMENT_UNCOMMITTED
        SemanticDependencyCaptureFailure.INPUT_PROVIDER_UNSUPPORTED ->
            IntellijReadTermination.SEMANTIC_INPUT_PROVIDER_UNSUPPORTED
        SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE -> IntellijReadTermination.SEMANTIC_INPUT_UNAVAILABLE
        SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED -> IntellijReadTermination.SEMANTIC_INPUT_CAPACITY_EXCEEDED
        SemanticDependencyCaptureFailure.WORK_EXHAUSTED -> IntellijReadTermination.SEMANTIC_INPUT_WORK_EXHAUSTED
        SemanticDependencyCaptureFailure.TIME_EXHAUSTED -> IntellijReadTermination.SEMANTIC_INPUT_TIME_EXHAUSTED
    }

/** Cost survives exceptional read-action exits and is debited exactly once. */
internal inline fun <Value> observeDependencyCaptureCost(
    accounting: DependencyCaptureBudget,
    observe: (SemanticDependencyCaptureCost) -> Unit,
    action: () -> Value,
): Value =
    try {
        action()
    } finally {
        observe(accounting.cost())
    }

private const val NANOS_PER_MILLISECOND = 1_000_000L
