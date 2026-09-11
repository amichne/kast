package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyQualification
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyResult
import io.github.amichne.kast.protocol.contract.ChangeFilePreview
import io.github.amichne.kast.protocol.contract.ChangePlanQualification
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanResult
import io.github.amichne.kast.protocol.contract.ChangeRecoverQualification
import io.github.amichne.kast.protocol.contract.ChangeRecoverRejection
import io.github.amichne.kast.protocol.contract.ChangeRecoverResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

internal object CanonicalChangeCliDocuments {
    fun projectPlan(
        outcome:
            OperationOutcome<
                ChangePlanResult,
                ChangePlanQualification,
                ChangePlanRejection,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result ->
                planCompleteFactory.create(
                    ChangePlanCompleteCliDocument(
                        CanonicalOperation.CHANGE_PLAN.id.value,
                        "complete",
                        result.planIdentity.value,
                        result.changes.entries.map(ChangeFilePreview::cliDocument),
                    )
                )
            },
            qualified = { result, qualification ->
                planQualifiedFactory.create(
                    ChangePlanQualifiedCliDocument(
                        CanonicalOperation.CHANGE_PLAN.id.value,
                        "qualified",
                        result.planIdentity.value,
                        result.changes.entries.map(ChangeFilePreview::cliDocument),
                        qualification.cliName(),
                    )
                )
            },
            rejected = { rejection ->
                canonicalRejectedDocument(CanonicalOperation.CHANGE_PLAN, rejection.cliName())
            },
        )

    fun projectApplication(
        outcome:
            OperationOutcome<
                ChangeApplyResult,
                ChangeApplyQualification,
                ChangeApplyRejection,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result -> applicationDocument(result, buildJsonObject { put("status", "complete") }) },
            qualified = { result, qualification ->
                applicationDocument(
                    result,
                    buildJsonObject {
                        put("status", "qualified")
                        put("qualification", qualification.cliName())
                    },
                )
            },
            rejected = { rejection ->
                canonicalRejectedDocument(CanonicalOperation.CHANGE_APPLY, rejection.cliName())
            },
        )

    fun projectRecovery(
        outcome:
            OperationOutcome<
                ChangeRecoverResult,
                ChangeRecoverQualification,
                ChangeRecoverRejection,
            >
    ) =
        projectClosedOutcome(
            outcome,
            complete = { result ->
                recoveryCompleteFactory.create(
                    ChangeRecoveryCompleteCliDocument(
                        CanonicalOperation.CHANGE_RECOVER.id.value,
                        "complete",
                        result.state.cliName(),
                    )
                )
            },
            qualified = { result, qualification ->
                recoveryQualifiedFactory.create(
                    ChangeRecoveryQualifiedCliDocument(
                        CanonicalOperation.CHANGE_RECOVER.id.value,
                        "qualified",
                        result.state.cliName(),
                        qualification.cliName(),
                    )
                )
            },
            rejected = { rejection ->
                canonicalRejectedDocument(CanonicalOperation.CHANGE_RECOVER, rejection.cliName())
            },
        )
}

@Serializable
private data class ChangePlanCompleteCliDocument(
    val operation: String,
    val status: String,
    val planIdentity: String,
    val changes: List<ChangeFilePreviewCliDocument>,
)

@Serializable
private data class ChangePlanQualifiedCliDocument(
    val operation: String,
    val status: String,
    val planIdentity: String,
    val changes: List<ChangeFilePreviewCliDocument>,
    val qualification: String,
)

/** Projection preserves the finite write effect and never invents a receipt for an unverified write. */
private fun applicationDocument(result: ChangeApplyResult, outcome: JsonObject): CliJsonDocument =
    CliJsonDocument.generated(JsonObject.serializer())
        .create(
            buildJsonObject {
                put("operation", CanonicalOperation.CHANGE_APPLY.id.value)
                outcome.forEach { (name, value) -> put(name, value) }
                when (result) {
                    is ChangeApplyResult.Verified -> {
                        put("state", "verified")
                        put("receiptIdentity", result.receiptIdentity.value)
                    }
                    is ChangeApplyResult.AppliedUnverified -> {
                        put("state", "applied_unverified")
                        put("planIdentity", result.planIdentity.value)
                        put("reason", result.reason.cliName())
                    }
                    is ChangeApplyResult.RecoveryRequired -> {
                        put("state", "recovery_required")
                        put("planIdentity", result.planIdentity.value)
                        put("reason", result.reason.cliName())
                    }
                }
                put("changes", Json.encodeToJsonElement(result.changes.entries.map(ChangeFilePreview::cliDocument)))
            }
        )

@Serializable
private data class ChangeFilePreviewCliDocument(
    val path: String,
    val kind: String,
    val diff: String,
)

private fun ChangeFilePreview.cliDocument(): ChangeFilePreviewCliDocument =
    ChangeFilePreviewCliDocument(path.value, kind.name.lowercase(), diff.value)

@Serializable
private data class ChangeRecoveryCompleteCliDocument(
    val operation: String,
    val status: String,
    val state: String,
)

@Serializable
private data class ChangeRecoveryQualifiedCliDocument(
    val operation: String,
    val status: String,
    val state: String,
    val qualification: String,
)

private val planCompleteFactory = CliJsonDocument.generated(ChangePlanCompleteCliDocument.serializer())
private val planQualifiedFactory = CliJsonDocument.generated(ChangePlanQualifiedCliDocument.serializer())
private val recoveryCompleteFactory = CliJsonDocument.generated(ChangeRecoveryCompleteCliDocument.serializer())
private val recoveryQualifiedFactory = CliJsonDocument.generated(ChangeRecoveryQualifiedCliDocument.serializer())
