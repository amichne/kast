package io.github.amichne.kast.runtime.composition.semanticbootstrap

import io.github.amichne.kast.distribution.contract.bootstrap.SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapCodec
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.InstalledKastRuntimeFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeAssemblyFailure
import io.github.amichne.kast.runtime.composition.InstalledRuntimeWorkspaceFailure
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspaceFailure

/** Opaque canonical document permitted to leave only at the Indexer filesystem boundary. */
class InstalledSemanticRuntimeBootstrapDocument private constructor(
    private val value: String,
) {
    fun boundaryValue(): String = value

    internal companion object {
        fun encode(state: SemanticRuntimeBootstrapState):
            InstalledSemanticRuntimeBootstrapDocument =
            InstalledSemanticRuntimeBootstrapDocument(
                SemanticRuntimeBootstrapCodec.encode(state),
            )
    }
}

enum class InstalledSemanticRuntimeBootstrapAttemptFailure { INVALID_IDENTITY }

sealed interface InstalledSemanticRuntimeBootstrapAttemptAdmission {
    data class Admitted(
        val attempt: InstalledSemanticRuntimeBootstrapAttempt,
    ) : InstalledSemanticRuntimeBootstrapAttemptAdmission

    data class Rejected(
        val failure: InstalledSemanticRuntimeBootstrapAttemptFailure,
    ) : InstalledSemanticRuntimeBootstrapAttemptAdmission
}

sealed interface InstalledSemanticRuntimeBootstrapRejection {
    data class Projected(
        val document: InstalledSemanticRuntimeBootstrapDocument,
    ) : InstalledSemanticRuntimeBootstrapRejection

    data object Unavailable : InstalledSemanticRuntimeBootstrapRejection
    data object Ambiguous : InstalledSemanticRuntimeBootstrapRejection
}

/** One admitted identity produces only canonical state documents for one Indexer process. */
class InstalledSemanticRuntimeBootstrapAttempt private constructor(
    private val attemptId: SemanticRuntimeBootstrapAttemptId,
) {
    fun startingDocument(): InstalledSemanticRuntimeBootstrapDocument = document(
        SemanticRuntimeBootstrapState.Starting(attemptId),
    )

    fun readyDocument(): InstalledSemanticRuntimeBootstrapDocument = document(
        SemanticRuntimeBootstrapState.Ready(attemptId),
    )

    /** Projects one unambiguous installed-workspace rejection into the shared wire contract. */
    fun rejectionDocument(
        failures: Set<InstalledKastRuntimeFailure>,
    ): InstalledSemanticRuntimeBootstrapRejection {
        val projections = failures.map(InstalledKastRuntimeFailure::intellijBootstrapFailure)
        val projected = projections
            .filterIsInstance<IntellijBootstrapFailureProjection.Projected>()
            .map(IntellijBootstrapFailureProjection.Projected::failure)
            .toSet()
        return when {
            projected.isEmpty() -> InstalledSemanticRuntimeBootstrapRejection.Unavailable
            projections.all { it is IntellijBootstrapFailureProjection.Projected } &&
                projected.size == 1 -> InstalledSemanticRuntimeBootstrapRejection.Projected(
                document(SemanticRuntimeBootstrapState.Rejected(attemptId, projected.single())),
            )
            else -> InstalledSemanticRuntimeBootstrapRejection.Ambiguous
        }
    }

    private fun document(
        state: SemanticRuntimeBootstrapState,
    ): InstalledSemanticRuntimeBootstrapDocument =
        InstalledSemanticRuntimeBootstrapDocument.encode(state)

    companion object {
        const val FILE_NAME: String = SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME

        fun admit(raw: String): InstalledSemanticRuntimeBootstrapAttemptAdmission =
            when (val refinement = SemanticRuntimeBootstrapAttemptId.admit(raw)) {
                is Refinement.Refined -> InstalledSemanticRuntimeBootstrapAttemptAdmission.Admitted(
                    InstalledSemanticRuntimeBootstrapAttempt(refinement.value),
                )
                is Refinement.Rejected -> InstalledSemanticRuntimeBootstrapAttemptAdmission.Rejected(
                    InstalledSemanticRuntimeBootstrapAttemptFailure.INVALID_IDENTITY,
                )
            }
    }
}

private sealed interface IntellijBootstrapFailureProjection {
    data class Projected(
        val failure: SemanticRuntimeBootstrapFailure,
    ) : IntellijBootstrapFailureProjection

    data object NotIntellijBootstrap : IntellijBootstrapFailureProjection
}

private fun InstalledKastRuntimeFailure.intellijBootstrapFailure():
    IntellijBootstrapFailureProjection = when (this) {
    is InstalledKastRuntimeFailure.Assembly -> failure.intellijBootstrapFailure()
    is InstalledKastRuntimeFailure.StateDirectory,
    is InstalledKastRuntimeFailure.Telemetry,
    is InstalledKastRuntimeFailure.WorkspaceRoot,
        -> IntellijBootstrapFailureProjection.NotIntellijBootstrap
}

private fun InstalledRuntimeAssemblyFailure.intellijBootstrapFailure():
    IntellijBootstrapFailureProjection = when (this) {
    is InstalledRuntimeAssemblyFailure.WorkspacePublication -> failure.intellijBootstrapFailure()
    is InstalledRuntimeAssemblyFailure.Composition,
    is InstalledRuntimeAssemblyFailure.Persistence,
    is InstalledRuntimeAssemblyFailure.WorkspaceHandler,
        -> IntellijBootstrapFailureProjection.NotIntellijBootstrap
}

private fun InstalledRuntimeWorkspaceFailure.intellijBootstrapFailure():
    IntellijBootstrapFailureProjection = when (this) {
    is InstalledRuntimeWorkspaceFailure.IntellijBootstrap ->
        IntellijBootstrapFailureProjection.Projected(failure.bootstrapFailure())
    InstalledRuntimeWorkspaceFailure.Blocked,
    InstalledRuntimeWorkspaceFailure.Invalidated,
    is InstalledRuntimeWorkspaceFailure.ModelRefinementUnavailable,
    InstalledRuntimeWorkspaceFailure.NoPublication,
    InstalledRuntimeWorkspaceFailure.RootMismatch,
        -> IntellijBootstrapFailureProjection.NotIntellijBootstrap
}

private fun InstalledIntellijWorkspaceFailure.bootstrapFailure():
    SemanticRuntimeBootstrapFailure = when (this) {
    InstalledIntellijWorkspaceFailure.PROJECT_OPEN_FAILED ->
        SemanticRuntimeBootstrapFailure.PROJECT_OPEN_FAILED
    InstalledIntellijWorkspaceFailure.STARTUP_FAILED ->
        SemanticRuntimeBootstrapFailure.STARTUP_FAILED
    InstalledIntellijWorkspaceFailure.GRADLE_JVM_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.GRADLE_JVM_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.PROJECT_JVM_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.PROJECT_JVM_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.PLATFORM_LINKAGE_INVALID ->
        SemanticRuntimeBootstrapFailure.PLATFORM_LINKAGE_INVALID
    InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_FAILED ->
        SemanticRuntimeBootstrapFailure.GRADLE_IMPORT_FAILED
    InstalledIntellijWorkspaceFailure.GRADLE_JVM_CONFIGURATION_INVALID ->
        SemanticRuntimeBootstrapFailure.GRADLE_JVM_CONFIGURATION_INVALID
    InstalledIntellijWorkspaceFailure.GRADLE_IMPORT_TIMED_OUT ->
        SemanticRuntimeBootstrapFailure.GRADLE_IMPORT_TIMED_OUT
    InstalledIntellijWorkspaceFailure.INDEXING_INTERRUPTED ->
        SemanticRuntimeBootstrapFailure.INDEXING_INTERRUPTED
    InstalledIntellijWorkspaceFailure.MODEL_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.MODEL_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.MODEL_ROOT_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.MODEL_ROOT_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.MODEL_EXTERNAL_PROJECT_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.MODEL_EXTERNAL_PROJECT_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.MODEL_EXTERNAL_PROJECT_INCOMPLETE ->
        SemanticRuntimeBootstrapFailure.MODEL_EXTERNAL_PROJECT_INCOMPLETE
    InstalledIntellijWorkspaceFailure.MODEL_SOURCE_ROOTS_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.MODEL_SOURCE_ROOTS_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.MODEL_SOURCE_STATE_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.MODEL_SOURCE_STATE_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.MODEL_SEMANTIC_INPUT_INCOMPLETE ->
        SemanticRuntimeBootstrapFailure.MODEL_SEMANTIC_INPUT_INCOMPLETE
    InstalledIntellijWorkspaceFailure.MODEL_SEMANTIC_PROJECT_PATH_INVALID ->
        SemanticRuntimeBootstrapFailure.MODEL_SEMANTIC_PROJECT_PATH_INVALID
    InstalledIntellijWorkspaceFailure.MODEL_SEMANTIC_SOURCE_ROOT_INVALID ->
        SemanticRuntimeBootstrapFailure.MODEL_SEMANTIC_SOURCE_ROOT_INVALID
    InstalledIntellijWorkspaceFailure.MODEL_SEMANTIC_MODULE_INVALID ->
        SemanticRuntimeBootstrapFailure.MODEL_SEMANTIC_MODULE_INVALID
    InstalledIntellijWorkspaceFailure.MODEL_STATE_IDENTITY_REJECTED ->
        SemanticRuntimeBootstrapFailure.MODEL_STATE_IDENTITY_REJECTED
}
