package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
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
}

// Independent byte vector: base64url and SHA-256 of the two UTF-8 bytes 0x7b, 0x7d.
private const val PAYLOAD = "e30:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
private const val OUTPUT = "traversal-output:v1:00000000-0000-0000-0000-000000000001"
