package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QualificationProofContractTest {
    @Test
    fun `walk page coverage preserves finite incomplete evidence and immutable limitations`() {
        val resumable =
            QueryWalkCoverageDocument.resumable(
                    listOf(
                        TraversalLimitationDocument.RECORD_LIMIT_REACHED,
                        TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
                    ),
                    listOf(RelationLimitationDocument.UNRESOLVED_TARGET),
                )
                .refined()
        assertEquals(2, resumable.limitations.size)
        assertEquals(1, resumable.relationLimitations.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST") (resumable.limitations as MutableList<TraversalLimitationDocument>).clear()
        }
        assertInstanceOf(
            Refinement.Rejected::class.java,
            QueryWalkCoverageDocument.resumable(
                listOf(TraversalLimitationDocument.ONE_HOP_INCOMPLETE),
                emptyList(),
            ),
        )
        assertInstanceOf(
            QueryWalkCoverageDocument.TerminalIncomplete::class.java,
            QueryWalkCoverageDocument.terminalIncomplete(
                    listOf(TraversalLimitationDocument.DEPTH_LIMIT_REACHED),
                    emptyList(),
                )
                .refined(),
        )
    }

    @Test
    fun `diagnostic qualification retains truncation and file coverage together`() {
        val qualification =
            DiagnosticCheckQualification.create(
                    DiagnosticKnownCountDocument.parse(3).refined(),
                    resultLimitReached = true,
                    analyzedFiles = listOf(text("src/A.kt")),
                    limitations =
                        listOf(
                            DiagnosticLimitationDocument(
                                text("src/B.kt"),
                                DiagnosticLimitationReasonDocument.INDEXING,
                            )
                        ),
                )
                .refined()

        assertEquals(true, qualification.resultLimitReached)
        assertEquals(listOf(text("src/A.kt")), qualification.analyzedFiles)
        assertEquals("src/B.kt", qualification.limitations.single().file.value)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST") (qualification.analyzedFiles as MutableList<ProtocolText>).clear()
        }
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
