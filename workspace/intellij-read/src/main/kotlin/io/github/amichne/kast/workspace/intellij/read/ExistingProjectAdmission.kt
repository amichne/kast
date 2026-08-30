package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
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

/** Exact existing-Project policy proof with no retained read authority or listener installation. */
sealed interface ExistingProjectValidation {
    data object Validated : ExistingProjectValidation

    data class Rejected(
        val failure: ExistingProjectAdmissionFailure,
    ) : ExistingProjectValidation

    companion object {
        /**
         * Proof transition: `(Project, root, host candidate, policy) -> ExistingProjectValidation`.
         *
         * Observes the complete existing-Project admission policy but cannot create a
         * [ProjectReadEpoch.Source]. Validation-only hosted factories therefore receive no
         * project-lifetime authority to discard.
         */
        fun validate(
            project: Project,
            expectedRoot: CanonicalWorkspaceRoot,
            compatibilityCandidate: IdeHostCompatibilityCandidate,
            compatibilityPolicy: IdeHostCompatibilityPolicy,
        ): ExistingProjectValidation = validateObserved(
            project,
            expectedRoot,
            compatibilityCandidate,
            compatibilityPolicy,
            LiveExistingProjectObservation,
        )

        internal fun validateObserved(
            project: Project,
            expectedRoot: CanonicalWorkspaceRoot,
            compatibilityCandidate: IdeHostCompatibilityCandidate,
            compatibilityPolicy: IdeHostCompatibilityPolicy,
            observation: ExistingProjectObservationPort,
        ): ExistingProjectValidation = when (val evidence = validateExistingProject(
            project,
            expectedRoot,
            compatibilityCandidate,
            compatibilityPolicy,
            observation,
        )) {
            is ExistingProjectValidationEvidence.Validated -> Validated
            is ExistingProjectValidationEvidence.Rejected -> Rejected(evidence.failure)
        }
    }
}

/** Non-forgeable package proof that project admission retained the exact live Project. */
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
     * Proof transition: `AdmittedIdeProject -> DetachedModelCapture`.
     *
     * Establishes the complete exact-root cached Gradle model through a suspending write-priority
     * read, so the hosted endpoint path never blocks a thread while waiting for read access.
     * Expected model failures remain [DetachedModelCaptureFailure]; raw Project extraction stays
     * inside [LiveDetachedModelCapture].
     */
    suspend fun captureDetachedModelAsync(): DetachedModelCapture = DetachedIdeWorkspaceModel.admit(
        canonicalRoot,
        compatibility,
        LiveDetachedModelCapture.observeAsync(liveProject.project, canonicalRoot),
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
        internal fun admit(
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
        ): ExistingProjectAdmission = when (val evidence = validateExistingProject(
            project,
            expectedRoot,
            compatibilityCandidate,
            compatibilityPolicy,
            observation,
        )) {
            is ExistingProjectValidationEvidence.Rejected ->
                ExistingProjectAdmission.Rejected(evidence.failure)
            is ExistingProjectValidationEvidence.Validated -> when (
                val installed = readEpochSourceFactory.create(project, evidence.exactRoot)
            ) {
                is Refinement.Refined -> ExistingProjectAdmission.Admitted(
                    AdmittedIdeProject(
                        LiveProjectHandle(project),
                        installed.value,
                        evidence.exactRoot,
                        evidence.compatibility,
                    ),
                )
                is Refinement.Rejected -> when (installed.failure) {
                    ExistingProjectReadEpochSourceInstallationFailure.ProjectDisposed ->
                        ExistingProjectAdmission.Rejected(
                            ExistingProjectAdmissionFailure.ProjectDisposed,
                        )
                }
            }
        }
    }
}

internal fun interface ExistingProjectAdmissionOperations {
    fun admit(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
        compatibilityCandidate: IdeHostCompatibilityCandidate,
        compatibilityPolicy: IdeHostCompatibilityPolicy,
    ): ExistingProjectAdmission
}

/** Project-service session that can install and retain at most one project-read epoch authority. */
class AdmittedIdeProjectSession {
    private var cached: CachedAdmittedIdeProject? = null

    fun admit(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
        compatibilityCandidate: IdeHostCompatibilityCandidate,
        compatibilityPolicy: IdeHostCompatibilityPolicy,
    ): ExistingProjectAdmission = admitUsing(
        project,
        expectedRoot,
        compatibilityCandidate,
        compatibilityPolicy,
        AdmittedIdeProject::admit,
    )

    @Synchronized
    internal fun admitUsing(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
        compatibilityCandidate: IdeHostCompatibilityCandidate,
        compatibilityPolicy: IdeHostCompatibilityPolicy,
        admissions: ExistingProjectAdmissionOperations,
    ): ExistingProjectAdmission {
        val current = cached
        if (current != null) {
            return if (
                current.liveProject === project &&
                current.canonicalRoot == expectedRoot &&
                current.compatibilityCandidate == compatibilityCandidate &&
                current.compatibilityPolicy == compatibilityPolicy
            ) {
                ExistingProjectAdmission.Admitted(current.authority)
            } else {
                ExistingProjectAdmission.Rejected(
                    ExistingProjectAdmissionFailure.RetainedAuthorityMismatch,
                )
            }
        }
        return when (val admission = admissions.admit(
            project,
            expectedRoot,
            compatibilityCandidate,
            compatibilityPolicy,
        )) {
            is ExistingProjectAdmission.Admitted -> {
                cached = CachedAdmittedIdeProject(
                    project,
                    expectedRoot,
                    compatibilityCandidate,
                    compatibilityPolicy,
                    admission.project,
                )
                admission
            }
            is ExistingProjectAdmission.Rejected -> admission
        }
    }
}

private data class CachedAdmittedIdeProject(
    val liveProject: Project,
    val canonicalRoot: CanonicalWorkspaceRoot,
    val compatibilityCandidate: IdeHostCompatibilityCandidate,
    val compatibilityPolicy: IdeHostCompatibilityPolicy,
    val authority: AdmittedIdeProject,
)

private class LiveProjectHandle(val project: Project)

private sealed interface ExistingProjectValidationEvidence {
    data class Validated(
        val exactRoot: CanonicalWorkspaceRoot,
        val compatibility: AdmittedIdeHostCompatibility,
    ) : ExistingProjectValidationEvidence

    data class Rejected(
        val failure: ExistingProjectAdmissionFailure,
    ) : ExistingProjectValidationEvidence
}

private fun validateExistingProject(
    project: Project,
    expectedRoot: CanonicalWorkspaceRoot,
    compatibilityCandidate: IdeHostCompatibilityCandidate,
    compatibilityPolicy: IdeHostCompatibilityPolicy,
    observation: ExistingProjectObservationPort,
): ExistingProjectValidationEvidence {
    val disposed = when (
        val attempt = observe(ExistingProjectObservationStage.DISPOSAL) {
            observation.isDisposed(project)
        }
    ) {
        is ExistingProjectObservation.Observed -> attempt.value
        is ExistingProjectObservation.Failed -> return attempt.rejection()
    }
    if (disposed) {
        return validationRejected(ExistingProjectAdmissionFailure.ProjectDisposed)
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
        return validationRejected(ExistingProjectAdmissionFailure.ProjectNotOpen)
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
        return validationRejected(ExistingProjectAdmissionFailure.ProjectNotInitialized)
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
        ExistingProjectRootObservation.Mismatch -> return validationRejected(
            ExistingProjectAdmissionFailure.ProjectRootMismatch,
        )
        ExistingProjectRootObservation.Unavailable -> return validationRejected(
            ExistingProjectAdmissionFailure.ProjectRootUnavailable,
        )
    }
    if (exactRoot != expectedRoot) {
        return validationRejected(ExistingProjectAdmissionFailure.ProjectRootMismatch)
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
        ExistingProjectGradleModelState.UNAVAILABLE -> return validationRejected(
            ExistingProjectAdmissionFailure.GradleModelUnavailable,
        )
        ExistingProjectGradleModelState.INCOMPLETE -> return validationRejected(
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
        return validationRejected(ExistingProjectAdmissionFailure.DumbMode)
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
        return validationRejected(ExistingProjectAdmissionFailure.K2Unavailable)
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
        is ExistingProjectHostIdentityObservation.Available -> compatibilityCandidate.copy(
            ideBuild = hostIdentity.ideBuild.value,
            kotlinPluginBuild = hostIdentity.kotlinPluginBuild.value,
        )
        is ExistingProjectHostIdentityObservation.Rejected -> return validationRejected(
            ExistingProjectAdmissionFailure.HostIncompatible(hostIdentity.failure),
        )
        ExistingProjectHostIdentityObservation.Unavailable -> return validationRejected(
            ExistingProjectAdmissionFailure.HostIdentityUnavailable,
        )
    }
    return when (val admitted = compatibilityPolicy.admit(observedCandidate)) {
        is IdeHostCompatibilityAdmission.Admitted -> ExistingProjectValidationEvidence.Validated(
            exactRoot,
            admitted.compatibility,
        )
        is IdeHostCompatibilityAdmission.Rejected -> validationRejected(
            ExistingProjectAdmissionFailure.HostIncompatible(admitted.failure),
        )
    }
}

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

private fun ExistingProjectObservation.Failed.rejection() = validationRejected(
    ExistingProjectAdmissionFailure.ObservationFailed(stage),
)

private fun validationRejected(
    failure: ExistingProjectAdmissionFailure,
) = ExistingProjectValidationEvidence.Rejected(failure)
