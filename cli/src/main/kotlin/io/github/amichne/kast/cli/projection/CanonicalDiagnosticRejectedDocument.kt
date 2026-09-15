@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.DiagnosticCheckFailure
import io.github.amichne.kast.protocol.contract.DiagnosticRecoveryAction
import io.github.amichne.kast.protocol.contract.ExecutionBudgetPresence
import io.github.amichne.kast.protocol.contract.budgetPresence
import io.github.amichne.kast.protocol.contract.diagnosticRecoveryAction
import io.github.amichne.kast.protocol.contract.reason
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class DiagnosticRejectedCliDocument(
    val operation: String,
    val status: String,
    val reason: String,
    @SerialName("next_action") val nextAction: DiagnosticRecoveryAction,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: ExecutionBudgetPresence = ExecutionBudgetPresence.Absent,
)

private val diagnosticRejectedFactory = CliJsonDocument.generated(DiagnosticRejectedCliDocument.serializer())

internal fun canonicalDiagnosticRejectedDocument(failure: DiagnosticCheckFailure): CliJsonDocument =
    diagnosticRejectedFactory.create(
        DiagnosticRejectedCliDocument(
            CanonicalOperation.DIAGNOSTIC_CHECK.id.value,
            "rejected",
            failure.reason().cliName(),
            failure.diagnosticRecoveryAction(),
            failure.budgetPresence(),
        )
    )
