package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.Disposable
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
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure

/** Typed installation of the one project-read epoch source retained by an admitted Project/runtime. */
internal object LiveProjectReadEpochSourceFactory : ExistingProjectReadEpochSourceFactory {
    /**
     * Proof transition: `(Project, CanonicalWorkspaceRoot) -> Refinement<ProjectReadEpoch.Source<*>,
     * ExistingProjectReadEpochSourceInstallationFailure>`.
     *
     * Establishes project-lifetime workspace-model and root-filtered VFS metadata subscriptions. Raw listeners and
     * counters never escape this adapter boundary; unexpected subscription defects propagate instead of being
     * mislabeled as an observation-stage failure.
     */
    override fun create(
        project: Project,
        root: CanonicalWorkspaceRoot,
    ): Refinement<
        ProjectReadEpoch.Source<*>,
        ExistingProjectReadEpochSourceInstallationFailure,
    > = createOwned(project, root, project)

    /** The host service supplies its own disposable so detach also disconnects epoch listeners. */
    // Platform subscriptions can fail with arbitrary runtime defects. Only disposed lifecycle maps to rejection;
    // cancellation and every failure in a live project retain the original exception.
    @Suppress("TooGenericExceptionCaught")
    internal fun createOwned(
        project: Project,
        root: CanonicalWorkspaceRoot,
        owner: Disposable,
        limits: ReadLimits = ReadLimits.Default,
        observation: (Refinement<ProjectReadEpochState, ProjectReadEpochObservationFailure>) -> Unit = {},
    ): Refinement<ProjectReadEpoch.Source<*>, ExistingProjectReadEpochSourceInstallationFailure> {
        if (project.isDisposed) {
            return Refinement.Rejected(ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed)
        }
        val projectModelCounter = ProjectReadEpochMetadataCounter()
        val vfsCounter = ProjectReadEpochMetadataCounter()
        val rootIdentity = ProjectReadEpochVfsRoot.from(root)
        return try {
            val connection = project.messageBus.connect(owner)
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
                RootFilteredProjectEpochVfsListener(rootIdentity, vfsCounter, limits),
            )
            if (project.isDisposed) {
                Refinement.Rejected(ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed)
            } else {
                Refinement.Refined(
                    LiveProjectReadEpochSource(
                            LiveProjectReadEpochPlatformPort(project, limits),
                            projectModelCounter,
                            vfsCounter,
                            limits = limits,
                            observation = observation,
                        )
                        .source
                )
            }
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            if (project.isDisposed) {
                Refinement.Rejected(ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed)
            } else {
                throw failure
            }
        }
    }
}

private class LiveProjectReadEpochPlatformPort(
    private val project: Project,
    private val limits: ReadLimits,
) : ProjectReadEpochPlatformPort {
    override fun checkCanceled() = ProgressManager.checkCanceled()

    override fun isDisposed(): Boolean = project.isDisposed

    override fun isOpen(): Boolean = project.isOpen

    override fun isInitialized(): Boolean = project.isInitialized

    override fun isDumb(): Boolean = DumbService.getInstance(project).isDumb

    override fun root(): String? = project.basePath

    /**
     * Proof transition: `(Project, ProjectEpochRootIdentity) -> Refinement<ObservedEpochGradleModel,
     * ProjectReadEpochObservationFailure>`. Establishes one ready bounded unambiguous cached model, preferring an exact
     * root while retaining sole moved-root evidence. Raw Gradle extraction is permitted only here.
     */
    override fun gradleModel(
        projectRoot: ProjectEpochRootIdentity
    ): Refinement<ObservedEpochGradleModel, ProjectReadEpochObservationFailure> {
        val infos =
            ProjectDataManager.getInstance()
                .getExternalProjectsData(
                    project,
                    ProjectSystemId("GRADLE"),
                )
        if (infos.isEmpty()) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.GradleModelUnavailable)
        }
        if (infos.size > limits[ReadLimitParameter.EPOCH_CACHED_GRADLE_MODELS].value) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.GradleModelAmbiguous)
        }
        val admitted = ArrayList<Pair<ExternalProjectInfo, ObservedEpochGradleModel>>(infos.size)
        for (info in infos) {
            val root =
                when (val refined = GradleEpochRootIdentity.admit(info.externalProjectPath, limits)) {
                    is Refinement.Refined -> refined.value
                    is Refinement.Rejected -> return refined
                }
            admitted +=
                info to
                    ObservedEpochGradleModel(
                        root,
                        info.lastImportTimestamp,
                        info.lastSuccessfulImportTimestamp,
                    )
        }
        val exact =
            admitted
                .asSequence()
                .filter { model ->
                    projectRoot.relationTo(model.second.root) == ProjectGradleRootRelation.SAME
                }
                .take(2)
                .toList()
        val selected =
            when {
                exact.size == 1 -> exact.single()
                exact.size > 1 -> return Refinement.Rejected(ProjectReadEpochObservationFailure.GradleModelAmbiguous)
                admitted.size == 1 -> admitted.single()
                else -> return Refinement.Rejected(ProjectReadEpochObservationFailure.GradleModelAmbiguous)
            }
        val selectedInfo = selected.first
        if (selectedInfo.externalProjectStructure?.isReady != true) {
            return Refinement.Rejected(ProjectReadEpochObservationFailure.GradleModelIncomplete)
        }
        return Refinement.Refined(selected.second)
    }

    override fun psiModificationCount(): Long = PsiModificationTracker.getInstance(project).modificationCount

    override fun rootModelModificationCount(): Long =
        ProjectRootModificationTracker.getInstance(project).modificationCount

    override fun dumbModeModificationCount(): Long =
        DumbService.getInstance(project).modificationTracker.modificationCount
}
