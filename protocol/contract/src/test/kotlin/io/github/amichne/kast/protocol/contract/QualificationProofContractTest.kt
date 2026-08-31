package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QualificationProofContractTest {
    @Test
    fun `relation qualification is canonical immutable proof`() {
        val qualification = RelationReadQualification.create(
            RelationKnownMinimumDocument.parse(2).refined(),
            listOf(
                RelationLimitationDocument.RESULT_LIMIT_REACHED,
                RelationLimitationDocument.PROVIDER_INCOMPLETE,
            ),
            RelationContinuationDocument.parse("a".repeat(64)).refined(),
        ).refined()

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (qualification.limitations as MutableList<RelationLimitationDocument>).clear()
        }
        assertEquals(2, qualification.limitations.size)
        assertInstanceOf(
            Refinement.Rejected::class.java,
            RelationReadQualification.create(
                qualification.knownMinimum,
                qualification.limitations.reversed(),
                qualification.continuation,
            ),
        )
    }

    @Test
    fun `traversal qualification retains co-occurring limitation families`() {
        val qualification = TraversalRunQualification.create(
            listOf(
                TraversalLimitationDocument.RECORD_LIMIT_REACHED,
                TraversalLimitationDocument.ONE_HOP_INCOMPLETE,
            ),
            listOf(
                RelationLimitationDocument.UNRESOLVED_TARGET,
                RelationLimitationDocument.PROVIDER_INCOMPLETE,
            ),
            TraversalContinuationDocument.parse("b".repeat(64)).refined(),
        ).refined()

        assertEquals(2, qualification.limitations.size)
        assertEquals(2, qualification.relationLimitations.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (qualification.relationLimitations as MutableList<RelationLimitationDocument>).clear()
        }
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

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
