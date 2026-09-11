package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRecoveryReason
import io.github.amichne.kast.protocol.contract.ChangeApplyUnverifiedReason
import kotlinx.serialization.json.JsonObject

/** Schema projection preserves every write-effect state and its finite failure vocabulary. */
internal fun changeApplicationDocumentSchema(operation: CanonicalOperation): JsonObject {
    return unionSchema(
        operationOutcomeVariant(operation, "complete", verifiedApplicationProperties()),
        operationOutcomeVariant(
            operation,
            "qualified",
            unverifiedApplicationProperties() +
                ServerSchemaProperty(
                    "qualification",
                    constantSchema("applied-unverified", "Observed unverified write."),
                ),
        ),
        operationOutcomeVariant(
            operation,
            "qualified",
            recoveryApplicationProperties() +
                ServerSchemaProperty("qualification", constantSchema("recovery-required", "Write requires recovery.")),
        ),
        operationOutcomeVariant(
            operation,
            "rejected",
            ServerSchemaProperty("reason", textSchema("Closed rejection reason.")),
        ),
    )
}

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
