package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ReadResumeActionDocument
import io.github.amichne.kast.protocol.contract.SourceCheckpointDocument
import io.github.amichne.kast.protocol.contract.SourceQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.SourceTerminalReasonDocument
import io.github.amichne.kast.source.contract.SourceEntityCount
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadQualification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SourceProgressProjectionTest {
    @Test
    fun `empty native pages preserve admitted upstream resumability`() {
        val token = SourceReadContinuation.parse("source-read-continuation-v1|" + "a".repeat(64)).proven()
        val qualification =
            SourceReadQualification.create(
                    SourceEntityCount.parse(0).proven(),
                    setOf(SourceReadLimitation.WORK_LIMIT_REACHED),
                    SourceReadContinuationState.Available(token),
                )
                .proven()
        val progress =
            assertInstanceOf(
                SourceQualifiedProgressDocument.Resumable::class.java,
                qualification.projectProgress().proven(),
            )
        val checkpoint = assertInstanceOf(SourceCheckpointDocument.Upstream::class.java, progress.checkpoint)
        assertEquals(ReadResumeActionDocument.RESUME, progress.nextAction)
        assertEquals(token.value, checkpoint.token.value)
    }

    @Test
    fun `withheld text and upstream coverage gaps retain distinct terminal explanations`() {
        for ((limitations, reason) in
            listOf(
                setOf(SourceReadLimitation.TEXT_BYTE_LIMIT_REACHED) to
                    SourceTerminalReasonDocument.TEXT_PROJECTION_WITHHELD,
                setOf(SourceReadLimitation.PROVIDER_FAILURE) to SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE,
                setOf(SourceReadLimitation.TEXT_BYTE_LIMIT_REACHED, SourceReadLimitation.WORK_LIMIT_REACHED) to
                    SourceTerminalReasonDocument.UPSTREAM_INCOMPLETE,
                setOf(SourceReadLimitation.RETENTION_LIMIT_REACHED) to
                    SourceTerminalReasonDocument.RETENTION_CAPACITY_EXCEEDED,
            )) {
            val qualification =
                SourceReadQualification.create(
                        SourceEntityCount.parse(0).proven(),
                        limitations,
                        SourceReadContinuationState.Unavailable,
                    )
                    .proven()
            assertEquals(
                SourceQualifiedProgressDocument.TerminalIncomplete(reason),
                qualification.projectProgress().proven(),
            )
        }
    }

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}
