package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactLocalLifetimeEndDocument
import io.github.amichne.kast.protocol.contract.ImpactLocalVersionDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactLocalVersionProjectionTest {
    @Test
    fun `site projection preserves both definition lifetimes without changing ordinary sites`() {
        val owner = ImpactPathProjectionFixture().owner
        assertEquals(
            ImpactLocalVersionDocument.Unversioned,
            ValueSite.fromCompiler(owner, range(10, 30), ValueRole.LocalBinding)
                .value()
                .impactDocument()
                .value()
                .localVersion,
        )
        for ((end, expectedEnd) in
            listOf(
                ValueLocalVersion.End.NextWrite(range(60, 80), range(70, 80)) to
                    ImpactLocalLifetimeEndDocument.NextWrite(wireRange(60, 80), wireRange(70, 80)),
                ValueLocalVersion.End.BlockEnd(range(5, 195)) to
                    ImpactLocalLifetimeEndDocument.BlockEnd(wireRange(5, 195)),
            )) {
            val proof =
                ValueLocalVersion.Defined.fromCompiler(
                        ValueExecutionOwner.declaration(owner),
                        range(10, 30),
                        range(5, 195),
                        range(10, 30),
                        range(20, 30),
                        end,
                    )
                    .value()
            val site =
                ValueSite.fromCompiler(owner, proof.definition, ValueRole.LocalBinding, localVersion = proof).value()
            assertEquals(
                ImpactLocalVersionDocument.Defined(
                    wireRange(10, 30),
                    wireRange(5, 195),
                    wireRange(10, 30),
                    wireRange(20, 30),
                    expectedEnd,
                ),
                site.impactDocument().value().localVersion,
            )
        }
    }

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

    private fun wireRange(start: Int, end: Int) =
        ImpactSourceRangeDocument(ProtocolOffset.parse(start).value(), ProtocolOffset.parse(end).value())

    private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
}
