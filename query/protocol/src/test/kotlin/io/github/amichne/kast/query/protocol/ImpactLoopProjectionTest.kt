package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactLoopCompletionDocument
import io.github.amichne.kast.protocol.contract.ImpactLoopResultDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.admitsExecutionOwner
import io.github.amichne.kast.protocol.contract.protocol
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueLoopIteration
import io.github.amichne.kast.relation.contract.ValueLoopProtocol
import io.github.amichne.kast.relation.contract.ValueLoopTransfer
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactLoopProjectionTest {
    @Test
    fun `projection retains selected calls phases and exact normal true receiver evidence`() {
        val f = ImpactPathProjectionFixture()
        val protocol = protocol(f)
        val iteration = ValueLoopIteration.fromCompilerNormalTrue(protocol)
        val source =
            ValueSite.fromCompiler(f.owner, f.producer.range, ValueRole.LoopResult(protocol.iteratorResult)).value()
        val target =
            ValueSite.fromCompiler(
                    f.owner,
                    f.producer.range,
                    ValueRole.Receiver(protocol.next, ValueReceiverKind.DISPATCH),
                )
                .value()
        val transfer =
            ValueTransfer.fromCompiler(
                    source,
                    target,
                    ValueTransferKind.RECEIVER,
                    ValueLoopTransfer.Advance.fromCompiler(iteration, ValueReceiverKind.DISPATCH),
                )
                .value()
                .impactDocument()
                .value()
        val proof = transfer.evidence as ImpactTransferEvidenceDocument.LoopAdvance
        val role = transfer.source.role as ImpactValueRoleDocument.LoopResult
        assertEquals(ImpactLoopCompletionDocument.NORMAL_TRUE, proof.iteration.condition)
        assertEquals(protocol.iterator.impactDocument().value(), role.result.protocol.iterator)
        assertEquals(protocol.hasNext.impactDocument().value(), role.result.protocol.hasNext)
        assertEquals(protocol.next.impactDocument().value(), role.result.protocol.next)
        assertTrue(role.result is ImpactLoopResultDocument.Iterator)
        assertTrue(transfer.source.admitsExecutionOwner())
        assertTrue(transfer.admitsEvidence())
        val element =
            ValueSite.fromCompiler(f.owner, f.producer.range, ValueRole.LoopResult(iteration.elementResult))
                .value()
                .impactDocument()
                .value()
        assertTrue((element.role as ImpactValueRoleDocument.LoopResult).result is ImpactLoopResultDocument.Element)
        assertNotEquals(element, transfer.source)
        assertNotEquals(f.producer.impactDocument().value(), transfer.source)
    }

    private fun protocol(f: ImpactPathProjectionFixture): ValueLoopProtocol {
        fun call(name: String): ValueInvocation {
            val evidence =
                CompilerGroundedSymbolEvidence.fromBoundary(
                        f.owner.file,
                        250,
                        280,
                        name,
                        "fixture.$name",
                        CompilerSymbolKind.FUNCTION,
                        CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), emptyList(), 0).value(),
                    )
                    .value()
            return ValueInvocation.fromCompiler(
                    f.owner,
                    f.producer.range,
                    RelationEndpoint.resolve(f.lease, f.owner.scope, evidence).value(),
                )
                .value()
        }
        return ValueLoopProtocol.fromCompiler(call("iterator"), call("hasNext"), call("next")).value()
    }

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
