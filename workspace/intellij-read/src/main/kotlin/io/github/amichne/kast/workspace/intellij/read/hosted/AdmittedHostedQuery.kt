package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.project.Project
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmission
import io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.DetachedModelCapture
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionAdmission
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionAdmissionFailure
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionFailure
import io.github.amichne.kast.workspace.intellij.read.epoch.execution.AdmittedProjectReadExecutionResult

internal sealed interface HostedReadPreparation<out Evidence> {
    data class Prepared<Evidence>(val epoch: ProjectReadEpoch<*>, val model: DetachedIdeWorkspaceModel, val evidence: Evidence) : HostedReadPreparation<Evidence>
    data class Rejected(val failure: HostedQueryFailure) : HostedReadPreparation<Nothing>
}

/** No isolated opener, import, refresh, or generic live-project escape is reachable here. */
internal suspend fun AdmittedIdeProject.prepareHostedQuery(
    selection: HostedKotlinSelection,
    progress: HostedQueryProgress,
    checkpoint: HostedReadCheckpoint,
): HostedReadPreparation<HostedInheritorEvidence> = prepareHostedRead(
    selection.root, progress, checkpoint,
    { project, model -> readHostedKotlin(project, selection, model) }, ::verifyHostedContent,
)

/** Shared read boundary; compiler/index values are detached before the checkpoint and transport. */
internal suspend fun <Evidence : Any> AdmittedIdeProject.prepareHostedRead(
    root: CanonicalWorkspaceRoot,
    progress: HostedQueryProgress,
    checkpoint: HostedReadCheckpoint,
    read: (Project, DetachedIdeWorkspaceModel) -> HostedSemanticRead<Evidence>,
    verify: (Project, Evidence) -> SavedDocuments,
): HostedReadPreparation<Evidence> {
    if (canonicalRoot != root) return rejected(HostedQueryFailure.WRONG_PROJECT)
    progress.advance(HostedQueryStage.EPOCH_OBSERVATION)
    val epoch = when (val observation = observeReadEpoch()) {
        is ProjectReadEpochObservation.Observed -> observation.epoch
        is ProjectReadEpochObservation.Rejected -> return rejected(HostedQueryFailure.ReadEpoch(observation.failure))
    }
    progress.advance(HostedQueryStage.MODEL_CAPTURE)
    val model = when (val captured = captureDetachedModelAsync()) {
        is DetachedModelCapture.Captured -> captured.model
        is DetachedModelCapture.Rejected -> return rejected(HostedQueryFailure.ModelCapture(captured))
    }
    val freshness = when (val admitted = admitVfsPassiveRead(epoch)) {
        is VfsPassiveReadAdmission.Admitted -> admitted.capability
        is VfsPassiveReadAdmission.Rejected -> return rejected(HostedQueryFailure.Freshness(admitted.failure))
    }
    val execution = when (val admitted = cancellableReadExecution(freshness)) {
        is AdmittedProjectReadExecutionAdmission.Admitted -> admitted.execution
        is AdmittedProjectReadExecutionAdmission.Rejected -> return rejected(when (val failure = admitted.failure) {
            AdmittedProjectReadExecutionAdmissionFailure.WrongProject -> HostedQueryFailure.WRONG_PROJECT
            is AdmittedProjectReadExecutionAdmissionFailure.FreshnessRejected -> HostedQueryFailure.Freshness(failure.cause)
        })
    }
    progress.advance(HostedQueryStage.SEMANTIC_READ)
    val evidence = when (val read = execution.executeAsync { project ->
        when (val current = admitVfsPassiveRead(epoch)) {
            is VfsPassiveReadAdmission.Admitted -> read(project, model)
            is VfsPassiveReadAdmission.Rejected -> HostedSemanticRead.Rejected(HostedQueryFailure.Freshness(current.failure))
        }
    }) {
        is AdmittedProjectReadExecutionResult.Completed -> when (val value = read.value) {
            is HostedSemanticRead.Resolved -> value.evidence
            is HostedSemanticRead.Rejected -> return rejected(value.failure)
        }
        is AdmittedProjectReadExecutionResult.Rejected -> return rejected(read.failure.hostedFailure())
    }
    progress.advance(HostedQueryStage.CONTENT_REVALIDATION)
    checkpoint.afterSemanticRead()
    // Reacquire a short read after asynchronous analysis and recheck all saved dependencies and stamps.
    when (val verified = execution.executeAsync { project -> verify(project, evidence) }) {
        is AdmittedProjectReadExecutionResult.Completed -> when (val saved = verified.value) {
            SavedDocuments.Clean -> Unit
            is SavedDocuments.Rejected -> return rejected(saved.failure)
        }
        is AdmittedProjectReadExecutionResult.Rejected -> return rejected(verified.failure.hostedFailure())
    }
    return when (val admitted = admitVfsPassiveRead(epoch)) {
        is VfsPassiveReadAdmission.Admitted -> {
            progress.advance(HostedQueryStage.RESULT_DETACHED)
            HostedReadPreparation.Prepared(epoch, model, evidence)
        }
        is VfsPassiveReadAdmission.Rejected -> rejected(HostedQueryFailure.Freshness(admitted.failure))
    }
}

private fun rejected(failure: HostedQueryFailure) = HostedReadPreparation.Rejected(failure)

private fun AdmittedProjectReadExecutionFailure.hostedFailure(): HostedQueryFailure = when (this) {
    AdmittedProjectReadExecutionFailure.WRONG_THREAD,
    AdmittedProjectReadExecutionFailure.EXISTING_READ_ACCESS -> HostedQueryFailure.WRONG_THREAD
    AdmittedProjectReadExecutionFailure.PROJECT_DISPOSED,
    AdmittedProjectReadExecutionFailure.PROJECT_NOT_OPEN -> HostedQueryFailure.PROJECT_UNAVAILABLE
    AdmittedProjectReadExecutionFailure.DUMB_MODE -> HostedQueryFailure.INDEXING
}
