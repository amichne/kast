package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryOccurrenceSnapshotTest {
    @Test
    fun `exact reference admission freezes caller and exposed membership`() {
        val first = selector()
        val second = selector("Other.kt")
        val input = mutableListOf(first, second)
        val admitted = QueryExactReferences.from(input).refined()
        input.reverse()
        input.clear()
        assertEquals(listOf(first, second), admitted.values)
        assertThrows(UnsupportedOperationException::class.java) {
            (admitted.values as MutableList<SymbolSelector>).clear()
        }
        assertSame(first, admitted.values[0])
        assertSame(second, admitted.values[1])
    }

    @Test
    fun `declaration occurrence snapshots retain immutable connection proof through capture and selection`() {
        val selected = selector()
        val request = RelationRequest.start(selected, RelationMeaning.References, relationBudget())
        val fact =
            RelationFact.create(
                    request,
                    request.subject,
                    request.subject,
                    RelationOccurrence.fromBoundary(selected.file, 20, 21).refined(),
                    RelationProvenance.K2_AUTHORED_SOURCE,
                )
                .refined()
        val connections = mutableListOf(fact)
        val original = QueryOccurrence.Declaration(QuerySymbol(SymbolDescription.from(selected), connections), fact)
        val inputs = mutableListOf<QueryOccurrence>(original)
        val rows = QueryRows.Occurrences.of(inputs)
        connections.clear()
        inputs.clear()
        val snapshot = rows.values.single() as QueryOccurrence.Declaration
        assertSame(fact, snapshot.fact)
        assertEquals(listOf(fact), snapshot.symbol.connections)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.symbol.connections as MutableList<RelationFact>).clear()
        }
        val execution =
            QueryExecutionResult.Complete.create(
                QueryResult(rows, emptyList()),
                QueryCoverage.Complete(QueryCount.parse(1).refined()),
            )
        val retained =
            QueryRetainedResult.capture(selected.lease, execution).refined() as QueryRetainedResult.Occurrences
        val retainedOccurrence = retained.occurrences.single() as QueryOccurrence.Declaration
        assertSame(fact, retainedOccurrence.fact)
        assertEquals(listOf(fact), retainedOccurrence.symbol.connections)
        assertThrows(UnsupportedOperationException::class.java) {
            (retainedOccurrence.symbol.connections as MutableList<RelationFact>).clear()
        }
        assertEquals(retained.occurrences, retained.selectRows(listOf(0)).refined().occurrences)
    }

    private fun selector(fileName: String = "Subject.kt"): SymbolSelector {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(7).refined())
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/workspace/$fileName")).refined()
            )
        val name = fileName.removeSuffix(".kt").lowercase()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    6,
                    name,
                    "sample.$name",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("sample.$name", null, emptyList(), emptyList(), 0).refined(),
                )
                .refined()
        return SymbolSelector.issue(
            lease,
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_ONLY,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            ),
            evidence,
        )
    }

    private fun relationBudget() =
        RelationBudget(
            ResourceBudget(
                ResultLimit.parse(1).refined(),
                WorkUnitLimit.parse(100).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            ),
            RelationByteLimit.parse(100_000).refined(),
        )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}
