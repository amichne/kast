package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import kotlinx.serialization.json.JsonObject

/** Schema projection preserves every write-effect state and its finite failure vocabulary. */
internal fun changeApplicationDocumentSchema(operation: CanonicalOperation): JsonObject {
    val variants =
        listOf(verifiedApplicationProperties(), unverifiedApplicationProperties(), recoveryApplicationProperties())
    val outcomes = variants.flatMap { payload ->
        listOf(
            applicationOutcome(operation, "complete", payload),
            applicationOutcome(
                operation,
                "qualified",
                payload + ServerSchemaProperty("qualification", textSchema("Closed qualification reason.")),
            ),
        )
    }
    return unionSchema(
        outcomes +
            applicationOutcome(
                operation,
                "rejected",
                listOf(ServerSchemaProperty("reason", textSchema("Closed rejection reason."))),
            )
    )
}

private fun applicationOutcome(
    operation: CanonicalOperation,
    status: String,
    payload: List<ServerSchemaProperty>,
): JsonObject =
    objectSchema(
        listOf(
            ServerSchemaProperty("operation", constantSchema(operation.id.value, "Canonical operation identity.")),
            ServerSchemaProperty("status", constantSchema(status, "Canonical operation outcome.")),
        ) + payload
    )

private fun verifiedApplicationProperties(): List<ServerSchemaProperty> =
    listOf(
        ServerSchemaProperty("state", constantSchema("verified", "Verified write effect.")),
        ServerSchemaProperty("receiptIdentity", textSchema("Verified change receipt identity.")),
        ServerSchemaProperty("changes", changeFilePreviewsSchema()),
    )

private fun unverifiedApplicationProperties(): List<ServerSchemaProperty> =
    listOf(
        ServerSchemaProperty("state", constantSchema("applied_unverified", "Write observed without verified receipt.")),
        ServerSchemaProperty("planIdentity", textSchema("Exact change plan identity.")),
        ServerSchemaProperty("changes", changeFilePreviewsSchema()),
        ServerSchemaProperty(
            "reason",
            enumSchema(
                ChangeApplyUnverifiedReason.entries.map { it.name.lowercase().replace('_', '-') },
                "Closed verification failure.",
            ),
        ),
    )

private fun recoveryApplicationProperties(): List<ServerSchemaProperty> =
    listOf(
        ServerSchemaProperty("state", constantSchema("recovery_required", "Write requires reconciliation.")),
        ServerSchemaProperty("planIdentity", textSchema("Exact change plan identity.")),
        ServerSchemaProperty("changes", changeFilePreviewsSchema()),
        ServerSchemaProperty(
            "reason",
            enumSchema(
                ChangeApplyRecoveryReason.entries.map { it.name.lowercase().replace('_', '-') },
                "Closed recovery reason.",
            ),
        ),
    )
