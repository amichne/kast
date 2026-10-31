package io.github.amichne.kast.cli.installation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagedControlCliTest {
    @Test
    fun `component states keep their discriminators and finite failure reasons`() {
        val transaction = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val cases =
            listOf(
                ManagedControlOutcome.Prepared(transaction, "/stage") to "PREPARED",
                ManagedControlOutcome.Committed(transaction) to "COMMITTED",
                ManagedControlOutcome.Aborted(transaction) to "ABORTED",
                ManagedControlOutcome.Rejected(InstallationFailure.REQUEST_REJECTED) to "REJECTED",
                ManagedControlOutcome.RecoveryRequired(InstallationFailure.TRANSACTION_OWNERSHIP_REJECTED) to
                    "RECOVERY_REQUIRED",
            )
        for ((outcome, expected) in cases) {
            val actual =
                installationManifestJson
                    .parseToJsonElement(installationManifestJson.encodeToString<ManagedControlOutcome>(outcome))
                    .jsonObject
            assertEquals(expected, actual.getValue("type").jsonPrimitive.content)
            when (outcome) {
                is ManagedControlOutcome.Prepared -> {
                    assertEquals(setOf("type", "transaction", "staged"), actual.keys)
                    assertEquals(transaction, actual.getValue("transaction").jsonPrimitive.content)
                    assertEquals("/stage", actual.getValue("staged").jsonPrimitive.content)
                }
                is ManagedControlOutcome.Committed,
                is ManagedControlOutcome.Aborted -> {
                    assertEquals(setOf("type", "transaction"), actual.keys)
                    assertEquals(transaction, actual.getValue("transaction").jsonPrimitive.content)
                }
                is ManagedControlOutcome.Rejected -> {
                    assertEquals(setOf("type", "reason"), actual.keys)
                    assertEquals("REQUEST_REJECTED", actual.getValue("reason").jsonPrimitive.content)
                }
                is ManagedControlOutcome.RecoveryRequired -> {
                    assertEquals(setOf("type", "reason"), actual.keys)
                    assertEquals("TRANSACTION_OWNERSHIP_REJECTED", actual.getValue("reason").jsonPrimitive.content)
                }
            }
        }
    }
}
