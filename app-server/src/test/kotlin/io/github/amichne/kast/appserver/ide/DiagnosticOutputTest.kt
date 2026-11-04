package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.appserver.runtime.JsonLineWorkspacePreparationObserver
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationActivity
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationActivityOutcome
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSwitch
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import io.github.amichne.kast.protocol.contract.WorkspacePreparationTiming
import io.github.amichne.kast.protocol.contract.WorkspacePreparationWaitMode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticOutputTest {
    @Test
    fun `shared expiry feedback distinguishes the expired native wait from the continuing indefinite owner`() {
        val expired =
            WorkspacePreparationTiming(
                IdeLifecycleStage.IMPORTING,
                100,
                100,
                0,
                WorkspacePreparationDeadlineOwner.NATIVE_PREPARATION,
            )
        val continuing =
            expired.copy(
                limitMillis = null,
                remainingMillis = null,
                waitMode = WorkspacePreparationWaitMode.INDEFINITE,
            )
        val bytes = ByteArrayOutputStream()
        JsonLineWorkspacePreparationObserver(PrintStream(bytes), ConfigurationSwitch.ENABLED)
            .observe(
                WorkspacePreparationActivity(
                    "owner",
                    WorkspacePreparationActivityOutcome.WaitingAfterSharedDeadline("old", "next", expired),
                    preparation = continuing,
                )
            )
        val document = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val outcome = document.getValue("outcome").jsonObject
        assertEquals("waiting_after_shared_deadline", outcome.getValue("type").jsonPrimitive.content)
        assertEquals("old", outcome.getValue("previousRequestId").jsonPrimitive.content)
        assertEquals("next", outcome.getValue("nextRequestId").jsonPrimitive.content)
        assertEquals("BOUNDED", outcome.getValue("expired").jsonObject.getValue("waitMode").jsonPrimitive.content)
        val preparation = document.getValue("preparation").jsonObject
        assertEquals("INDEFINITE", preparation.getValue("waitMode").jsonPrimitive.content)
        assertEquals(JsonNull, preparation.getValue("limitMillis"))
    }

    @Test
    fun `normal preparation and host exchanges emit no diagnostic bytes`() {
        assertEquals("", record(ConfigurationSwitch.DISABLED))
    }

    @Test
    fun `debug preserves bounded preparation and response evidence`() {
        val lines = record(ConfigurationSwitch.ENABLED).lineSequence().filter(String::isNotEmpty).toList()
        assertEquals(2, lines.size)
        assertEquals(
            "workspace-preparation",
            Json.parseToJsonElement(lines[0]).jsonObject.getValue("event").jsonPrimitive.content,
        )
        val response = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals("123", response.getValue("hostPid").jsonPrimitive.content)
        assertEquals(
            "HOST_RESPONSE_FRAME",
            response.getValue("evidence").jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    private fun record(debug: ConfigurationSwitch): String {
        val bytes = ByteArrayOutputStream()
        val output = PrintStream(bytes)
        JsonLineWorkspacePreparationObserver(output, debug)
            .observe(WorkspacePreparationActivity("request", WorkspacePreparationActivityOutcome.Completed))
        val owner = (HostedEndpointOwnerPid.parse("123") as Refinement.Refined).value
        JsonLineExistingIdeResponseObserver(
                ExistingIdeDescriptor(owner, UUID(0, 1)),
                ExistingIdeOperation.Status,
                output,
                debug,
            )
            .record(ExistingIdeResponseEvidence.Frame(10, 100, ExistingIdeResponseFrameOutcome.ADMITTED))
        return bytes.toString(Charsets.UTF_8)
    }
}
