package io.github.amichne.kast.query.service

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.query.contract.ExactQueryStage
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryGroupingEvidence
import io.github.amichne.kast.query.contract.QueryOutputSyntax
import io.github.amichne.kast.query.contract.QuerySymbol
import io.github.amichne.kast.query.contract.QuerySymbolField
import io.github.amichne.kast.query.contract.QuerySymbolFields
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalSymbolId
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryCheckpointTraceProofTest {
    private val fixture = QueryServiceTest()
    private val selector = fixture.selector(fixture.selection())
    private val second = secondRow()
    private val row = QuerySymbol(SymbolDescription.from(selector), emptyList())
    private val emit = ExactQueryStage.Emit(QueryOutputSyntax.Symbols(QuerySymbolFields.from(emptySet()).refined()))
    private val trace = ExactQueryStage.Distinct(emit, QueryGroupingEvidence.ALL_ARRIVALS)

    @Test
    fun `proof snapshot obeys row and byte capacity without removing grouped state`() {
        val checkpoint = checkpoint(trace)
        val groupsBefore = checkpoint.identityRows
        val tooSmall = checkpoint.traceProof(ResultLimit.parse(1).refined(), QueryByteLimit.parse(1).refined())
        assertTrue(tooSmall.values.isEmpty())
        val exact =
            checkpoint.traceProof(
                ResultLimit.parse(1).refined(),
                QueryByteLimit.parse(row.projectedUtf8Size()).refined(),
            )
        assertEquals(listOf(selector), exact.values.map { it.selector })
        assertEquals(groupsBefore, checkpoint.identityRows)
        assertEquals(0, checkpoint.emittedCount.value)
        assertEquals(
            listOf(selector),
            checkpoint
                .traceProof(ResultLimit.parse(1).refined(), QueryByteLimit.parse(row.projectedUtf8Size()).refined())
                .values
                .map { it.selector },
        )
    }

    @Test
    fun `proof excludes nonterminal and ordinary distinct groups before collecting rows`() {
        val stages = listOf(ExactQueryStage.Distinct(emit), trace.copy(next = ExactQueryStage.Distinct(emit)))
        for (stage in stages) {
            assertTrue(
                checkpoint(stage)
                    .traceProof(ResultLimit.parse(1).refined(), QueryByteLimit.parse(100_000).refined())
                    .values
                    .isEmpty()
            )
        }
    }

    @Test
    fun `proof never invents a selected source projection`() {
        val sourceOutput =
            ExactQueryStage.Emit(
                QueryOutputSyntax.Symbols(QuerySymbolFields.from(setOf(QuerySymbolField.SOURCE)).refined())
            )
        val source = checkpoint(trace.copy(next = sourceOutput))
        assertTrue(
            source.traceProof(ResultLimit.parse(1).refined(), QueryByteLimit.parse(100_000).refined()).values.isEmpty()
        )
        assertEquals(2, source.identityRows.values.single().size)
    }

    private fun secondRow(): QuerySymbol {
        val basis = fixture.selection()
        val path = Path.of(selector.file.stableValue)
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    "other",
                    selector.lease,
                    path,
                    path.toUri().toString(),
                    30,
                )
                .refined()
        val selection =
            SymbolDiscoverySelection.restore(selector.lease, selector.scope, candidate, basis.constraints).refined()
        val signature =
            CanonicalCompilerSignature.function("sample.PaymentService.other", null, emptyList(), emptyList(), 0)
                .refined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    selector.file,
                    30,
                    40,
                    "other",
                    "sample.PaymentService.other",
                    CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .refined()
        return QuerySymbol(SymbolDescription.from(SymbolSelector.issue(selection, evidence).refined()), emptyList())
    }

    private fun checkpoint(stage: ExactQueryStage): PipelineCheckpoint =
        PipelineCheckpoint(
            PipelineSeed.Accounted.create(fixture.symbolPlan()),
            selector.lease,
            emptyList(),
            mapOf(
                stage to
                    linkedMapOf(
                        QueryIdentityRowKey.Declaration(CanonicalSymbolId.from(selector)) to row,
                        QueryIdentityRowKey.Declaration(CanonicalSymbolId.from(second.selector)) to second,
                    )
            ),
            QueryJoinSnapshot(emptyMap()),
            emptySet(),
            0.queryCount(),
        )
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
