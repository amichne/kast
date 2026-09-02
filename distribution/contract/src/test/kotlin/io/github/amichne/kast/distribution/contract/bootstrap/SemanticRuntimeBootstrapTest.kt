package io.github.amichne.kast.distribution.contract.bootstrap

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SemanticRuntimeBootstrapTest {
    private val attempt = SemanticRuntimeBootstrapAttemptId.admit(
        "123e4567-e89b-42d3-a456-426614174000",
    ).refined()

    @Test
    fun `every bootstrap state round trips through the versioned contract`() {
        val states = SemanticRuntimeBootstrapFailure.entries.map(
            { failure -> SemanticRuntimeBootstrapState.Rejected(attempt, failure) },
        ) + listOf(
            SemanticRuntimeBootstrapState.Starting(attempt),
            SemanticRuntimeBootstrapState.Ready(attempt),
        )

        states.forEach { state ->
            assertEquals(
                Refinement.Refined(state),
                SemanticRuntimeBootstrapCodec.decode(
                    SemanticRuntimeBootstrapCodec.encode(state),
                ),
            )
        }
    }

    @Test
    fun `version one starting document has a stable wire representation`() {
        assertEquals(
            """{"schemaVersion":1,"bootstrap":{"state":"starting","attemptId":"123e4567-e89b-42d3-a456-426614174000"}}""",
            SemanticRuntimeBootstrapCodec.encode(SemanticRuntimeBootstrapState.Starting(attempt)),
        )
    }

    @Test
    fun `unknown state fails closed`() {
        assertEquals(
            Refinement.Rejected(
                SemanticRuntimeBootstrapDocumentFailure.MALFORMED_DOCUMENT,
            ),
            SemanticRuntimeBootstrapCodec.decode(
                """{"schemaVersion":1,"bootstrap":{"state":"unknown","attemptId":"123e4567-e89b-42d3-a456-426614174000"}}""",
            ),
        )
    }

    @Test
    fun `future schema with a future body is unsupported before body decoding`() {
        assertEquals(
            Refinement.Rejected(
                SemanticRuntimeBootstrapDocumentFailure.UNSUPPORTED_SCHEMA,
            ),
            SemanticRuntimeBootstrapCodec.decode(
                """{"schemaVersion":2,"bootstrap":{"state":"future","newField":true}}""",
            ),
        )
    }

    @Test
    fun `wire decoding cannot manufacture an invalid attempt identity`() {
        assertEquals(
            Refinement.Rejected(
                SemanticRuntimeBootstrapDocumentFailure.MALFORMED_DOCUMENT,
            ),
            SemanticRuntimeBootstrapCodec.decode(
                """{"schemaVersion":1,"bootstrap":{"state":"starting","attemptId":"not-a-uuid"}}""",
            ),
        )
    }

    private fun Refinement<
        SemanticRuntimeBootstrapAttemptId,
        SemanticRuntimeBootstrapAttemptIdFailure,
        >.refined(): SemanticRuntimeBootstrapAttemptId =
        (this as Refinement.Refined).value
}
