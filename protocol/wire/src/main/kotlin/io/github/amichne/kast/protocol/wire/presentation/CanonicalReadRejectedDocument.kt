@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.ReadRecoveryAction
import io.github.amichne.kast.protocol.contract.SourceReadFailure
import io.github.amichne.kast.protocol.contract.TraversalRunFailure
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.reason
import io.github.amichne.kast.protocol.contract.recoveryAction
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Fixed read output is constructed only from the corresponding canonical failure. */
@Serializable
private data class ReadRejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: String,
    @SerialName("next_action") val nextAction: ReadRecoveryAction,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent,
)

private val readRejectedFactory = CanonicalJsonDocument.generated(ReadRejectedCliDocument.serializer())

@Serializable
private data class SourceRejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: io.github.amichne.kast.protocol.contract.SourceReadCause,
    @SerialName("next_action") val nextAction: ReadRecoveryAction,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent,
)

private val sourceRejectedFactory = CanonicalJsonDocument.generated(SourceRejectedCliDocument.serializer())

fun canonicalReadRejectedDocument(failure: SourceReadFailure): CanonicalJsonDocument =
    sourceRejectedFactory.create(
        SourceRejectedCliDocument(
            CanonicalOperation.SOURCE_READ.id.value,
            "rejected",
            failure.reason(),
            failure.recoveryAction(),
            failure.budgetPresence(),
        )
    )

fun canonicalReadRejectedDocument(failure: TraversalRunFailure): CanonicalJsonDocument =
    readRejectedFactory.create(
        ReadRejectedCliDocument(
            CanonicalOperation.TRAVERSAL_RUN.id.value,
            "rejected",
            failure.reason().cliName(),
            failure.recoveryAction(),
            failure.budgetPresence(),
        )
    )
