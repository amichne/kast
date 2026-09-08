package io.github.amichne.kast.distribution.contract.bootstrap

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SemanticRuntimeBootstrapTest {
    @Test
    fun `model input capture is a distinct boundary after JVM selection`() {
        assertEquals("capturing-model-inputs", SemanticRuntimeBootstrapPhase.entries[2].wireName)
    }

    @Test
    fun `structured input causes round trip and malformed evidence fails closed`() {
        val input = ModelInputFailure((ModelInputPath.admit("gradle.properties") as Refinement.Refined).value, ModelInputFailureReason.TARGET_MISSING)
        val state = SemanticRuntimeBootstrapState.Rejected(attempt, SemanticRuntimeBootstrapCause.ModelInput(input), SemanticRuntimeBootstrapPhase.MODEL_INPUT_CAPTURE)
        val encoded = SemanticRuntimeBootstrapCodec.encode(state)
        assertEquals(Refinement.Refined(state), SemanticRuntimeBootstrapCodec.decode(encoded))
        for (malformed in listOf(
            encoded.replace("\"path\":\"gradle.properties\",", ""),
            encoded.replace(",\"reason\":\"TARGET_MISSING\"", ""),
            encoded.replace("gradle.properties", "../outside"),
            encoded.replace("gradle.properties", "/absolute"),
            encoded.replace("TARGET_MISSING", "UNKNOWN"),
            """{"schemaVersion":3,"bootstrap":{"state":"rejected","attemptId":"123e4567-e89b-42d3-a456-426614174000","phase":"capturing-model-inputs","cause":{"state":"standard","failure":"model-input-rejected"}}}""",
        )) {
            assertEquals(Refinement.Rejected(SemanticRuntimeBootstrapDocumentFailure.MALFORMED_DOCUMENT), SemanticRuntimeBootstrapCodec.decode(malformed), malformed)
        }
    }

    private val attempt = SemanticRuntimeBootstrapAttemptId.admit(
        "123e4567-e89b-42d3-a456-426614174000",
    ).refined()

    @Test
    fun `every bootstrap state round trips through the versioned contract`() {
        val states = SemanticRuntimeBootstrapFailure.entries.filter { it != SemanticRuntimeBootstrapFailure.MODEL_INPUT_REJECTED }.map(
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
    fun `starting document exposes runtime discovery phase`() {
        assertEquals(
            """{"schemaVersion":3,"bootstrap":{"state":"starting","attemptId":"123e4567-e89b-42d3-a456-426614174000","phase":"discovering-runtime","gradleJvm":{"state":"io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionObservation.Unobserved"}}}""",
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
                """{"schemaVersion":3,"bootstrap":{"state":"unknown","attemptId":"123e4567-e89b-42d3-a456-426614174000"}}""",
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
                """{"schemaVersion":4,"bootstrap":{"state":"future","newField":true}}""",
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
                """{"schemaVersion":3,"bootstrap":{"state":"starting","attemptId":"not-a-uuid"}}""",
            ),
        )
    }

    @Test
    fun `every phase is retained and unknown phases cannot be admitted`() {
        SemanticRuntimeBootstrapPhase.entries.forEach { phase ->
            val state = SemanticRuntimeBootstrapState.Starting(attempt, phase)
            assertEquals(Refinement.Refined(state), SemanticRuntimeBootstrapCodec.decode(SemanticRuntimeBootstrapCodec.encode(state)))
        }
        assertEquals(
            Refinement.Rejected(SemanticRuntimeBootstrapDocumentFailure.MALFORMED_DOCUMENT),
            SemanticRuntimeBootstrapCodec.decode(
                """{"schemaVersion":3,"bootstrap":{"state":"starting","attemptId":"123e4567-e89b-42d3-a456-426614174000","phase":"unproven-phase"}}""",
            ),
        )
    }

    @Test
    fun `missing phase in schema two is incomplete evidence`() {
        assertEquals(
            Refinement.Rejected(SemanticRuntimeBootstrapDocumentFailure.MALFORMED_DOCUMENT),
            SemanticRuntimeBootstrapCodec.decode(
                """{"schemaVersion":3,"bootstrap":{"state":"starting","attemptId":"123e4567-e89b-42d3-a456-426614174000"}}""",
            ),
        )
    }

    @Test
    fun `legacy bootstrap document cannot prove phase and is rejected as unsupported`() {
        assertEquals(
            Refinement.Rejected(SemanticRuntimeBootstrapDocumentFailure.UNSUPPORTED_SCHEMA),
            SemanticRuntimeBootstrapCodec.decode(
                """{"schemaVersion":1,"bootstrap":{"state":"starting","attemptId":"123e4567-e89b-42d3-a456-426614174000"}}""",
            ),
        )
    }

    private fun Refinement<
        SemanticRuntimeBootstrapAttemptId,
        SemanticRuntimeBootstrapAttemptIdFailure,
        >.refined(): SemanticRuntimeBootstrapAttemptId =
        (this as Refinement.Refined).value
}
