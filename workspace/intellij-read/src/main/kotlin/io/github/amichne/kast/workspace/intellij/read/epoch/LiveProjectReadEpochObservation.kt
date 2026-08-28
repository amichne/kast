package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.WorkspaceModelChangeListener
import com.intellij.platform.backend.workspace.WorkspaceModelTopics
import com.intellij.platform.workspace.storage.VersionedStorageChange
import com.intellij.psi.util.PsiModificationTracker
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationStage

/** Typed installation of the one project-read epoch source retained by an admitted Project/runtime. */
internal object LiveProjectReadEpochSourceFactory : ExistingProjectReadEpochSourceFactory {
    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) ->
     * Refinement<ProjectReadEpoch.Source<*>,
     * ExistingProjectReadEpochSourceInstallationFailure>`.
     *
     * Establishes project-lifetime workspace-model and root-filtered VFS metadata subscriptions.
     * Raw listeners and counters never escape this adapter boundary; unexpected subscription
     * defects propagate instead of being mislabeled as an observation-stage failure.
     */
    override fun create(
        project: Project,
        root: CanonicalWorkspaceRoot,
    ): Refinement<
        ProjectReadEpoch.Source<*>,
        ExistingProjectReadEpochSourceInstallationFailure,
    > {
        if (project.isDisposed) {
            return Refinement.Rejected(
                ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed,
            )
        }
        val projectModelCounter = ProjectReadEpochMetadataCounter()
        val vfsCounter = ProjectReadEpochMetadataCounter()
        val rootIdentity = ProjectReadEpochVfsRoot.from(root)
        return try {
            val connection = project.messageBus.connect(project)
            connection.subscribe(
                WorkspaceModelTopics.CHANGED,
                object : WorkspaceModelChangeListener {
                    override fun changed(event: VersionedStorageChange) {
                        projectModelCounter.advance()
                    }
                },
            )
            connection.subscribe(
                VirtualFileManager.VFS_CHANGES,
                RootFilteredProjectEpochVfsListener(rootIdentity, vfsCounter),
            )
            if (project.isDisposed) {
                Refinement.Rejected(
                    ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed,
                )
            } else {
                Refinement.Refined(LiveProjectReadEpochSource(
                    LiveProjectReadEpochPlatformPort(project),
                    projectModelCounter,
                    vfsCounter,
                ).source)
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            if (project.isDisposed) {
                Refinement.Rejected(
                    ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed,
                )
            } else {
                throw failure
            }
        }
    }
}

/** Live IDEA 262 source for one admitted Project/runtime epoch domain. */
internal class LiveProjectReadEpochSource(
    private val platform: ProjectReadEpochPlatformPort,
    private val projectModelCounter: ProjectReadEpochMetadataCounter,
    private val vfsCounter: ProjectReadEpochMetadataCounter,
    private val execution: ProjectReadEpochExecution = IdeaProjectReadEpochExecution,
) {
    internal val source = ProjectReadEpoch.Source.create(::observeState)

    /**
     * Proof transition: `LiveProjectReadEpochSource ->
     * Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>`.
     *
     * Establishes a smart, lifecycle-current, constant-size sample of every epoch-signal policy authority
     * inside one cancellable IDEA 262 read. Raw platform extraction remains in the live port.
     * Exact `CannotReadException` becomes finite [ProjectReadEpochObservationFailure.ReadPreempted]
     * data; all other cancellation propagates.
     */
    @Suppress("IncorrectCancellationExceptionHandling")
    internal fun observeState(): Refinement<
        ProjectReadEpochState,
        ProjectReadEpochObservationFailure,
    > {
        val dispatchThread = when (
            val observed = observe(
                ProjectReadEpochObservationStage.THREAD,
                execution::isDispatchThread,
            )
        ) {
            is EpochPlatformObservation.Observed -> observed.value
            is EpochPlatformObservation.Failed -> return observed.rejection()
        }
        if (dispatchThread) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.WrongThread)
        }
        return try {
            execution.compute(::observeInsideRead)
        } catch (_: ReadAction.CannotReadException) {
            Refinement.Rejected(ProjectReadEpochObservationFailure.ReadPreempted)
        }
    }

    /**
     * Proof transition: `(ProjectReadEpochPlatformPort, ProjectReadEpochMetadataCounter,
     * ProjectReadEpochMetadataCounter) -> Refinement<ProjectReadEpochState,
     * ProjectReadEpochObservationFailure>`.
     * Establishes one lifecycle-current smart snapshot inside the active read. Each raw platform
     * value is extracted only at its named observation stage and immediately refined or consumed.
     */
    private fun observeInsideRead(): Refinement<
        ProjectReadEpochState,
        ProjectReadEpochObservationFailure,
    > {
        platform.checkCanceled()
        val disposed = when (
            val observed = observe(ProjectReadEpochObservationStage.DISPOSAL, platform::isDisposed)
        ) {
            is EpochPlatformObservation.Observed -> observed.value
            is EpochPlatformObservation.Failed -> return observed.rejection()
        }
        if (disposed) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.ProjectDisposed)
        }
        val open = when (
            val observed = observe(ProjectReadEpochObservationStage.OPEN, platform::isOpen)
        ) {
            is EpochPlatformObservation.Observed -> observed.value
            is EpochPlatformObservation.Failed -> return observed.rejection()
        }
        if (!open) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.ProjectNotOpen)
        }
        val initialized = when (
            val observed = observe(
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
        val dumb = when (
            val observed = observe(ProjectReadEpochObservationStage.DUMB_MODE, platform::isDumb)
        ) {
            is EpochPlatformObservation.Observed -> observed.value
            is EpochPlatformObservation.Failed -> return observed.rejection()
        }
        if (dumb) return Refinement.Rejected(ProjectReadEpochObservationFailure.DumbMode)

        val rawProjectRoot = when (
            val observed = observe(ProjectReadEpochObservationStage.PROJECT_ROOT, platform::root)
        ) {
            is EpochPlatformObservation.Observed -> observed.value
            is EpochPlatformObservation.Failed -> return observed.rejection()
        }
        val projectRoot = when (val refined = ProjectEpochRootIdentity.admit(rawProjectRoot)) {
            is Refinement.Refined -> refined.value
            is Refinement.Rejected -> return refined
        }
        val gradle = when (
            val observed = observe(ProjectReadEpochObservationStage.PROJECT_MODEL) {
                platform.gradleModel(projectRoot)
            }
        ) {
            is EpochPlatformObservation.Failed -> return observed.rejection()
            is EpochPlatformObservation.Observed -> when (val refined = observed.value) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
        }
        val psi = when (
            val observed = observe(ProjectReadEpochObservationStage.PSI) {
                platform.psiModificationCount()
            }
        ) {
            is EpochPlatformObservation.Observed -> observed.value
            is EpochPlatformObservation.Failed -> return observed.rejection()
        }
        val rootModel = when (
            val observed = observe(ProjectReadEpochObservationStage.ROOT_MODEL) {
                platform.rootModelModificationCount()
            }
        ) {
            is EpochPlatformObservation.Observed -> observed.value
            is EpochPlatformObservation.Failed -> return observed.rejection()
        }
        val dumbCycle = when (
            val observed = observe(ProjectReadEpochObservationStage.DUMB_MODE) {
                platform.dumbModeModificationCount()
            }
        ) {
            is EpochPlatformObservation.Observed -> observed.value
            is EpochPlatformObservation.Failed -> return observed.rejection()
        }
        val dumbAfter = when (
            val observed = observe(ProjectReadEpochObservationStage.DUMB_MODE, platform::isDumb)
        ) {
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
            ),
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
     * ProjectReadEpochObservationFailure>`.
     * Establishes one ready bounded unambiguous cached model, preferring the exact admitted root
     * while retaining sole moved-root evidence. Raw extraction stays in the live adapter.
     */
    fun gradleModel(
        projectRoot: ProjectEpochRootIdentity,
    ): Refinement<ObservedEpochGradleModel, ProjectReadEpochObservationFailure>
    fun psiModificationCount(): Long
    fun rootModelModificationCount(): Long
    fun dumbModeModificationCount(): Long
}

private class LiveProjectReadEpochPlatformPort(
    private val project: Project,
) : ProjectReadEpochPlatformPort {
    override fun checkCanceled() = ProgressManager.checkCanceled()
    override fun isDisposed(): Boolean = project.isDisposed
    override fun isOpen(): Boolean = project.isOpen
    override fun isInitialized(): Boolean = project.isInitialized
    override fun isDumb(): Boolean = DumbService.getInstance(project).isDumb
    override fun root(): String? = project.basePath

    /**
     * Proof transition: `(Project, ProjectEpochRootIdentity) ->
     * Refinement<ObservedEpochGradleModel, ProjectReadEpochObservationFailure>`.
     * Establishes one ready bounded unambiguous cached model, preferring an exact root while
     * retaining sole moved-root evidence. Raw Gradle extraction is permitted only here.
     */
    override fun gradleModel(
        projectRoot: ProjectEpochRootIdentity,
    ): Refinement<ObservedEpochGradleModel, ProjectReadEpochObservationFailure> {
        val infos = ProjectDataManager.getInstance().getExternalProjectsData(
            project,
            ProjectSystemId("GRADLE"),
        )
        if (infos.isEmpty()) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.GradleModelUnavailable)
        }
        if (infos.size > MAX_CACHED_GRADLE_MODELS) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.GradleModelAmbiguous)
        }
        val admitted = ArrayList<Pair<ExternalProjectInfo, ObservedEpochGradleModel>>(infos.size)
        for (info in infos) {
            val root = when (val refined = GradleEpochRootIdentity.admit(info.externalProjectPath)) {
                is Refinement.Refined -> refined.value
                is Refinement.Rejected -> return refined
            }
            admitted += info to ObservedEpochGradleModel(
                root,
                info.lastImportTimestamp,
                info.lastSuccessfulImportTimestamp,
            )
        }
        val exact = admitted.asSequence()
            .filter { model ->
                projectRoot.relationTo(model.second.root) == ProjectGradleRootRelation.SAME
            }
            .take(2)
            .toList()
        val selected = when {
            exact.size == 1 -> exact.single()
            exact.size > 1 -> return Refinement.Rejected(
                ProjectReadEpochObservationFailure.GradleModelAmbiguous,
            )
            admitted.size == 1 -> admitted.single()
            else -> return Refinement.Rejected(
                ProjectReadEpochObservationFailure.GradleModelAmbiguous,
            )
        }
        val selectedInfo = selected.first
        if (selectedInfo.externalProjectStructure?.isReady != true) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.GradleModelIncomplete)
        }
        return Refinement.Refined(selected.second)
    }

    override fun psiModificationCount(): Long =
        PsiModificationTracker.getInstance(project).modificationCount

    override fun rootModelModificationCount(): Long =
        ProjectRootModificationTracker.getInstance(project).modificationCount

    override fun dumbModeModificationCount(): Long =
        DumbService.getInstance(project).modificationTracker.modificationCount
}

/** Explicit EDT and cancellable-read effect boundary for epoch observation. */
internal interface ProjectReadEpochExecution {
    fun isDispatchThread(): Boolean
    fun compute(
        read: () -> Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>,
    ): Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>
}

private object IdeaProjectReadEpochExecution : ProjectReadEpochExecution {
    override fun isDispatchThread(): Boolean = ApplicationManager.getApplication().isDispatchThread

    override fun compute(
        read: () -> Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>,
    ): Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure> =
        ReadAction.computeCancellable<
            Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>,
            RuntimeException,
        >(read)
}

private sealed interface EpochPlatformObservation<out Value> {
    data class Observed<Value>(val value: Value) : EpochPlatformObservation<Value>
    data class Failed(val stage: ProjectReadEpochObservationStage) :
        EpochPlatformObservation<Nothing>
}

/**
 * Proof transition: `(ProjectReadEpochObservationStage, () -> Value) ->
 * EpochPlatformObservation<Value>`.
 *
 * Establishes either the value produced by the named live-platform extraction boundary or the
 * exact closed `EpochPlatformObservation.Failed(stage)`. Raw extraction is permitted only in
 * [LiveProjectReadEpochPlatformPort]; [ProcessCanceledException] propagates to its caller.
 */
private inline fun <Value> observe(
    stage: ProjectReadEpochObservationStage,
    operation: () -> Value,
): EpochPlatformObservation<Value> = try {
    EpochPlatformObservation.Observed(operation())
} catch (cancelled: ProcessCanceledException) {
    throw cancelled
} catch (_: RuntimeException) {
    EpochPlatformObservation.Failed(stage)
}

private fun EpochPlatformObservation.Failed.rejection() = Refinement.Rejected(
    ProjectReadEpochObservationFailure.ObservationFailed(stage),
)

private const val MAX_CACHED_GRADLE_MODELS = 16
