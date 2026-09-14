package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AR-01: parser admission is independent of serializer round trips and wire fixture migration. */
class TraversalContinuationDocumentAdmissionTest {
    @Test
    fun `admits both upstream versions and retained output without rewriting tokens`() {
        val tokens =
            listOf(
                "traversal-continuation:v1:$PAYLOAD",
                "traversal-continuation:v2:$PAYLOAD",
                OUTPUT,
            )
        for (token in tokens) {
            val admitted = TraversalContinuationDocument.parse(token) as Refinement.Refined
            assertEquals(token, admitted.value.value)
        }
    }

    @Test
    fun `rejects malformed retained output without falling through to another family`() {
        for (token in listOf("traversal-output:v1:", "$OUTPUT:extra", OUTPUT.dropLast(1))) {
            assertEquals(
                Refinement.Rejected(TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE),
                TraversalContinuationDocument.parse(token),
            )
        }
    }

    @Test
    fun `preserves every finite upstream rejection cause`() {
        val cases =
            listOf(
                "other:v1:$PAYLOAD" to TraversalContinuationDocumentFailure.UNKNOWN_TOKEN_FAMILY,
                "traversal-continuation:v3:$PAYLOAD" to TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE,
                "traversal-continuation:v1" to TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE,
                "traversal-continuation:v1:$PAYLOAD:extra" to
                    TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE,
                "traversal-continuation:v1:e30=:digest" to
                    TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING,
                "traversal-continuation:v1::digest" to TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING,
                "traversal-continuation:v1:+:digest" to TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING,
                "traversal-continuation:v1:_w:digest" to TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING,
                "traversal-continuation:v1:e30:wrong" to TraversalContinuationDocumentFailure.PAYLOAD_DIGEST_MISMATCH,
            )
        for ((token, reason) in cases) {
            assertEquals(Refinement.Rejected(reason), TraversalContinuationDocument.parse(token))
        }
    }

    @Test
    fun `canonical upstream token lengths straddle the existing one MiB text bound`() {
        // Canonical unpadded base64 cannot have length 1 modulo 4, so no valid token has length 1,048,576.
        val largestAccepted = "traversal-continuation:v1:${nearLimitPayload()}:$NEAR_LIMIT_DIGEST"
        val smallestOversized = "traversal-continuation:v1:${nearLimitPayload()}YQ:$OVER_LIMIT_DIGEST"
        assertEquals(1_048_575, largestAccepted.length)
        assertEquals(1_048_577, smallestOversized.length)
        val admitted = TraversalContinuationDocument.parse(largestAccepted) as Refinement.Refined
        assertEquals(largestAccepted, admitted.value.value)
        assertEquals(
            Refinement.Rejected(TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE),
            TraversalContinuationDocument.parse(smallestOversized),
        )
    }

    @Test
    fun `oversized malformed tokens retain rejection precedence before the size check`() {
        val oversizedPayload = nearLimitPayload() + "YQ"
        val cases =
            listOf(
                "other:v3:$oversizedPayload:${"wrong".repeat(64)}" to
                    TraversalContinuationDocumentFailure.UNKNOWN_TOKEN_FAMILY,
                "traversal-continuation:v3:$oversizedPayload:$OVER_LIMIT_DIGEST" to
                    TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE,
                "traversal-continuation:v1:${oversizedPayload}=:${"0".repeat(64)}" to
                    TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING,
                "traversal-continuation:v1:$oversizedPayload:${"0".repeat(64)}" to
                    TraversalContinuationDocumentFailure.PAYLOAD_DIGEST_MISMATCH,
            )
        for ((token, reason) in cases) {
            assertTrue(token.length > 1_048_576)
            assertEquals(Refinement.Rejected(reason), TraversalContinuationDocument.parse(token))
        }
    }

    @Test
    fun `serializer preserves independent token text and rejects each finite invalid family case`() {
        val serializer = TraversalContinuationDocument.serializer()
        for (token in listOf("traversal-continuation:v1:$PAYLOAD", "traversal-continuation:v2:$PAYLOAD", OUTPUT)) {
            val document = (TraversalContinuationDocument.parse(token) as Refinement.Refined).value
            assertEquals(JsonPrimitive(token), Json.encodeToJsonElement(serializer, document))
            assertEquals(document, Json.decodeFromJsonElement(serializer, JsonPrimitive(token)))
        }
        val malformed =
            listOf(
                "other:v1:$PAYLOAD" to TraversalContinuationDocumentFailure.UNKNOWN_TOKEN_FAMILY,
                "$OUTPUT:extra" to TraversalContinuationDocumentFailure.INVALID_TOKEN_STRUCTURE,
                "traversal-continuation:v1:_w:digest" to TraversalContinuationDocumentFailure.INVALID_PAYLOAD_ENCODING,
                "traversal-continuation:v1:e30:wrong" to TraversalContinuationDocumentFailure.PAYLOAD_DIGEST_MISMATCH,
            )
        for ((token, reason) in malformed) {
            val failure =
                assertThrows(SerializationException::class.java) {
                    Json.decodeFromJsonElement(serializer, JsonPrimitive(token))
                }
            assertEquals(
                "io.github.amichne.kast.protocol.contract.TraversalContinuationDocument rejected $reason",
                failure.message,
            )
        }
    }
}

// Independent byte vector: base64url and SHA-256 of the two UTF-8 bytes 0x7b, 0x7d.
private const val PAYLOAD = "e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
private const val OUTPUT = "traversal-output:v1:00000000-0000-0000-0000-000000000001"

// Independent SHA-256 vectors for 786,363 and 786,364 ASCII 'a' bytes, respectively.
private const val NEAR_LIMIT_DIGEST = "9684251e5771012c8efc3dd1b5253767930c244c301456f83b483447c961e444"
private const val OVER_LIMIT_DIGEST = "d028bb4770bab5c773d039fbc353ef682899452cd9a9aba70789e1e505f90b88"

private fun nearLimitPayload(): String = "YWFh".repeat(262_121)
