package io.github.amichne.kast.fixtureprobe

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProbeProtocolTest {
    private val id = UUID.fromString("35cf8fa9-3bbf-464c-9f63-7c479b22caa7")
    private val preimage = "a".repeat(64)
    private val postimage = "b".repeat(64)

    private fun request(command: String, suffix: String = ""): String =
        "{\"version\":1,\"id\":\"$id\",\"command\":\"$command\",\"expectedPreimageSha256\":\"$preimage\"$suffix}"

    private fun decode(raw: String) = ProbeRequest.decode(raw.toByteArray(), id)

    @Test
    fun barrierRetainsFuturePostimageWhileGuardingCurrentPreimage() {
        assertIs<ProbeResult.Rejected>(decode(request("ARM_POST_SAVE_BARRIER")))
        val admitted =
            assertIs<ProbeResult.Accepted<ProbeRequest>>(
                    decode(request("ARM_POST_SAVE_BARRIER", ",\"expectedPostimageSha256\":\"$postimage\""))
                )
                .value
        assertEquals(preimage, admitted.expectedCurrentSaved.value)
        assertEquals(postimage, assertIs<ProbeExpectedImages.Changed>(admitted.images).postimage.value)
    }

    @Test
    fun saveBarrierRequiresExactSavedCommittedPostimage() {
        val before = assertIs<ProbeResult.Accepted<ProbeDigest>>(ProbeDigest.parse(preimage)).value
        val after = assertIs<ProbeResult.Accepted<ProbeDigest>>(ProbeDigest.parse(postimage)).value
        val images = ProbeExpectedImages.Changed(before, after)
        assertIs<ProbeResult.Accepted<ProbeSavedBarrierEvidence>>(
            ProbeSavedBarrierEvidence.admit(
                images = images,
                saved = after,
                document = after,
                state = ProbeDocumentState.SAVED_COMMITTED,
            )
        )
        for (state in ProbeDocumentState.entries.filter { it != ProbeDocumentState.SAVED_COMMITTED }) {
            assertIs<ProbeResult.Rejected>(
                ProbeSavedBarrierEvidence.admit(images = images, saved = after, document = after, state = state)
            )
        }
        assertIs<ProbeResult.Rejected>(
            ProbeSavedBarrierEvidence.admit(
                images = images,
                saved = before,
                document = after,
                state = ProbeDocumentState.SAVED_COMMITTED,
            )
        )
        assertIs<ProbeResult.Rejected>(
            ProbeSavedBarrierEvidence.admit(
                images = images,
                saved = after,
                document = before,
                state = ProbeDocumentState.SAVED_COMMITTED,
            )
        )
    }

    @Test
    fun canonicalObservationsRetainExactRequestAndImageProof() {
        val raw = request("OBSERVE")
        val admitted = assertIs<ProbeResult.Accepted<ProbeRequest>>(decode(raw)).value
        assertEquals(raw, admitted.encode())
        assertEquals(preimage, admitted.images.currentSaved.value)
        assertIs<ProbeExpectedImages.Unchanged>(admitted.images)
    }

    @Test
    fun unknownDuplicateCoercionAndPathKeysFailClosed() {
        for (raw in
            listOf(
                request("OBSERVE", ",\"path\":\"/tmp/other.kt\""),
                request("OBSERVE", ",\"version\":1"),
                request("OBSERVE").replace("\"version\":1", "\"version\":\"1\""),
                request("OBSERVE") + "\n",
                request("SAVE_ALL"),
            )) assertIs<ProbeResult.Rejected>(decode(raw))
    }

    @Test
    fun undoRequiresDistinctPreimageAndPostimage() {
        assertEquals(
            ProbeFailure.IMAGE_GUARD_REQUIRED,
            assertIs<ProbeResult.Rejected>(decode(request("UNDO_PRODUCTION_CHANGE"))).failure,
        )
        assertIs<ProbeResult.Rejected>(
            decode(request("UNDO_PRODUCTION_CHANGE", ",\"expectedPostimageSha256\":\"$preimage\""))
        )
        val accepted =
            assertIs<ProbeResult.Accepted<ProbeRequest>>(
                    decode(request("UNDO_PRODUCTION_CHANGE", ",\"expectedPostimageSha256\":\"$postimage\""))
                )
                .value
        assertEquals(postimage, accepted.images.currentSaved.value)
        assertEquals(preimage, accepted.images.preimage.value)
    }

    @Test
    fun filenameIdentityAndBoundedRequestRejectMismatches() {
        assertEquals(
            ProbeFailure.REQUEST_ID_MISMATCH,
            assertIs<ProbeResult.Rejected>(ProbeRequest.decode(request("OBSERVE").toByteArray(), UUID.randomUUID()))
                .failure,
        )
        assertEquals(
            ProbeFailure.REQUEST_TOO_LARGE,
            assertIs<ProbeResult.Rejected>(decode("x".repeat(MAXIMUM_REQUEST_BYTES + 1))).failure,
        )
    }

    @Test
    fun documentStatePreservesAllSavedAndCommittedCombinations() {
        assertEquals(ProbeDocumentState.SAVED_COMMITTED, ProbeDocumentState.observe(false, true))
        assertEquals(ProbeDocumentState.SAVED_UNCOMMITTED, ProbeDocumentState.observe(false, false))
        assertEquals(ProbeDocumentState.DIRTY_COMMITTED, ProbeDocumentState.observe(true, true))
        assertEquals(ProbeDocumentState.DIRTY_UNCOMMITTED, ProbeDocumentState.observe(true, false))
    }
}

private inline fun <reified Value : Any> assertIs(value: Any): Value = assertInstanceOf(Value::class.java, value)
