package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.CodexIntegrationRun
import io.github.amichne.kast.appserver.PersistentBrokerServiceFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastCodexDiagnosticTest {
    @Test
    fun `broker diagnostic preserves every finite cause and identifies the rejected stage`() {
        for (cause in PersistentBrokerServiceFailure.entries) {
            val document =
                Json.parseToJsonElement(codexBrokerRejectionDiagnostic(CodexIntegrationRun.BrokerRejected(cause)))
                    .jsonObject
            assertEquals(setOf("type", "failure"), document.keys)
            assertEquals("BROKER_START_REJECTED", document.getValue("type").jsonPrimitive.content)
            assertEquals(cause.name, document.getValue("failure").jsonPrimitive.content)
        }
    }
}
