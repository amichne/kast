package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class QueryBindingRetentionTest {
    @Test
    fun `empty binding result retains its row kind and immutable qualification`() {
        val limitation = QueryLimitation.JOIN_INPUT_INCOMPLETE
        val coverage = QueryCoverage.Qualified.create(QueryCount.parse(0).refined(), setOf(limitation)).refined()
        val mode = QueryJoinMode.Inner.create(bindingName("left"), bindingName("right")).refined()
        val result = QueryResult(QueryRows.Bindings.of(emptyList(), mode), emptyList())
        val execution = QueryExecutionResult.Qualified(result, coverage)
        val retained = QueryRetainedResult.capture(lease(), execution).refined()

        val bindings = assertInstanceOf(QueryRetainedResult.Bindings::class.java, retained)
        assertEquals(mode, bindings.mode)
        assertEquals(emptyList<QueryBindingRow>(), bindings.bindingRows)
        assertEquals(0, bindings.rowCount)
        assertEquals(0, bindings.selectRows(emptyList()).refined().rowCount)
        assertEquals(
            Refinement.Rejected(QueryMembershipFailure.INCOMPLETE),
            bindings.completeMembership(),
        )
        val returnedCoverage = assertInstanceOf(QueryCoverage.Qualified::class.java, bindings.coverage)
        assertThrows(UnsupportedOperationException::class.java) {
            (returnedCoverage.limitations as MutableList<QueryLimitation>).clear()
        }
        assertEquals(listOf(limitation), (bindings.coverage as QueryCoverage.Qualified).limitations)
        assertEquals(
            QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.UnknownBindingName(bindingName("unknown"))),
            QueryPlanCompiler.admit(
                QueryPlanSyntax(
                    QuerySourceSyntax.Retained(bindings),
                    listOf(QueryStepSyntax.ProjectBinding(bindingName("unknown"))),
                    QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()),
                )
            ),
        )
        assertEquals(
            QueryPlanAdmission.Rejected(QueryPlanAdmissionFailure.OutputTypeMismatch),
            QueryPlanCompiler.admit(
                QueryPlanSyntax(
                    QuerySourceSyntax.Retained(bindings),
                    listOf(QueryStepSyntax.Distinct),
                    QueryOutputSyntax.BindingRows,
                )
            ),
        )
    }

    @Test
    fun `empty complete symbol result yields a proof bound to those rows`() {
        val basis = lease()
        val result = QueryResult(QueryRows.Symbols.of(emptyList()), emptyList())
        val execution = QueryExecutionResult.Complete(result, QueryCoverage.Complete(QueryCount.parse(0).refined()))
        val retained = QueryRetainedResult.capture(basis, execution).refined()

        val symbols = assertInstanceOf(QueryRetainedResult.Symbols::class.java, retained)
        val proof = QueryCompleteMembership.from(symbols).refined()
        assertEquals(symbols, proof.source)
        assertEquals(emptyList<QuerySymbol>(), proof.symbols)
        assertEquals(basis, proof.lease)
    }

    @Test
    fun `joined row from another basis is rejected during retention`() {
        val original = lease()
        val foreign =
            SemanticReadLease(
                original.workspaceRoot,
                EvidenceGeneration.parse(8L).refined(),
            )
        val selector = selector(original)
        val symbol = QuerySymbol(SymbolDescription.from(selector), emptyList())
        val mode = QueryJoinMode.Inner.create(bindingName("left"), bindingName("right")).refined()
        val row = QueryBindingRow.join(mode, symbol, symbol).refined()
        val result = QueryResult(QueryRows.Bindings.of(listOf(row), mode), emptyList())
        val execution = QueryExecutionResult.Complete(result, QueryCoverage.Complete(QueryCount.parse(1).refined()))

        assertEquals(
            Refinement.Rejected(QueryRetainedResultFailure.BASIS_MISMATCH),
            QueryRetainedResult.capture(foreign, execution),
        )
    }

    @Test
    fun `equal display names do not establish join identity`() {
        val basis = lease()
        val first = QuerySymbol(SymbolDescription.from(selector(basis, "Service.kt")), emptyList())
        val second = QuerySymbol(SymbolDescription.from(selector(basis, "Other.kt")), emptyList())
        val mode = QueryJoinMode.Inner.create(bindingName("left"), bindingName("right")).refined()

        assertEquals(
            Refinement.Rejected(QueryBindingRowFailure.DIFFERENT_SYMBOL_IDENTITIES),
            QueryBindingRow.join(mode, first, second),
        )
    }

    private fun selector(lease: SemanticReadLease, fileName: String = "Service.kt"): SymbolSelector {
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    "call",
                    lease,
                    Path.of("/workspace/$fileName"),
                    "file:///workspace/$fileName",
                    0,
                )
                .refined()
        val file = (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).file
        val signature =
            CanonicalCompilerSignature.function("sample.Service.call", null, emptyList(), emptyList(), 0).refined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    10,
                    "call",
                    "sample.Service.call",
                    CompilerSymbolKind.FUNCTION,
                    signature,
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

    private fun bindingName(raw: String): QueryBindingName = QueryBindingName.parse(raw).refined()

    private fun lease(): SemanticReadLease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(7L).refined(),
        )

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refinement, got $failure")
        }
}
