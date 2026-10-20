package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.protocol.contract.QueryImpactDeclarationDocument
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RevalidatedRelationEndpoint
import io.github.amichne.kast.relation.contract.ValueKnownDeclarations
import io.github.amichne.kast.relation.contract.ValueModelDeclarationRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedCompilerPort
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.symbol.contract.SymbolSelector
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class QueryKnownDeclarationsSourceAcquisitionTest : QueryImpactSourceAdmissionCase() {
    @Test
    fun `an unused explicit declaration enters the source with its original proof and acquisition work`() = runTest {
        var seeds = 0
        var declarations = 0
        val current =
            RevalidatedRelationEndpoint.validate(
                    RelationEndpoint.subject(SymbolSelector.issue(callable.lease, callable.scope, callable.evidence)),
                    callable.evidence,
                )
                .refined()
        val compiler =
            object : ValueProducerSeedCompilerPort {
                override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                    assertEquals(0, seeds++)
                    return ValueProducerSeedRead.Seeded(proof(request), work(3))
                }

                override suspend fun revalidate(
                    selector: SymbolSelector,
                    budget: RelationBudget,
                ): ValueModelDeclarationRead {
                    assertEquals(0, declarations++)
                    assertEquals(callable.compilerIdentity, selector.compilerIdentity)
                    return ValueModelDeclarationRead.Revalidated(current, work(2))
                }
            }
        val source =
            document()
                .copy(
                    declarations =
                        bounded(listOf(QueryImpactDeclarationDocument(token(callable), declaration(callable))))
                )
        val admitted = source.admitImpact(owner.lease, references, compiler, budget).refined()
        assertEquals(5L, admitted.examinedWork)
        assertEquals(1, seeds)
        assertEquals(1, declarations)
        assertEquals(1, admitted.source.knownDeclarations.declarations.size)
        assertSame(current, admitted.source.knownDeclarations.declarations.single())
        assertEquals(
            emptyList<io.github.amichne.kast.relation.contract.RepresentationRule>(),
            admitted.source.representationModels,
        )
    }

    @Test
    fun `restored producer selectors do not become explicit known declarations or trigger another native read`() =
        runTest {
            var seeds = 0
            val compiler =
                object : ValueProducerSeedCompilerPort {
                    override suspend fun seed(request: ValueProducerSeedRequest): ValueProducerSeedRead {
                        assertEquals(0, seeds++)
                        return ValueProducerSeedRead.Seeded(proof(request), work(3))
                    }

                    override suspend fun revalidate(
                        selector: SymbolSelector,
                        budget: RelationBudget,
                    ): ValueModelDeclarationRead = error("unexpected explicit declaration read")
                }
            val admitted = document().admitImpact(owner.lease, references, compiler, budget).refined()
            assertSame(ValueKnownDeclarations.Empty, admitted.source.knownDeclarations)
            assertEquals(3L, admitted.examinedWork)
            assertEquals(1, seeds)
        }
}
