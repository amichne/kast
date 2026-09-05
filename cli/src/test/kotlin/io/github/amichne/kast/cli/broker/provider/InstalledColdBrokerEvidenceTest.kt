package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.BrokerDispatch
import io.github.amichne.kast.cli.broker.core.BrokerFailure
import io.github.amichne.kast.cli.broker.core.CanonicalBrokerDirectory
import io.github.amichne.kast.cli.broker.core.ProviderFailureCode
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ToolPresentation
import io.github.amichne.kast.kernel.Refinement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class InstalledColdBrokerEvidenceTest {
    @Test
    fun `process and tool rejection persist finite evidence before cleanup without private payload`(@TempDir root: Path) = runBlocking {
        val report = root.resolve("report.json")
        val evidence = InstalledColdBrokerEvidence(report)
        evidence.advance(ColdBrokerStage.DISCOVERY)
        val payload = """{"status":"rejected","boundary":"runtime","reason":"idea-installation-missing","diagnostic":"secret source /private/user","bootstrap":{"phase":"indexing","cause":"unknown secret","correctiveAction":"secret action"}}"""
        val executor = evidence.executor(BrokerProcessExecutor { BrokerProcessExecution.Completed(4, "", payload) })
        val request = BrokerProcessRequest.admit(
            BrokerExecutable.admit(Path.of("/bin/sh")).required(), listOf("start"),
            checkNotNull(CanonicalBrokerDirectory.admit(root.toRealPath())), 1_024, 1_000,
        ).required()
        executor.execute(request)
        evidence.dispatch(BrokerDispatch.Completed(ToolPresentation.text("""{"status":"rejected","diagnostic":$payload}""", false)))
        evidence.advance(ColdBrokerStage.STOP)
        evidence.reject(ColdBrokerFailure.ASSERTION_REJECTED)

        val raw = Files.readString(report)
        val document = Json.parseToJsonElement(raw).jsonObject
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals("DISCOVERY", document.getValue("failedStage").jsonPrimitive.content)
        assertEquals("TOOL_REJECTED", document.getValue("failure").jsonPrimitive.content)
        assertTrue(raw.contains("idea-installation-missing"))
        assertTrue(raw.contains("INDEXING"))
        assertFalse(raw.contains("secret")); assertFalse(raw.contains("/private/user"))
        assertEquals(3, document.getValue("observations").jsonArray.size)
    }

    @Test
    fun `finite provider rejection survives without arbitrary tool text`(@TempDir root: Path) {
        val report = root.resolve("report.json")
        val evidence = InstalledColdBrokerEvidence(report)
        evidence.advance(ColdBrokerStage.QUALIFICATION)
        evidence.dispatch(BrokerDispatch.Rejected(BrokerFailure.ProviderStartupRejected(
            ProviderNamespace.admit("kast").required(), ProviderFailureCode.KAST_CONTRACT_CHANGED,
        )))
        val raw = Files.readString(report)
        assertTrue(raw.contains("KAST_CONTRACT_CHANGED")); assertTrue(raw.contains("provider-startup-rejected"))
        assertThrows(IllegalStateException::class.java) { evidence.complete(buildJsonObject { put("status", "passed") }) }
    }

    @Test
    fun `successful receipt retains bounded observations and original evidence`(@TempDir root: Path) {
        val report = root.resolve("report.json")
        val evidence = InstalledColdBrokerEvidence(report)
        evidence.advance(ColdBrokerStage.SOURCE)
        evidence.dispatch(BrokerDispatch.Completed(ToolPresentation.text("private source payload", true)))
        evidence.complete(buildJsonObject { put("schemaVersion", 2); put("status", "passed"); put("cliEquivalent", true) })
        val raw = Files.readString(report)
        val document = Json.parseToJsonElement(raw).jsonObject
        assertEquals("passed", document.getValue("status").jsonPrimitive.content)
        assertEquals(1, document.getValue("observations").jsonArray.size)
        assertFalse(raw.contains("private source payload"))
    }

    @Test
    fun `observation saturation rejects proof while permitting process cleanup`(@TempDir root: Path) = runBlocking {
        val report = root.resolve("report.json")
        val evidence = InstalledColdBrokerEvidence(report)
        repeat(96) { evidence.dispatch(BrokerDispatch.Completed(ToolPresentation.text("bounded", true))) }
        evidence.advance(ColdBrokerStage.STOP)
        var cleanupRan = false
        val executor = evidence.executor(BrokerProcessExecutor {
            cleanupRan = true
            BrokerProcessExecution.Completed(0, "", "")
        })
        executor.execute(BrokerProcessRequest.admit(
            BrokerExecutable.admit(Path.of("/bin/sh")).required(), listOf("stop"),
            checkNotNull(CanonicalBrokerDirectory.admit(root.toRealPath())), 1_024, 1_000,
        ).required())
        assertTrue(cleanupRan)
        val document = Json.parseToJsonElement(Files.readString(report)).jsonObject
        assertEquals("OBSERVATION_LIMIT", document.getValue("failure").jsonPrimitive.content)
        assertEquals(96, document.getValue("observations").jsonArray.size)
    }

    @Test
    fun `failed receipt writes cannot prevent public stop or replace prior rejection`(@TempDir root: Path) = runBlocking {
        val directory = Files.createDirectory(root.resolve("evidence"))
        val report = directory.resolve("report.json")
        val evidence = InstalledColdBrokerEvidence(report)
        evidence.advance(ColdBrokerStage.DISCOVERY)
        evidence.reject(ColdBrokerFailure.TOOL_REJECTED)
        val preserved = root.resolve("preserved")
        Files.move(directory, preserved)
        Files.writeString(directory, "block subsequent receipt directory creation")
        var cleanupRan = false
        val executor = evidence.executor(BrokerProcessExecutor {
            cleanupRan = true
            BrokerProcessExecution.Completed(0, "", "")
        })
        val request = BrokerProcessRequest.admit(
            BrokerExecutable.admit(Path.of("/bin/sh")).required(), listOf("stop"),
            checkNotNull(CanonicalBrokerDirectory.admit(root.toRealPath())), 1_024, 1_000,
        ).required()
        var receiptWriteRejected = false
        try { executor.execute(request) } catch (failure: IOException) {
            receiptWriteRejected = true
            assertEquals("Cold broker evidence unavailable", failure.message)
        }
        assertTrue(receiptWriteRejected)
        assertTrue(cleanupRan, "receipt failure must not prevent the public stop process")
        val document = Json.parseToJsonElement(Files.readString(preserved.resolve("report.json"))).jsonObject
        assertEquals("rejected", document.getValue("status").jsonPrimitive.content)
        assertEquals("TOOL_REJECTED", document.getValue("failure").jsonPrimitive.content)
        assertFalse(Files.exists(report))
    }

    private fun <T, E> Refinement<T, E>.required(): T = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejected")
    }
}
