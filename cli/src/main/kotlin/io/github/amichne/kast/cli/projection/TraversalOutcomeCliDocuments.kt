@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TraversalCompleteCliDocument(
    val operation: String,
    val status: String,
    val graph: NormalizedTraversalGraphCliDocument,
    val partialExpansions: List<TraversalPartialExpansionCliDocument>,
    val progress: TraversalProgressDocument,
    val strategy: TraversalStrategyDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
)

@Serializable
internal data class TraversalQualifiedCliDocument(
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
)

@Serializable
internal sealed interface TraversalQualificationCliDocument {
    @Serializable
    @SerialName("resumable")
    data class Resumable(
        val limitations: List<String>,
        val relationLimitations: List<String>,
        val continuation: String,
    ) : TraversalQualificationCliDocument

    @Serializable
    @SerialName("terminal_incomplete")
    data class TerminalIncomplete(
        val limitations: List<String>,
        val relationLimitations: List<String>,
    ) : TraversalQualificationCliDocument
}

internal fun TraversalRunQualification.toCliDocument(): TraversalQualificationCliDocument =
    when (this) {
        is TraversalRunQualification.Resumable ->
            TraversalQualificationCliDocument.Resumable(
                limitations = limitations.map { it.cliName() },
                relationLimitations = relationLimitations.map { it.cliName() },
                continuation = continuation.value,
            )
        is TraversalRunQualification.TerminalIncomplete ->
            TraversalQualificationCliDocument.TerminalIncomplete(
                limitations = limitations.map { it.cliName() },
                relationLimitations = relationLimitations.map { it.cliName() },
            )
    }

internal val traversalCompleteFactory = CliJsonDocument.generated(TraversalCompleteCliDocument.serializer())
internal val traversalQualifiedFactory = CliJsonDocument.generated(TraversalQualifiedCliDocument.serializer())
