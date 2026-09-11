package io.github.amichne.kast.fixtureprobe

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbeControlContractTest {
    private val id = UUID.fromString("35cf8fa9-3bbf-464c-9f63-7c479b22caa7")
    private val digest = "a".repeat(64)
    private val postimage = "b".repeat(64)
    private val previousSequence = 2L
    private val nextSequence = 3L

    @Test
    fun reimportRequiresDistinctNamedBuildGuardAndRejectsItElsewhere() {
        assertEquals(ProbeFailure.BUILD_IMAGE_GUARD_REQUIRED, rejected(request(ProbeCommand.REIMPORT_GRADLE)).failure)
        val raw = request(ProbeCommand.REIMPORT_GRADLE, ",\"expectedBuildSha256\":\"$digest\"")
        val admitted =
            assertInstanceOf(ProbeResult.Accepted::class.java, ProbeRequest.decode(raw.toByteArray(), id)).value
                as ProbeRequest
        assertEquals(raw, admitted.encode())
        assertEquals(digest, assertInstanceOf(ProbeBuildGuard.Expected::class.java, admitted.build).digest.value)
        assertInstanceOf(
            ProbeResult.Rejected::class.java,
            ProbeRequest.decode(
                request(ProbeCommand.OBSERVE, ",\"expectedBuildSha256\":\"$digest\"").toByteArray(),
                id,
            ),
        )
        assertInstanceOf(
            ProbeResult.Rejected::class.java,
            ProbeRequest.decode(
                request(ProbeCommand.REIMPORT_GRADLE, ",\"expectedPostimageSha256\":\"$postimage\"").toByteArray(),
                id,
            ),
        )
    }

    @Test
    fun freshImportRequirementRejectsHistoricalSuccessAndUnfinishedNewImport() {
        val requirement = ProbeImportRequirement.After(previousSequence)
        assertFalse(
            requirement.ready(
                ProbeImportProgress(ProbeImportState.FINAL_TASKS_FINISHED, previousSequence),
                ProbeCommand.REIMPORT_GRADLE,
            )
        )
        assertFalse(
            requirement.ready(
                ProbeImportProgress(ProbeImportState.IMPORTING, nextSequence),
                ProbeCommand.REIMPORT_GRADLE,
            )
        )
        assertFalse(
            requirement.ready(ProbeImportProgress(ProbeImportState.FAILED, nextSequence), ProbeCommand.REIMPORT_GRADLE)
        )
        assertTrue(
            requirement.ready(
                ProbeImportProgress(ProbeImportState.FINAL_TASKS_FINISHED, nextSequence),
                ProbeCommand.REIMPORT_GRADLE,
            )
        )
    }

    @Test
    fun indexingDispatchAndWireRetainActualStageAndBound() {
        val hash = ProbeDigest.observe(byteArrayOf())
        val evidence =
            ProbeEvidence(
                saved = hash,
                document = hash,
                documentState = ProbeDocumentState.SAVED_COMMITTED,
                syntax = ProbeSyntaxState.CLEAN,
                undo = ProbeUndoState.UNAVAILABLE,
                declarations = emptyList(),
            )
        for ((command, execution) in
            listOf(
                ProbeCommand.HOLD_INDEXING to ProbeExecution.IndexingHeld(evidence),
                ProbeCommand.RELEASE_INDEXING to ProbeExecution.IndexingReleased(evidence),
            )) {
            val request =
                assertInstanceOf(
                        ProbeResult.Accepted::class.java,
                        ProbeRequest.decode(request(command).toByteArray(), id),
                    )
                    .value as ProbeRequest
            val ports =
                object : ProbeDispatchPorts {
                    override fun executeNative(request: ProbeRequest): ProbeExecution =
                        error("Indexing must not use generic native dispatch")

                    override fun awaitReadiness(request: ProbeRequest): ProbeExecution =
                        error("Indexing must not use readiness dispatch")

                    override fun reimport(request: ProbeRequest): ProbeExecution = error("Indexing must not reimport")

                    override fun controlIndexing(request: ProbeRequest): ProbeExecution = execution
                }
            val body =
                Json.parseToJsonElement(
                        encodeProbeResponse(id, command.name, ProbeRequestDispatch.dispatch(request, ports))
                    )
                    .jsonObject
            val indexing = body.getValue("indexing").jsonObject
            assertEquals(
                if (command == ProbeCommand.HOLD_INDEXING) "INDEXING_HELD" else "INDEXING_RELEASED",
                body.getValue("outcome").jsonPrimitive.content,
            )
            assertEquals(
                if (command == ProbeCommand.HOLD_INDEXING) "DUMB" else "SMART",
                indexing.getValue("state").jsonPrimitive.content,
            )
            if (command == ProbeCommand.HOLD_INDEXING)
                assertEquals("10000", indexing.getValue("maximumHoldMillis").jsonPrimitive.content)
            else assertEquals(setOf("state"), indexing.keys)
        }
    }

    private fun request(command: ProbeCommand, suffix: String = ""): String =
        "{\"version\":1,\"id\":\"$id\",\"command\":\"${command.name}\",\"expectedPreimageSha256\":\"$digest\"$suffix}"

    private fun rejected(raw: String): ProbeResult.Rejected =
        assertInstanceOf(ProbeResult.Rejected::class.java, ProbeRequest.decode(raw.toByteArray(), id))
}
