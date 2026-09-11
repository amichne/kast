package io.github.amichne.kast.fixtureprobe

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProbeSaveCompletionTest {
    private val saved = ProbeDigest.observe("saved fixture".toByteArray())
    private val older = ProbeDigest.observe("older fixture".toByteArray())
    private val document =
        ProbeDocumentEvidence(
            document = saved,
            documentState = ProbeDocumentState.SAVED_COMMITTED,
            syntax = ProbeSyntaxState.CLEAN,
            undo = ProbeUndoState.UNAVAILABLE,
            declarations = emptyList(),
        )

    @Test
    fun physicalObservationFollowsCompletionAndRetainsDocumentEvidence() {
        val stages = mutableListOf<String>()
        var physical = older
        val result =
            completeProbeSave(
                expected = saved,
                document = document,
                complete = {
                    stages += "complete"
                    physical = saved
                    ProbeResult.Accepted(Unit)
                },
                observe = {
                    stages += "observe"
                    ProbeResult.Accepted(physical)
                },
            )
        val completed = assertInstanceOf(ProbeExecution.Completed::class.java, result)
        assertEquals(listOf("complete", "observe"), stages)
        assertEquals(saved, completed.evidence.saved)
        assertEquals(saved, completed.evidence.document)
        assertEquals(ProbeDocumentState.SAVED_COMMITTED, completed.evidence.documentState)
        assertEquals(ProbeSyntaxState.CLEAN, completed.evidence.syntax)
        assertEquals(ProbeUndoState.UNAVAILABLE, completed.evidence.undo)
    }

    @Test
    fun completionFailurePreventsPhysicalObservationAndEncodesUncertainty() {
        var observations = 0
        val result =
            completeProbeSave(
                expected = saved,
                document = document,
                complete = {
                    ProbeResult.Rejected(ProbeFailure.SAVE_REJECTED)
                },
                observe = {
                    observations++
                    ProbeResult.Accepted(saved)
                },
            )
        assertEquals(0, observations)
        assertEquals(ProbeExecution.EffectUncertain(ProbeFailure.SAVE_REJECTED), result)
        val encoded =
            Json.parseToJsonElement(
                    encodeProbeResponse(
                        UUID.fromString("35cf8fa9-3bbf-464c-9f63-7c479b22caa7"),
                        "UNDO_PRODUCTION_CHANGE",
                        result,
                    )
                )
                .jsonObject
        assertEquals("EFFECT_UNCERTAIN", encoded.getValue("outcome").jsonPrimitive.content)
        assertEquals("SAVE_REJECTED", encoded.getValue("failure").jsonPrimitive.content)
    }

    @Test
    fun completionDoesNotManufactureMatchingImagesOrACommittedDocument() {
        assertEquals(
            ProbeExecution.EffectUncertain(ProbeFailure.SAVE_REJECTED),
            completeProbeSave(
                expected = saved,
                document = document,
                complete = { ProbeResult.Accepted(Unit) },
                observe = { ProbeResult.Accepted(older) },
            ),
        )
        assertEquals(
            ProbeExecution.EffectUncertain(ProbeFailure.SAVE_REJECTED),
            completeProbeSave(
                expected = saved,
                document = document.copy(document = older),
                complete = { ProbeResult.Accepted(Unit) },
                observe = { ProbeResult.Accepted(saved) },
            ),
        )
        assertEquals(
            ProbeExecution.EffectUncertain(ProbeFailure.DOCUMENT_STATE_REJECTED),
            completeProbeSave(
                expected = saved,
                document = document.copy(documentState = ProbeDocumentState.DIRTY_COMMITTED),
                complete = { ProbeResult.Accepted(Unit) },
                observe = { ProbeResult.Accepted(saved) },
            ),
        )
        assertEquals(
            ProbeExecution.EffectUncertain(ProbeFailure.SOURCE_TOO_LARGE),
            completeProbeSave(
                expected = saved,
                document = document,
                complete = { ProbeResult.Accepted(Unit) },
                observe = { ProbeResult.Rejected(ProbeFailure.SOURCE_TOO_LARGE) },
            ),
        )
    }
}
