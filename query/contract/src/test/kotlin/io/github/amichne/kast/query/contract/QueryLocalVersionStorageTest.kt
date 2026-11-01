package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryLocalVersionStorageTest {
    @Test
    fun `retained sites charge exact proof and share only its actual object identity`() {
        val owner = QueryImpactLedgerFixture().owner
        val proof =
            ValueLocalVersion.Defined.fromCompiler(
                    ValueExecutionOwner.declaration(owner),
                    range(10, 30),
                    range(5, 195),
                    range(10, 30),
                    range(20, 30),
                    ValueLocalVersion.End.NextWrite(range(60, 80), range(70, 80)),
                )
                .value()
        val source =
            ValueSite.fromCompiler(owner, proof.definition, ValueRole.LocalBinding, localVersion = proof).value()
        val plain = ValueSite.fromCompiler(owner, proof.definition, ValueRole.LocalBinding).value()
        val graph = QueryImpactRetainedGraph()
        val first = graph.site(source)
        assertTrue(first > QueryImpactRetainedGraph().site(plain))
        assertTrue(graph.site(source) < first)
        val equalDetachedProof =
            ValueLocalVersion.Defined.fromCompiler(
                    proof.execution,
                    proof.declaration,
                    proof.block,
                    proof.definition,
                    proof.rhs,
                    proof.end,
                )
                .value()
        val detached =
            ValueSite.fromCompiler(owner, proof.definition, ValueRole.LocalBinding, localVersion = equalDetachedProof)
                .value()
        val shared = source.withState().value()
        assertTrue(graph.site(detached) > graph.site(shared))
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
