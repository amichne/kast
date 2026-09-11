package io.github.amichne.kast.fixtureprobe

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProbeResponseDispatchTest {
    private val id = UUID.fromString("35cf8fa9-3bbf-464c-9f63-7c479b22caa7")
    private val digest = ProbeDigest.observe("fixture".toByteArray())
    private val evidence =
        ProbeEvidence(
            saved = digest,
            document = digest,
            documentState = ProbeDocumentState.SAVED_COMMITTED,
            syntax = ProbeSyntaxState.CLEAN,
            undo = ProbeUndoState.UNAVAILABLE,
            declarations = emptyList(),
        )

    @Test
    fun readinessDispatchRoundtripRetainsRicherResponseForBothCommands() {
        for (command in listOf(ProbeCommand.AWAIT_SETUP_READY, ProbeCommand.AWAIT_REOPEN_READY)) {
            val ports = RecordingPorts(ready(), ProbeExecution.Completed(evidence))
            val request = request(command)
            val result = ProbeRequestDispatch.dispatch(request, ports)
            val encoded = Json.parseToJsonElement(encodeProbeResponse(id, command.name, result)).jsonObject
            assertEquals(1, ports.readinessCalls)
            assertEquals(0, ports.nativeCalls)
            assertEquals("SETUP_READY", encoded.getValue("outcome").jsonPrimitive.content)
            assertEquals(command.name, encoded.getValue("command").jsonPrimitive.content)
            assertEquals(id.toString(), encoded.getValue("id").jsonPrimitive.content)
            assertEquals(
                "FINAL_TASKS_OBSERVED",
                encoded.getValue("readiness").jsonObject.getValue("import").jsonPrimitive.content,
            )
            assertEquals(
                "OBSERVED_SETUP_ONLY",
                encoded.getValue("readiness").jsonObject.getValue("scope").jsonPrimitive.content,
            )
            assertEquals(
                digest.value,
                encoded.getValue("evidence").jsonObject.getValue("savedSha256").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun readinessFailureCannotFallBackToGenericCompletedObservation() {
        val ports =
            RecordingPorts(
                ProbeExecution.Rejected(ProbeFailure.SETUP_IMPORT_NOT_OBSERVED),
                ProbeExecution.Completed(evidence),
            )
        val result = ProbeRequestDispatch.dispatch(request(ProbeCommand.AWAIT_SETUP_READY), ports)
        val encoded =
            Json.parseToJsonElement(encodeProbeResponse(id, ProbeCommand.AWAIT_SETUP_READY.name, result)).jsonObject
        assertEquals(1, ports.readinessCalls)
        assertEquals(0, ports.nativeCalls)
        assertEquals("REJECTED", encoded.getValue("outcome").jsonPrimitive.content)
        assertEquals("SETUP_IMPORT_NOT_OBSERVED", encoded.getValue("failure").jsonPrimitive.content)
    }

    @Test
    fun ordinaryObservationRetainsGenericCompletedContract() {
        val ports = RecordingPorts(ready(), ProbeExecution.Completed(evidence))
        val result = ProbeRequestDispatch.dispatch(request(ProbeCommand.OBSERVE), ports)
        val encoded = Json.parseToJsonElement(encodeProbeResponse(id, ProbeCommand.OBSERVE.name, result)).jsonObject
        assertEquals(0, ports.readinessCalls)
        assertEquals(1, ports.nativeCalls)
        assertEquals("COMPLETED", encoded.getValue("outcome").jsonPrimitive.content)
        assertEquals(setOf("version", "id", "command", "outcome", "evidence"), encoded.keys)
    }

    private fun request(command: ProbeCommand): ProbeRequest {
        val raw =
            "{\"version\":1,\"id\":\"$id\",\"command\":\"${command.name}\",\"expectedPreimageSha256\":\"${digest.value}\"}"
        return assertInstanceOf(ProbeResult.Accepted::class.java, ProbeRequest.decode(raw.toByteArray(), id)).value
            as ProbeRequest
    }

    private fun ready(): ProbeExecution.SetupReady {
        val sample =
            ProbeSetupSample(
                status = ProbeSetupStatus.CANDIDATE,
                generation = ProbeSetupGeneration(imports = 1, roots = 1, workspace = 1, vfs = 1, psi = 1, dumb = 1),
                import = ProbeImportState.FINAL_TASKS_FINISHED,
                provenance = ProbeSourceProvenance.AUTHORED,
                indexing = ProbeSetupIndexingState.IDLE,
            )
        val proof =
            assertInstanceOf(
                    ProbeResult.Accepted::class.java,
                    ProbeSetupObservation.admit(sample, sample, SETUP_QUIET_WINDOW_NANOS),
                )
                .value as ProbeSetupObservation
        return ProbeExecution.SetupReady(evidence, proof)
    }
}

private class RecordingPorts(private val readiness: ProbeExecution, private val native: ProbeExecution) :
    ProbeDispatchPorts {
    var nativeCalls = 0
        private set

    var readinessCalls = 0
        private set

    override fun controlIndexing(request: ProbeRequest): ProbeExecution = native

    override fun reimport(request: ProbeRequest): ProbeExecution = readiness

    override fun executeNative(request: ProbeRequest): ProbeExecution {
        nativeCalls++
        return native
    }

    override fun awaitReadiness(request: ProbeRequest): ProbeExecution {
        readinessCalls++
        return readiness
    }
}
