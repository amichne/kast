package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.application.ApplicationManager
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
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.call
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/** Cache eligibility rejection preserves the ordinary fresh semantic analysis path. */
enum class SemanticDependencyCaptureFailure {
    READ_CAPTURE_ENDED,
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
    MINIMUM_HASH_WORK_UNAVAILABLE,
    FILE_CAPTURE_ADMISSION_CONSUMED,
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
    /** Opened and closed inside one synchronous native attempt; graph carriers never survive closure. */
    fun openRead(project: Project, authority: LiveSemanticReadAuthority, model: WorkspaceSearchScopeModel): Read {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return Read(project, authority, model)
    }

    inner class Read
    internal constructor(
        internal val project: Project,
        internal val authority: LiveSemanticReadAuthority,
        internal val model: WorkspaceSearchScopeModel,
    ) {
        internal val inputs = SemanticDependencyReadInputs<SemanticNativeModuleGraph>(authority, model) { it.graph }
        internal val files = SemanticNativeFileMemo(limits)

        fun capture(
            roots: Set<WorkspaceModuleIdentity>,
            budget: ResourceBudget,
            onCost: (SemanticDependencyCaptureCost) -> Unit,
        ): SemanticDependencyCapture = captureRead(this, roots, budget, onCost)

        fun finishNativeRead() {
            inputs.finishNativeRead()
            files.finishNativeRead()
        }
    }

    /** Standalone callers receive a fresh scope, always closed before returning. */
    fun capture(
        project: Project,
        authority: LiveSemanticReadAuthority,
        model: WorkspaceSearchScopeModel,
        roots: Set<WorkspaceModuleIdentity>,
        budget: ResourceBudget,
        onCost: (SemanticDependencyCaptureCost) -> Unit,
    ): SemanticDependencyCapture {
        val read = openRead(project, authority, model)
        return try {
            read.capture(roots, budget, onCost)
        } finally {
            read.finishNativeRead()
        }
    }

    /** Caller must keep the same native read action through fact admission and use. */
    // Native platform extensions may throw unchecked exceptions; retain bounded stage evidence at this effect boundary.
    private fun captureRead(
        read: Read,
        roots: Set<WorkspaceModuleIdentity>,
        budget: ResourceBudget,
        onCost: (SemanticDependencyCaptureCost) -> Unit,
    ): SemanticDependencyCapture {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        observation.phase(IntellijReadPhase.SEMANTIC_DEPENDENCY_PREPARATION)
        val accounting = DependencyCaptureBudget(budget, System::nanoTime, ProgressManager::checkCanceled, observation)
        return observation.call(IntellijReadCall.SEMANTIC_DEPENDENCY_CAPTURE) {
            observeDependencyCaptureCost(accounting, onCost) {
                val result = observeSemanticInputCapture(limits, observation) { captureInRead(read, roots, accounting) }
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

    private fun captureInRead(
        read: Read,
        roots: Set<WorkspaceModuleIdentity>,
        budget: DependencyCaptureBudget,
    ): SemanticCapture<SemanticDependencySnapshot> {
        when (val active = read.inputs.admitRead()) {
            is Refinement.Rejected -> return active
            is Refinement.Refined -> Unit
        }
        val project = read.project
        val authority = read.authority
        val model = read.model
        if (project.isDisposed || DumbService.isDumb(project))
            return rejected(SemanticDependencyCaptureFailure.PROJECT_UNAVAILABLE)
        if (authority.workspaceRoot != model.workspaceRoot || project.basePath != model.workspaceRoot.value)
            return rejected(SemanticDependencyCaptureFailure.MODEL_ROOT_MISMATCH)
        return read.inputs.snapshot(
            roots,
            budget,
            captureGraph = {
                observeSemanticInputCapture(limits, observation) {
                    SemanticNativeModuleGraphCapture(limits, budget).capture(project, model)
                }
            },
            captureModule = { modules, closure, identity ->
                observeSemanticInputCapture(limits, observation) {
                    SemanticNativeModuleInputs(project, model, closure, limits, budget, read.files)
                        .module(identity, modules.selected.getValue(identity))
                }
            },
        )
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

    fun current(): Refinement<Unit, SemanticDependencyCaptureFailure> {
        checkCanceled()
        if (work >= budget.workUnitLimit.value) return rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED)
        if ((nanoTime() - started) / NANOS_PER_MILLISECOND >= budget.elapsedTimeLimit.value)
            return rejected(SemanticDependencyCaptureFailure.TIME_EXHAUSTED)
        return Refinement.Refined(Unit)
    }

    fun step(): Refinement<Unit, SemanticDependencyCaptureFailure> =
        when (val admitted = current()) {
            is Refinement.Rejected -> admitted
            is Refinement.Refined -> {
                work += 1
                admitted
            }
        }

    /** A lower bound is proof of infeasibility, never work that was actually performed. */
    fun requireHashReads(minimum: SemanticHashReadMinimum): SemanticCapture<Unit> {
        checkCanceled()
        if ((nanoTime() - started) / NANOS_PER_MILLISECOND >= budget.elapsedTimeLimit.value)
            return rejected(SemanticDependencyCaptureFailure.TIME_EXHAUSTED)
        if (minimum.calls.toLong() > budget.workUnitLimit.value - work)
            return rejected(SemanticDependencyCaptureFailure.MINIMUM_HASH_WORK_UNAVAILABLE)
        return Refinement.Refined(Unit)
    }

    fun cost() = SemanticDependencyCaptureCost(work, (nanoTime() - started).coerceAtLeast(0))
}

private fun rejected(cause: SemanticDependencyCaptureFailure) = Refinement.Rejected(cause)

internal fun SemanticDependencyCaptureFailure.termination(): IntellijReadTermination =
    when (this) {
        SemanticDependencyCaptureFailure.READ_CAPTURE_ENDED -> IntellijReadTermination.SEMANTIC_INPUT_READ_CAPTURE_ENDED
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
        SemanticDependencyCaptureFailure.MINIMUM_HASH_WORK_UNAVAILABLE ->
            IntellijReadTermination.SEMANTIC_INPUT_MINIMUM_HASH_WORK_UNAVAILABLE
        SemanticDependencyCaptureFailure.FILE_CAPTURE_ADMISSION_CONSUMED ->
            IntellijReadTermination.SEMANTIC_INPUT_FILE_CAPTURE_ADMISSION_CONSUMED
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
