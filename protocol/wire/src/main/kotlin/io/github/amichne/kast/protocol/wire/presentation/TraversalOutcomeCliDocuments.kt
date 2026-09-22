@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import io.github.amichne.kast.protocol.contract.ReadRecoveryGuidance
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import io.github.amichne.kast.protocol.contract.recoveryGuidance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraversalCompleteCliDocument(
    val operation: String,
    val status: String,
    val graph: NormalizedTraversalGraphCliDocument,
    val partialExpansions: List<TraversalPartialExpansionCliDocument>,
    val progress: TraversalProgressDocument,
    val strategy: TraversalStrategyDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
data class TraversalQualifiedCliDocument(
    val operation: String,
    val status: String,
    val graph: NormalizedTraversalGraphCliDocument,
    val partialExpansions: List<TraversalPartialExpansionCliDocument>,
    val progress: TraversalProgressDocument,
    val strategy: TraversalStrategyDocument,
    val qualification: TraversalQualificationCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
sealed interface TraversalQualificationCliDocument {
    @Serializable
    @SerialName("resumable")
    data class Resumable(
        val limitations: List<String>,
        val recovery: List<ReadRecoveryGuidance>,
        val relationLimitations: List<String>,
        val continuation: String,
        val checkpoint: io.github.amichne.kast.protocol.contract.TraversalCheckpointDocument,
        @SerialName("next_action") val nextAction: io.github.amichne.kast.protocol.contract.ReadResumeActionDocument,
    ) : TraversalQualificationCliDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(
        val limitations: List<String>,
        val recovery: List<ReadRecoveryGuidance>,
        val relationLimitations: List<String>,
    ) : TraversalQualificationCliDocument
}

fun TraversalRunQualification.toCliDocument(report: ExecutionBudgetReport?): TraversalQualificationCliDocument =
    when (this) {
        is TraversalRunQualification.Resumable ->
            TraversalQualificationCliDocument.Resumable(
                limitations = limitations.map { it.cliName() },
                recovery = recoveryGuidance(report),
                relationLimitations = relationLimitations.map { it.cliName() },
                continuation = continuation.value,
                checkpoint = checkpoint,
                nextAction = nextAction,
            )
        is TraversalRunQualification.TerminalIncomplete ->
            TraversalQualificationCliDocument.TerminalIncomplete(
                limitations = limitations.map { it.cliName() },
                recovery = recoveryGuidance(report),
                relationLimitations = relationLimitations.map { it.cliName() },
            )
    }

val traversalCompleteFactory = CanonicalJsonDocument.generated(TraversalCompleteCliDocument.serializer())
val traversalQualifiedFactory = CanonicalJsonDocument.generated(TraversalQualifiedCliDocument.serializer())
