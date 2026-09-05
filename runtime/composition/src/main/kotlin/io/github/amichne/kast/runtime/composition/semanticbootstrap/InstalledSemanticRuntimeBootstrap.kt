package io.github.amichne.kast.runtime.composition.semanticbootstrap

import io.github.amichne.kast.distribution.contract.bootstrap.SEMANTIC_RUNTIME_BOOTSTRAP_FILE_NAME
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapCodec
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapFailure
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase
import io.github.amichne.kast.runtime.composition.InstalledRuntimeBootstrapPhase
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionObservation
import io.github.amichne.kast.runtime.composition.InstalledGradleJvmSelectionReport
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

enum class InstalledSemanticRuntimeBootstrapTerminalFailure {
    RUNTIME_ASSEMBLY,
    TRANSPORT_ACTIVATION,
    CACHE_STATE_PUBLICATION,
}

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

sealed interface InstalledSemanticRuntimeGradleJvmRefinement {
    data class Refined(val attempt: InstalledSemanticRuntimeBootstrapAttempt) : InstalledSemanticRuntimeGradleJvmRefinement
    data object ConflictingEvidence : InstalledSemanticRuntimeGradleJvmRefinement
}

/** One admitted identity produces only canonical state documents for one Indexer process. */
class InstalledSemanticRuntimeBootstrapAttempt private constructor(
    private val attemptId: SemanticRuntimeBootstrapAttemptId,
    private val gradleJvm: GradleJvmSelectionObservation = GradleJvmSelectionObservation.Unobserved,
) {
    /** Refines absent JVM evidence once; an existing proof can only be observed identically. */
    fun withGradleJvm(report: InstalledGradleJvmSelectionReport): InstalledSemanticRuntimeGradleJvmRefinement =
        when (gradleJvm) {
            GradleJvmSelectionObservation.Unobserved -> InstalledSemanticRuntimeGradleJvmRefinement.Refined(
                InstalledSemanticRuntimeBootstrapAttempt(attemptId, GradleJvmSelectionObservation.Observed(report.report)),
            )
            is GradleJvmSelectionObservation.Observed -> if (gradleJvm.report == report.report) {
                InstalledSemanticRuntimeGradleJvmRefinement.Refined(this)
            } else {
                InstalledSemanticRuntimeGradleJvmRefinement.ConflictingEvidence
            }
        }

    fun startingDocument(
        phase: InstalledRuntimeBootstrapPhase = InstalledRuntimeBootstrapPhase.DISCOVERING_RUNTIME,
    ): InstalledSemanticRuntimeBootstrapDocument = document(
        SemanticRuntimeBootstrapState.Starting(attemptId, phase.contractPhase(), gradleJvm),
    )

    fun readyDocument(): InstalledSemanticRuntimeBootstrapDocument = document(
        SemanticRuntimeBootstrapState.Ready(attemptId, gradleJvm),
    )

    /** Projects one unambiguous installed-workspace rejection into the shared wire contract. */
    fun rejectionDocument(
        failures: Set<InstalledKastRuntimeFailure>,
        phase: InstalledRuntimeBootstrapPhase = InstalledRuntimeBootstrapPhase.DISCOVERING_RUNTIME,
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
                document(SemanticRuntimeBootstrapState.Rejected(attemptId, projected.single(), phase.contractPhase(), gradleJvm)),
            )
            else -> InstalledSemanticRuntimeBootstrapRejection.Ambiguous
        }
    }

    fun terminalFailureDocument(
        phase: InstalledRuntimeBootstrapPhase,
        failure: InstalledSemanticRuntimeBootstrapTerminalFailure,
    ): InstalledSemanticRuntimeBootstrapDocument = document(
        SemanticRuntimeBootstrapState.Rejected(
            attemptId,
            when (failure) {
                InstalledSemanticRuntimeBootstrapTerminalFailure.TRANSPORT_ACTIVATION ->
                    SemanticRuntimeBootstrapFailure.TRANSPORT_ACTIVATION_FAILED
                InstalledSemanticRuntimeBootstrapTerminalFailure.RUNTIME_ASSEMBLY ->
                    SemanticRuntimeBootstrapFailure.RUNTIME_ASSEMBLY_FAILED
                InstalledSemanticRuntimeBootstrapTerminalFailure.CACHE_STATE_PUBLICATION ->
                    SemanticRuntimeBootstrapFailure.CACHE_STATE_PUBLICATION_FAILED
            },
            phase.contractPhase(),
            gradleJvm,
        ),
    )

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
    InstalledIntellijWorkspaceFailure.PROJECT_STORE_OVERLAPS_WORKSPACE ->
        SemanticRuntimeBootstrapFailure.PROJECT_STORE_OVERLAPS_WORKSPACE
    InstalledIntellijWorkspaceFailure.PROJECT_STORE_CREATION_FAILED ->
        SemanticRuntimeBootstrapFailure.PROJECT_STORE_CREATION_FAILED
    InstalledIntellijWorkspaceFailure.PROJECT_STORE_IDENTITY_REJECTED ->
        SemanticRuntimeBootstrapFailure.PROJECT_STORE_IDENTITY_REJECTED
    InstalledIntellijWorkspaceFailure.PROJECT_STORE_EXCLUSION_DISCOVERY_FAILED ->
        SemanticRuntimeBootstrapFailure.PROJECT_STORE_EXCLUSION_DISCOVERY_FAILED
    InstalledIntellijWorkspaceFailure.PROJECT_STORE_CONFIGURATION_WRITE_FAILED ->
        SemanticRuntimeBootstrapFailure.PROJECT_STORE_CONFIGURATION_WRITE_FAILED
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_MODULE_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_MODULE_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_EXCLUSION_POLICY_MISMATCH ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_EXCLUSION_POLICY_MISMATCH
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_CONTENT_ROOT_MISMATCH ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_CONTENT_ROOT_MISMATCH
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_EXCLUSION_ROOTS_MISMATCH ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_EXCLUSION_ROOTS_MISMATCH
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_PLATFORM_OBSERVATION_FAILED ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_PLATFORM_OBSERVATION_FAILED
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_RETIREMENT_IDENTITY_LOST ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_RETIREMENT_IDENTITY_LOST
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_RETIREMENT_FAILED ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_RETIREMENT_FAILED
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_IMPORTED_MODULES_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_IMPORTED_MODULES_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_EXCLUSION_ROOT_UNAVAILABLE ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_EXCLUSION_ROOT_UNAVAILABLE
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_EXCLUSION_NOT_PRESERVED ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_EXCLUSION_NOT_PRESERVED
    InstalledIntellijWorkspaceFailure.INDEX_BOOTSTRAP_SOURCE_ROOT_NOT_ADMITTED ->
        SemanticRuntimeBootstrapFailure.INDEX_BOOTSTRAP_SOURCE_ROOT_NOT_ADMITTED
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
    InstalledIntellijWorkspaceFailure.GRADLE_TOOLING_PAYLOAD_INCOMPATIBLE ->
        SemanticRuntimeBootstrapFailure.GRADLE_TOOLING_PAYLOAD_INCOMPATIBLE
    InstalledIntellijWorkspaceFailure.GRADLE_PROJECT_POLICY_INVALID ->
        SemanticRuntimeBootstrapFailure.GRADLE_PROJECT_POLICY_INVALID
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

private fun InstalledRuntimeBootstrapPhase.contractPhase(): SemanticRuntimeBootstrapPhase = when (this) {
    InstalledRuntimeBootstrapPhase.DISCOVERING_RUNTIME -> SemanticRuntimeBootstrapPhase.DISCOVERING_RUNTIME
    InstalledRuntimeBootstrapPhase.GRADLE_JVM_SELECTION -> SemanticRuntimeBootstrapPhase.GRADLE_JVM_SELECTION
    InstalledRuntimeBootstrapPhase.PROJECT_IMPORT -> SemanticRuntimeBootstrapPhase.PROJECT_IMPORT
    InstalledRuntimeBootstrapPhase.INDEXING -> SemanticRuntimeBootstrapPhase.INDEXING
    InstalledRuntimeBootstrapPhase.MODEL_CAPTURE -> SemanticRuntimeBootstrapPhase.MODEL_CAPTURE
    InstalledRuntimeBootstrapPhase.RUNTIME_ASSEMBLY -> SemanticRuntimeBootstrapPhase.RUNTIME_ASSEMBLY
    InstalledRuntimeBootstrapPhase.TRANSPORT_ACTIVATION -> SemanticRuntimeBootstrapPhase.TRANSPORT_ACTIVATION
}
