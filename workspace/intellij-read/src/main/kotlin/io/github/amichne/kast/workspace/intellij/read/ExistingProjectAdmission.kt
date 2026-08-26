package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.IdeBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.KotlinPluginBuildIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadCapability
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionAdmission
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecution

/** Finite observation stages at the existing-Project boundary. */
enum class ExistingProjectObservationStage {
    DISPOSAL,
    OPEN,
    INITIALIZATION,
    ROOT,
    GRADLE_MODEL,
    INDEXING,
    KOTLIN_MODE,
    HOST_IDENTITY,
}

/** Closed expected failures for `Project -> AdmittedIdeProject`. */
sealed interface ExistingProjectAdmissionFailure {
    data object ProjectDisposed : ExistingProjectAdmissionFailure
    data object ProjectNotOpen : ExistingProjectAdmissionFailure
    data object ProjectNotInitialized : ExistingProjectAdmissionFailure
    data object ProjectRootUnavailable : ExistingProjectAdmissionFailure
    data object ProjectRootMismatch : ExistingProjectAdmissionFailure
    data object GradleModelUnavailable : ExistingProjectAdmissionFailure
    data object GradleModelIncomplete : ExistingProjectAdmissionFailure
    data object DumbMode : ExistingProjectAdmissionFailure
    data object K2Unavailable : ExistingProjectAdmissionFailure
    data object HostIdentityUnavailable : ExistingProjectAdmissionFailure
    data class HostIncompatible(
        val cause: IdeHostCompatibilityFailure,
    ) : ExistingProjectAdmissionFailure
    data class ObservationFailed(
        val stage: ExistingProjectObservationStage,
    ) : ExistingProjectAdmissionFailure
}

/** Closed result of attempting to admit one existing IntelliJ Project. */
sealed interface ExistingProjectAdmission {
    data class Admitted(
        val project: AdmittedIdeProject,
    ) : ExistingProjectAdmission

    data class Rejected(
        val failure: ExistingProjectAdmissionFailure,
    ) : ExistingProjectAdmission
}

/** Cached Gradle model state observed without import or repair. */
enum class ExistingProjectGradleModelState {
    UNAVAILABLE,
    INCOMPLETE,
    COMPLETE,
}

/** Current IntelliJ indexing state observed without waiting. */
enum class ExistingProjectIndexingState {
    DUMB,
    SMART,
}

/** Current Kotlin frontend mode observed from the installed Kotlin plugin. */
enum class ExistingProjectKotlinMode {
    K1,
    K2,
}

/** Detached result of refining the supplied Project root. */
sealed interface ExistingProjectRootObservation {
    data class Available(
        val root: CanonicalWorkspaceRoot,
    ) : ExistingProjectRootObservation

    data object Mismatch : ExistingProjectRootObservation
    data object Unavailable : ExistingProjectRootObservation
}

/** Detached running-host identity observed from the loaded IDE and Kotlin plugins. */
sealed interface ExistingProjectHostIdentityObservation {
    data class Available(
        val ideBuild: IdeBuildIdentity,
        val kotlinPluginBuild: KotlinPluginBuildIdentity,
    ) : ExistingProjectHostIdentityObservation

    data class Rejected(
        val failure: IdeHostCompatibilityFailure,
    ) : ExistingProjectHostIdentityObservation

    data object Unavailable : ExistingProjectHostIdentityObservation
}

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

/** Non-forgeable package proof that KVP-014 retained the exact live Project. */
internal sealed interface AdmittedProjectReadExecutionProof
private data object RetainedAdmittedProjectProof : AdmittedProjectReadExecutionProof

/** Exact-root compatible live read authority; its [Project] has no generic accessor or escape. */
class AdmittedIdeProject private constructor(
    private val liveProject: LiveProjectHandle,
    private val readEpochSource: ProjectReadEpoch.Source<*>,
    val canonicalRoot: CanonicalWorkspaceRoot,
    val compatibility: AdmittedIdeHostCompatibility,
) {
    /**
     * `AdmittedIdeProject -> DetachedModelCapture`; returns an exact detached model or closed
     * [DetachedModelCaptureFailure]. Raw extraction stays inside [LiveDetachedModelCapture].
     */
    fun captureDetachedModel(): DetachedModelCapture = DetachedIdeWorkspaceModel.admit(
        canonicalRoot,
        compatibility,
        LiveDetachedModelCapture.observe(liveProject.project, canonicalRoot),
    )

    /**
     * `AdmittedIdeProject -> ProjectReadEpochObservation`; returns one opaque retained-source epoch
     * or [ProjectReadEpochObservationFailure], without exposing Project or raw signal values.
     */
    fun observeReadEpoch(): ProjectReadEpochObservation = readEpochSource.observe()

    /**
     * `(AdmittedIdeProject, ProjectReadEpoch<*>) -> VfsPassiveReadAdmission`; one observation proves
     * unchanged same-source state or [VfsPassiveReadAdmissionFailure]. Raw Project stays confined.
     */
    fun admitVfsPassiveRead(
        expectedEpoch: ProjectReadEpoch<*>,
    ): VfsPassiveReadAdmission = admitVfsPassiveReadObservation(
        canonicalRoot,
        expectedEpoch,
        readEpochSource.observe(),
    )

    /**
     * `(AdmittedIdeProject, VfsPassiveReadCapability) -> execution admission`; one observation
     * issues authority only for unchanged same-source freshness, with Project remaining private.
     */
    internal fun cancellableReadExecution(
        freshness: VfsPassiveReadCapability,
    ): AdmittedProjectReadExecutionAdmission {
        if (freshness.canonicalRoot != canonicalRoot) {
            return AdmittedProjectReadExecutionAdmission.Rejected(
                AdmittedProjectReadExecutionAdmissionFailure.WrongProject,
            )
        }
        return when (val admitted = admitVfsPassiveRead(freshness.admittedEpoch)) {
            is VfsPassiveReadAdmission.Admitted -> AdmittedProjectReadExecutionAdmission.Admitted(
                AdmittedProjectReadExecution.bind(
                    liveProject.project,
                    RetainedAdmittedProjectProof,
                ),
                admitted.capability,
            )
            is VfsPassiveReadAdmission.Rejected -> AdmittedProjectReadExecutionAdmission.Rejected(
                AdmittedProjectReadExecutionAdmissionFailure.FreshnessRejected(admitted.failure),
            )
        }
    }

    companion object {
        /**
         * `(Project, root, host candidate, policy) -> ExistingProjectAdmission`; proves open,
         * initialized, exact-root, complete-model, smart, K2, compatible state or closed failure.
         */
        fun admit(
            project: Project,
            expectedRoot: CanonicalWorkspaceRoot,
            compatibilityCandidate: IdeHostCompatibilityCandidate,
            compatibilityPolicy: IdeHostCompatibilityPolicy,
        ): ExistingProjectAdmission = admitObserved(
            project,
            expectedRoot,
            compatibilityCandidate,
            compatibilityPolicy,
            LiveExistingProjectObservation,
            LiveProjectReadEpochSourceFactory,
        )

        /**
         * `(Project, root, host policy, observation port, epoch factory) -> admission`; proves the
         * [admit] invariants or closed failure while raw Project stays at the observation boundary.
         */
        internal fun admitObserved(
            project: Project,
            expectedRoot: CanonicalWorkspaceRoot,
            compatibilityCandidate: IdeHostCompatibilityCandidate,
            compatibilityPolicy: IdeHostCompatibilityPolicy,
            observation: ExistingProjectObservationPort,
            readEpochSourceFactory: ExistingProjectReadEpochSourceFactory,
        ): ExistingProjectAdmission {
            val disposed = when (
                val attempt = observe(ExistingProjectObservationStage.DISPOSAL) {
                    observation.isDisposed(project)
                }
            ) {
                is ExistingProjectObservation.Observed -> attempt.value
                is ExistingProjectObservation.Failed -> return attempt.rejection()
            }
            if (disposed) {
                return ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.ProjectDisposed,
                )
            }
            val open = when (
                val attempt = observe(ExistingProjectObservationStage.OPEN) {
                    observation.isOpen(project)
                }
            ) {
                is ExistingProjectObservation.Observed -> attempt.value
                is ExistingProjectObservation.Failed -> return attempt.rejection()
            }
            if (!open) {
                return ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.ProjectNotOpen,
                )
            }
            val initialized = when (
                val attempt = observe(ExistingProjectObservationStage.INITIALIZATION) {
                    observation.isInitialized(project)
                }
            ) {
                is ExistingProjectObservation.Observed -> attempt.value
                is ExistingProjectObservation.Failed -> return attempt.rejection()
            }
            if (!initialized) {
                return ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.ProjectNotInitialized,
                )
            }
            val observedRoot = when (
                val attempt = observe(ExistingProjectObservationStage.ROOT) {
                    observation.root(project, expectedRoot)
                }
            ) {
                is ExistingProjectObservation.Observed -> attempt.value
                is ExistingProjectObservation.Failed -> return attempt.rejection()
            }
            val exactRoot = when (observedRoot) {
                is ExistingProjectRootObservation.Available -> observedRoot.root
                ExistingProjectRootObservation.Mismatch -> return ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.ProjectRootMismatch,
                )
                ExistingProjectRootObservation.Unavailable -> return ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.ProjectRootUnavailable,
                )
            }
            if (exactRoot != expectedRoot) {
                return ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.ProjectRootMismatch,
                )
            }
            val gradleModel = when (
                val attempt = observe(ExistingProjectObservationStage.GRADLE_MODEL) {
                    observation.gradleModel(project, expectedRoot)
                }
            ) {
                is ExistingProjectObservation.Observed -> attempt.value
                is ExistingProjectObservation.Failed -> return attempt.rejection()
            }
            when (gradleModel) {
                ExistingProjectGradleModelState.UNAVAILABLE ->
                    return ExistingProjectAdmission.Rejected(
                        ExistingProjectAdmissionFailure.GradleModelUnavailable,
                    )
                ExistingProjectGradleModelState.INCOMPLETE ->
                    return ExistingProjectAdmission.Rejected(
                        ExistingProjectAdmissionFailure.GradleModelIncomplete,
                    )
                ExistingProjectGradleModelState.COMPLETE -> Unit
            }
            val indexing = when (
                val attempt = observe(ExistingProjectObservationStage.INDEXING) {
                    observation.indexing(project)
                }
            ) {
                is ExistingProjectObservation.Observed -> attempt.value
                is ExistingProjectObservation.Failed -> return attempt.rejection()
            }
            if (indexing == ExistingProjectIndexingState.DUMB) {
                return ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.DumbMode,
                )
            }
            val kotlinMode = when (
                val attempt = observe(ExistingProjectObservationStage.KOTLIN_MODE) {
                    observation.kotlinMode()
                }
            ) {
                is ExistingProjectObservation.Observed -> attempt.value
                is ExistingProjectObservation.Failed -> return attempt.rejection()
            }
            if (kotlinMode != ExistingProjectKotlinMode.K2) {
                return ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.K2Unavailable,
                )
            }

            val hostIdentity = when (
                val attempt = observe(ExistingProjectObservationStage.HOST_IDENTITY) {
                    observation.hostIdentity()
                }
            ) {
                is ExistingProjectObservation.Observed -> attempt.value
                is ExistingProjectObservation.Failed -> return attempt.rejection()
            }
            val observedCandidate = when (hostIdentity) {
                is ExistingProjectHostIdentityObservation.Available ->
                    compatibilityCandidate.copy(
                        ideBuild = hostIdentity.ideBuild.value,
                        kotlinPluginBuild = hostIdentity.kotlinPluginBuild.value,
                    )
                is ExistingProjectHostIdentityObservation.Rejected ->
                    return ExistingProjectAdmission.Rejected(
                        ExistingProjectAdmissionFailure.HostIncompatible(hostIdentity.failure),
                    )
                ExistingProjectHostIdentityObservation.Unavailable ->
                    return ExistingProjectAdmission.Rejected(
                        ExistingProjectAdmissionFailure.HostIdentityUnavailable,
                    )
            }

            return when (val admitted = compatibilityPolicy.admit(observedCandidate)) {
                is IdeHostCompatibilityAdmission.Admitted -> when (
                    val installed = readEpochSourceFactory.create(project, exactRoot)
                ) {
                    is Refinement.Refined -> ExistingProjectAdmission.Admitted(
                        AdmittedIdeProject(
                            LiveProjectHandle(project),
                            installed.value,
                            exactRoot,
                            admitted.compatibility,
                        ),
                    )
                    is Refinement.Rejected -> when (installed.failure) {
                        ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed ->
                            ExistingProjectAdmission.Rejected(ExistingProjectAdmissionFailure.ProjectDisposed)
                    }
                }
                is IdeHostCompatibilityAdmission.Rejected -> ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.HostIncompatible(admitted.failure),
                )
            }
        }
    }
}

private class LiveProjectHandle(val project: Project)

private sealed interface ExistingProjectObservation<out Value> {
    data class Observed<Value>(val value: Value) : ExistingProjectObservation<Value>
    data class Failed(val stage: ExistingProjectObservationStage) : ExistingProjectObservation<Nothing>
}

private inline fun <Value> observe(
    stage: ExistingProjectObservationStage,
    read: () -> Value,
): ExistingProjectObservation<Value> = try {
    ExistingProjectObservation.Observed(read())
} catch (cancelled: ProcessCanceledException) {
    throw cancelled
} catch (_: RuntimeException) {
    ExistingProjectObservation.Failed(stage)
}

private fun ExistingProjectObservation.Failed.rejection() = ExistingProjectAdmission.Rejected(
    ExistingProjectAdmissionFailure.ObservationFailed(stage),
)
