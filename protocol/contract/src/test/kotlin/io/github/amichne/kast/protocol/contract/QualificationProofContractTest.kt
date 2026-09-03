package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QualificationProofContractTest {
    @Test
    fun `relation qualification is canonical immutable proof`() {
        val continuation = relationContinuationDocument()
        val qualification = RelationReadQualification.resumable(
            RelationKnownMinimumDocument.parse(2).refined(),
            listOf(
                RelationLimitationDocument.RESULT_LIMIT_REACHED,
                RelationLimitationDocument.PROVIDER_INCOMPLETE,
            ),
            continuation,
        ).refined()

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (qualification.limitations as MutableList<RelationLimitationDocument>).clear()
        }
        assertEquals(2, qualification.limitations.size)
        assertInstanceOf(
            Refinement.Rejected::class.java,
            RelationReadQualification.resumable(
                qualification.knownMinimum,
                qualification.limitations.reversed(),
                qualification.continuation,
            ),
        )

        val terminal = RelationReadQualification.terminalIncomplete(
            RelationKnownMinimumDocument.parse(0).refined(),
            listOf(RelationLimitationDocument.UNRESOLVED_TARGET),
        ).refined()
        assertInstanceOf(RelationReadQualification.TerminalIncomplete::class.java, terminal)
    }

    @Test
    fun `relation request position is a closed start or resume shape`() {
        val start = RelationReadRequest(
            text("exact:Target"),
            RelationKindDocument.CALLERS,
            ProtocolCount.parse(2).refined(),
            RelationReadPositionDocument.Start,
        )
        val resume = start.copy(
            position = RelationReadPositionDocument.Resume(relationContinuationDocument()),
        )

        assertInstanceOf(RelationReadPositionDocument.Start::class.java, start.position)
        assertInstanceOf(RelationReadPositionDocument.Resume::class.java, resume.position)
        assertInstanceOf(
            Refinement.Rejected::class.java,
            RelationContinuationDocument.parse("a".repeat(64)),
        )
    }

    @Test
    fun `traversal request and qualification distinguish resumable from terminal state`() {
        val continuation = traversalContinuationDocument()
        val start = TraversalRunRequest(
            text("exact:Target"),
            RelationKindDocument.CALLEES,
            ProtocolCount.parse(2).refined(),
            ProtocolCount.parse(4).refined(),
            TraversalRunPositionDocument.Start,
        )
        val resume = start.copy(
            position = TraversalRunPositionDocument.Resume(continuation),
        )
        val qualification = TraversalRunQualification.resumable(
            listOf(
                TraversalLimitationDocument.RECORD_LIMIT_REACHED,
                TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
            ),
            listOf(
                RelationLimitationDocument.UNRESOLVED_TARGET,
                RelationLimitationDocument.PROVIDER_INCOMPLETE,
            ),
            continuation,
        ).refined()

        assertInstanceOf(TraversalRunPositionDocument.Start::class.java, start.position)
        assertInstanceOf(TraversalRunPositionDocument.Resume::class.java, resume.position)
        assertEquals(2, qualification.limitations.size)
        assertEquals(2, qualification.relationLimitations.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (qualification.relationLimitations as MutableList<RelationLimitationDocument>).clear()
        }

        val terminal = TraversalRunQualification.terminalIncomplete(
            listOf(TraversalLimitationDocument.ONE_HOP_INCOMPLETE),
            listOf(RelationLimitationDocument.UNRESOLVED_TARGET),
        ).refined()
        val depthTerminal = TraversalRunQualification.terminalIncomplete(
            listOf(TraversalLimitationDocument.DEPTH_LIMIT_REACHED),
            emptyList(),
        ).refined()
        assertInstanceOf(TraversalRunQualification.TerminalIncomplete::class.java, terminal)
        assertInstanceOf(TraversalRunQualification.TerminalIncomplete::class.java, depthTerminal)
        assertInstanceOf(
            Refinement.Rejected::class.java,
            TraversalRunQualification.resumable(
                listOf(TraversalLimitationDocument.DEPTH_LIMIT_REACHED),
                emptyList(),
                continuation,
            ),
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            TraversalContinuationDocument.parse("b".repeat(64)),
        )
    }

    @Test
    fun `diagnostic qualification retains truncation and file coverage together`() {
        val qualification = DiagnosticCheckQualification.create(
            DiagnosticKnownCountDocument.parse(3).refined(),
            resultLimitReached = true,
            analyzedFiles = listOf(text("src/A.kt")),
            limitations = listOf(
                DiagnosticLimitationDocument(
                    text("src/B.kt"),
                    DiagnosticLimitationReasonDocument.INDEXING,
                ),
            ),
        ).refined()

        assertEquals(true, qualification.resultLimitReached)
        assertEquals(listOf(text("src/A.kt")), qualification.analyzedFiles)
        assertEquals("src/B.kt", qualification.limitations.single().file.value)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (qualification.analyzedFiles as MutableList<ProtocolText>).clear()
        }
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun relationContinuationDocument(): RelationContinuationDocument {
        val payload = "self-contained".toByteArray()
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return RelationContinuationDocument.parse(
            "relation-continuation:v1:$encoded:$digest",
        ).refined()
    }

    private fun traversalContinuationDocument(): TraversalContinuationDocument {
        val payload = "self-contained-checkpoint".toByteArray()
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return TraversalContinuationDocument.parse(
            "traversal-continuation:v1:$encoded:$digest",
        ).refined()
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
