package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage

/** Live IDEA 262 source for one admitted Project/runtime epoch domain. */
internal class LiveProjectReadEpochSource(
    private val platform: ProjectReadEpochPlatformPort,
    private val projectModelCounter: ProjectReadEpochMetadataCounter,
    private val vfsCounter: ProjectReadEpochMetadataCounter,
    private val execution: ProjectReadEpochExecution = IdeaProjectReadEpochExecution,
    private val limits: ReadLimits = ReadLimits.Default,
    private val observation: (Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>) -> Unit = {},
) {
    internal val source =
        ProjectReadEpoch.Source.createWithEnvironment(
            ::observeState,
            ::observeBeforeWriteState,
            ProjectReadEpochState::environment,
        )

    /** Same constant-size signals, sampled only inside the existing EDT write action. No index query or wait. */
    private fun observeBeforeWriteState(): Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure> {
        val admitted =
            when (
                val result =
                    observe(ProjectReadEpochObservationStage.THREAD) {
                        execution.isDispatchThread() && execution.isWriteAccessAllowed()
                    }
            ) {
                is EpochPlatformObservation.Observed -> result.value
                is EpochPlatformObservation.Failed -> return result.rejection().also(observation)
            }
        if (!admitted) return Refinement.Rejected(ProjectReadEpochObservationFailure.WrongThread).also(observation)
        return observeInsideRead().also(observation)
    }

    /**
     * Proof transition: `LiveProjectReadEpochSource -> Refinement<ProjectReadEpochState,
     * ProjectReadEpochObservationFailure>`.
     *
     * Establishes a smart, lifecycle-current, constant-size sample of every epoch-signal policy authority inside one
     * cancellable IDEA 262 read. Raw platform extraction remains in the live port. Exact `CannotReadException` becomes
     * finite [ProjectReadEpochObservationFailure.ReadPreempted] data; all other cancellation propagates.
     */
    @Suppress("IncorrectCancellationExceptionHandling")
    internal fun observeState():
        Refinement<
            ProjectReadEpochState,
            ProjectReadEpochObservationFailure,
        > {
        val dispatchThread =
            when (
                val observed =
                    observe(
                        ProjectReadEpochObservationStage.THREAD,
                        execution::isDispatchThread,
                    )
            ) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection().also(observation)
            }
        if (dispatchThread) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.WrongThread).also(observation)
        }
        return try {
            execution.compute { observeInsideRead().also(observation) }
        } catch (_: ReadAction.CannotReadException) {
            Refinement.Rejected(ProjectReadEpochObservationFailure.ReadPreempted).also(observation)
        }
    }

    /**
     * Proof transition: `(ProjectReadEpochPlatformPort, ProjectReadEpochMetadataCounter,
     * ProjectReadEpochMetadataCounter) -> Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>`.
     * Establishes one lifecycle-current smart snapshot inside the active read. Each raw platform value is extracted
     * only at its named observation stage and immediately refined or consumed.
     */
    private fun observeInsideRead():
        Refinement<
            ProjectReadEpochState,
            ProjectReadEpochObservationFailure,
        > {
        platform.checkCanceled()
        val disposed =
            when (val observed = observe(ProjectReadEpochObservationStage.DISPOSAL, platform::isDisposed)) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        if (disposed) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.ProjectDisposed)
        }
        val open =
            when (val observed = observe(ProjectReadEpochObservationStage.OPEN, platform::isOpen)) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        if (!open) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.ProjectNotOpen)
        }
        val initialized =
            when (
                val observed =
                    observe(
                        ProjectReadEpochObservationStage.INITIALIZATION,
                        platform::isInitialized,
                    )
            ) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        if (!initialized) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.ProjectNotInitialized)
        }
        val dumb =
            when (val observed = observe(ProjectReadEpochObservationStage.DUMB_MODE, platform::isDumb)) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        if (dumb) return Refinement.Rejected(ProjectReadEpochObservationFailure.DumbMode)

        val rawProjectRoot =
            when (val observed = observe(ProjectReadEpochObservationStage.PROJECT_ROOT, platform::root)) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        val projectRoot =
            when (val refined = ProjectEpochRootIdentity.admit(rawProjectRoot, limits)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        val gradle =
            when (
                val observed =
                    observe(ProjectReadEpochObservationStage.PROJECT_MODEL) {
                        platform.gradleModel(projectRoot)
                    }
            ) {
                is EpochPlatformObservation.Failed -> return observed.rejection()
                is EpochPlatformObservation.Observed ->
                    when (val refined = observed.value) {
                        is Refinement.Refined -> refined.value
                        is Refinement.Rejected -> return refined
                    }
            }
        val psi =
            when (
                val observed =
                    observe(ProjectReadEpochObservationStage.PSI) {
                        platform.psiModificationCount()
                    }
            ) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        val rootModel =
            when (
                val observed =
                    observe(ProjectReadEpochObservationStage.ROOT_MODEL) {
                        platform.rootModelModificationCount()
                    }
            ) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        val dumbCycle =
            when (
                val observed =
                    observe(ProjectReadEpochObservationStage.DUMB_MODE) {
                        platform.dumbModeModificationCount()
                    }
            ) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        val dumbAfter =
            when (val observed = observe(ProjectReadEpochObservationStage.DUMB_MODE, platform::isDumb)) {
                is EpochPlatformObservation.Observed -> observed.value
                is EpochPlatformObservation.Failed -> return observed.rejection()
            }
        if (dumbAfter) return Refinement.Rejected(ProjectReadEpochObservationFailure.DumbMode)
        platform.checkCanceled()
        return ProjectReadEpochState.admit(
            ProjectReadEpochBoundary(
                projectModelRevision = projectModelCounter.sample(),
                projectRoot = projectRoot,
                gradleRoot = gradle.root,
                lastImportTimestamp = gradle.lastImportTimestamp,
                lastSuccessfulImportTimestamp = gradle.lastSuccessfulImportTimestamp,
                psiModificationCount = ProjectReadEpochSignalSample.Value(psi),
                rootFilteredVfsBatchCount = vfsCounter.sample(),
                rootModelModificationCount = ProjectReadEpochSignalSample.Value(rootModel),
                dumbModeModificationCount = ProjectReadEpochSignalSample.Value(dumbCycle),
                dumb = false,
            )
        )
    }
}

/** Raw live-platform extraction boundary consumed only by the typed epoch transition. */
internal interface ProjectReadEpochPlatformPort {
    fun checkCanceled()

    fun isDisposed(): Boolean

    fun isOpen(): Boolean

    fun isInitialized(): Boolean

    fun isDumb(): Boolean

    fun root(): String?

    /**
     * Proof transition: `ProjectEpochRootIdentity -> Refinement<ObservedEpochGradleModel,
     * ProjectReadEpochObservationFailure>`. Establishes one ready bounded unambiguous cached model, preferring the
     * exact admitted root while retaining sole moved-root evidence. Raw extraction stays in the live adapter.
     */
    fun gradleModel(
        projectRoot: ProjectEpochRootIdentity
    ): Refinement<ObservedEpochGradleModel, ProjectReadEpochObservationFailure>

    fun psiModificationCount(): Long

    fun rootModelModificationCount(): Long

    fun dumbModeModificationCount(): Long
}

/** Explicit EDT and cancellable-read effect boundary for epoch observation. */
private sealed interface EpochPlatformObservation<out Value> {
    data class Observed<Value>(val value: Value) : EpochPlatformObservation<Value>

    data class Failed(val stage: ProjectReadEpochObservationStage) : EpochPlatformObservation<Nothing>
}

/**
 * Proof transition: `(ProjectReadEpochObservationStage, () -> Value) -> EpochPlatformObservation<Value>`.
 *
 * Establishes either the value produced by the named live-platform extraction boundary or the exact closed
 * `EpochPlatformObservation.Failed(stage)`. Raw extraction is permitted only in [LiveProjectReadEpochPlatformPort];
 * [ProcessCanceledException] propagates to its caller.
 */
private inline fun <Value> observe(
    stage: ProjectReadEpochObservationStage,
    operation: () -> Value,
): EpochPlatformObservation<Value> =
    try {
        EpochPlatformObservation.Observed(operation())
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (_: RuntimeException) {
        EpochPlatformObservation.Failed(stage)
    }

private fun EpochPlatformObservation.Failed.rejection() =
    Refinement.Rejected(ProjectReadEpochObservationFailure.ObservationFailed(stage))

private const val MAX_CACHED_GRADLE_MODELS = 16
