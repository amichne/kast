package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.cli.ide.BrokerTrustFailure
import io.github.amichne.kast.cli.ide.BrokerTrustResult
import io.github.amichne.kast.cli.ide.BrokerTrustStatus
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class InstallationTrustObservationTest {
    @Test
    fun `installer response retains each finite trust failure`() {
        BrokerTrustFailure.entries.forEach { failure ->
            val exit = installationTrustRejection(failure)
            val document = Json.parseToJsonElement(exit.document.value).jsonObject
            assertEquals(setOf("operation", "status", "reason", "trust"), document.keys)
            assertEquals("installation.install", document.getValue("operation").jsonPrimitive.content)
            assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
            assertEquals("broker-trust-rejected", document.getValue("reason").jsonPrimitive.content)
            assertEquals(failure.name, document.getValue("trust").jsonPrimitive.content)
        }
    }

    @Test
    fun `enrollment preserves every result and emits only finite status evidence`() {
        val results =
            BrokerTrustStatus.entries.map { BrokerTrustResult.Complete(it) } +
                BrokerTrustFailure.entries.map { BrokerTrustResult.Rejected(it) }
        results.forEach { expected ->
            var calls = 0
            val observations = mutableListOf<InstallationTrustObservation>()
            assertSame(
                expected,
                enrollInstallationTrust(
                    Path.of("/private-home"),
                    {
                        calls++
                        expected
                    },
                    observations::add,
                ),
            )
            assertEquals(1, calls)
            val document = Json.parseToJsonElement(observations.single().toJson()).jsonObject
            assertEquals(setOf("event", "outcome"), document.keys)
            assertEquals("kast_installation_trust", document.getValue("event").jsonPrimitive.content)
            val outcome = document.getValue("outcome").jsonObject
            when (expected) {
                is BrokerTrustResult.Complete -> {
                    assertEquals(setOf("type", "status"), outcome.keys)
                    assertEquals("complete", outcome.getValue("type").jsonPrimitive.content)
                    assertEquals(expected.status.name, outcome.getValue("status").jsonPrimitive.content)
                }
                is BrokerTrustResult.Rejected -> {
                    assertEquals(setOf("type", "failure"), outcome.keys)
                    assertEquals("rejected", outcome.getValue("type").jsonPrimitive.content)
                    assertEquals(expected.failure.name, outcome.getValue("failure").jsonPrimitive.content)
                }
            }
        }
    }
}
