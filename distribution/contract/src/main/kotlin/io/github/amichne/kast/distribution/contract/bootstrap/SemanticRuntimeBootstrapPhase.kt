package io.github.amichne.kast.distribution.contract.bootstrap

import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionObservation
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionOutcome
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionFailure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Closed ordered startup stages; counts measure completed stages, never speculative work. */
@Serializable
enum class SemanticRuntimeBootstrapPhase(val wireName: String, val displayName: String) {
    @SerialName("discovering-runtime")
    DISCOVERING_RUNTIME("discovering-runtime", "discovering runtime"),
    @SerialName("selecting-gradle-jvm")
    GRADLE_JVM_SELECTION("selecting-gradle-jvm", "selecting Gradle JVM"),
    @SerialName("importing-gradle-model")
    PROJECT_IMPORT("importing-gradle-model", "importing Gradle model"),
    @SerialName("indexing")
    INDEXING("indexing", "indexing"),
    @SerialName("capturing-workspace-model")
    MODEL_CAPTURE("capturing-workspace-model", "capturing workspace model"),
    @SerialName("assembling-runtime")
    RUNTIME_ASSEMBLY("assembling-runtime", "assembling runtime"),
    @SerialName("activating-transport")
    TRANSPORT_ACTIVATION("activating-transport", "activating transport");

    val completedPhases: Int get() = ordinal
    val totalPhases: Int get() = entries.size
}

/** Finite actions contain only fixed product guidance, never persisted exception or environment text. */
enum class SemanticRuntimeBootstrapCorrectiveAction(val instruction: String) {
    VERIFY_RUNTIME("Verify the installed Kast payload and supported IntelliJ installation, then run kast start again."),
    SELECT_GRADLE_JVM("Select a Gradle-compatible project JVM, then run kast start again."),
    VERIFY_GRADLE_TOOLING_PAYLOAD("Update Kast to a build with Gradle tooling payloads compatible with the project JVM, then run kast start again."),
    VERIFY_GRADLE_IMPORT("Run the repository Gradle wrapper successfully with the admitted import inputs, then run kast start again."),
    SELECT_SUPPORTED_IDE("Select the supported IntelliJ installation with kast start --idea-home."),
    CORRECT_WORKSPACE_MODEL("Correct the repository Gradle model and source roots, then run kast stop followed by kast start."),
    RESTART_RUNTIME("Run kast stop to retire the owned runtime, then run kast start again.");
}

sealed interface SemanticRuntimeBootstrapRemediation {
    val instruction: String

    data class Bootstrap(val action: SemanticRuntimeBootstrapCorrectiveAction) : SemanticRuntimeBootstrapRemediation {
        override val instruction: String get() = action.instruction
    }

    data class GradleJvm(val failure: GradleJvmSelectionFailure) : SemanticRuntimeBootstrapRemediation {
        override val instruction: String get() = failure.correctiveAction
    }
}

/** A specific observed JVM failure keeps its more precise correction through every projection. */
fun SemanticRuntimeBootstrapState.Rejected.correctiveAction(): SemanticRuntimeBootstrapRemediation {
    when (val observation = gradleJvm) {
        GradleJvmSelectionObservation.Unobserved -> Unit
        is GradleJvmSelectionObservation.Observed -> when (val outcome = observation.report.outcome) {
            is GradleJvmSelectionOutcome.Rejected -> return SemanticRuntimeBootstrapRemediation.GradleJvm(outcome.failure)
            is GradleJvmSelectionOutcome.Selected -> Unit
        }
    }
    return SemanticRuntimeBootstrapRemediation.Bootstrap(defaultCorrectiveAction())
}

private fun SemanticRuntimeBootstrapState.Rejected.defaultCorrectiveAction(): SemanticRuntimeBootstrapCorrectiveAction =
    when (failure) {
        SemanticRuntimeBootstrapFailure.GRADLE_JVM_UNAVAILABLE,
        SemanticRuntimeBootstrapFailure.PROJECT_JVM_UNAVAILABLE,
        SemanticRuntimeBootstrapFailure.GRADLE_JVM_CONFIGURATION_INVALID ->
            SemanticRuntimeBootstrapCorrectiveAction.SELECT_GRADLE_JVM
        SemanticRuntimeBootstrapFailure.GRADLE_TOOLING_PAYLOAD_INCOMPATIBLE ->
            SemanticRuntimeBootstrapCorrectiveAction.VERIFY_GRADLE_TOOLING_PAYLOAD
        SemanticRuntimeBootstrapFailure.GRADLE_IMPORT_FAILED,
        SemanticRuntimeBootstrapFailure.GRADLE_IMPORT_TIMED_OUT,
        SemanticRuntimeBootstrapFailure.GRADLE_PROJECT_POLICY_INVALID ->
            SemanticRuntimeBootstrapCorrectiveAction.VERIFY_GRADLE_IMPORT
        SemanticRuntimeBootstrapFailure.PLATFORM_LINKAGE_INVALID ->
            SemanticRuntimeBootstrapCorrectiveAction.SELECT_SUPPORTED_IDE
        else -> when (phase) {
            SemanticRuntimeBootstrapPhase.DISCOVERING_RUNTIME -> SemanticRuntimeBootstrapCorrectiveAction.VERIFY_RUNTIME
            SemanticRuntimeBootstrapPhase.GRADLE_JVM_SELECTION -> SemanticRuntimeBootstrapCorrectiveAction.SELECT_GRADLE_JVM
            SemanticRuntimeBootstrapPhase.PROJECT_IMPORT -> SemanticRuntimeBootstrapCorrectiveAction.VERIFY_GRADLE_IMPORT
            SemanticRuntimeBootstrapPhase.INDEXING,
            SemanticRuntimeBootstrapPhase.MODEL_CAPTURE -> SemanticRuntimeBootstrapCorrectiveAction.CORRECT_WORKSPACE_MODEL
            SemanticRuntimeBootstrapPhase.RUNTIME_ASSEMBLY,
            SemanticRuntimeBootstrapPhase.TRANSPORT_ACTIVATION -> SemanticRuntimeBootstrapCorrectiveAction.RESTART_RUNTIME
        }
    }
