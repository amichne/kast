package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ExactRenameOccurrence
import io.github.amichne.kast.api.contract.result.ExactRenameProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RenameOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ResultCardinality
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactRenameProofTest {
    @Test
    fun `proof retains exact target identity and compiler occurrence count`() {
        val target = target("sample.greet")
        val mutableOccurrences = mutableListOf(occurrence(target, startOffset = 31))

        val proof = ExactRenameProof.of(
            target = target,
            requiredGeneration = MutationSemanticGeneration(7),
            evidence = completeEvidence(occurrenceCount = 1),
            occurrences = mutableOccurrences,
        )
        mutableOccurrences.clear()

        assertEquals(target, proof.target)
        assertEquals(7, proof.requiredGeneration.value)
        assertEquals(1, proof.evidence.cardinality.totalCount)
        assertEquals(1, proof.occurrences.size)
        assertEquals(target, proof.occurrences.single().resolvedTarget)
        assertEquals(RenameOccurrenceProvenance.COMPILER, proof.occurrences.single().provenance)
        assertNotSame(mutableOccurrences, proof.occurrences)
    }

    @Test
    fun `proof rejects a cardinality that differs from compiler occurrences`() {
        val target = target("sample.greet")

        assertThrows(IllegalArgumentException::class.java) {
            ExactRenameProof.of(
                target = target,
                requiredGeneration = MutationSemanticGeneration(7),
                evidence = completeEvidence(occurrenceCount = 2),
                occurrences = listOf(occurrence(target, startOffset = 31)),
            )
        }
    }

    @Test
    fun `proof rejects an occurrence bound to another target`() {
        val target = target("sample.greet")

        assertThrows(IllegalArgumentException::class.java) {
            ExactRenameProof.of(
                target = target,
                requiredGeneration = MutationSemanticGeneration(7),
                evidence = completeEvidence(occurrenceCount = 1),
                occurrences = listOf(occurrence(target("sample.other"), startOffset = 31)),
            )
        }
    }

    private fun target(fqName: String): SymbolIdentity = SymbolIdentity(
        fqName = fqName,
        kind = SymbolKind.FUNCTION,
        declarationFile = NormalizedPath.parse("/workspace/src/Sample.kt"),
        declarationStartOffset = NonNegativeInt(12),
    )

    private fun occurrence(target: SymbolIdentity, startOffset: Int): ExactRenameOccurrence =
        ExactRenameOccurrence(
            reference = ReferenceOccurrence(
                location = Location(
                    filePath = "/workspace/src/Usage.kt",
                    startOffset = startOffset,
                    endOffset = startOffset + 5,
                    startLine = 3,
                    startColumn = 9,
                    preview = "greet()",
                ),
                containingSymbol = ContainingSymbolEvidence.TopLevel,
            ),
            resolvedTarget = target,
            provenance = RenameOccurrenceProvenance.COMPILER,
        )

    private fun completeEvidence(occurrenceCount: Int): RelationshipResultEvidence.Complete =
        RelationshipResultEvidence.Complete(
            cardinality = ResultCardinality.Exact(occurrenceCount),
            coverage = RelationshipSearchCoverage.complete(),
        )
}
