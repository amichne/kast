package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactInterpolationScalarKindDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationCurrentDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationHistoryDocument
import io.github.amichne.kast.protocol.contract.ImpactRepresentationUnknownDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.relation.contract.ValueInterpolationEntryIndex
import io.github.amichne.kast.relation.contract.ValueInterpolationScalarKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactStringInterpolationProjectionTest {
    @Test
    fun `compiler derivation keeps complete string evidence while representation becomes unknown`() {
        val f = ImpactPathProjectionFixture()
        val references = CanonicalQueryReferences()
        val target = f.site(20, 50, ValueRole.ExpressionResult)
        val evidence =
            ValueTransferEvidence.StringInterpolation.fromCompiler(
                    range(20, 50),
                    range(25, 45),
                    ValueInterpolationEntryIndex.parse(2).value(),
                    ValueInterpolationScalarKind.STRING,
                )
                .value()
        val edge = ValueTransfer.fromCompiler(f.producer, target, ValueTransferKind.DERIVATION, evidence).value()
        val representation = f.origin.transfer(edge).value().impactDocument(references).value()
        val branch = representation.branches.values.single()
        assertEquals(
            ImpactRepresentationCurrentDocument.Unknown(ImpactRepresentationUnknownDocument.UNMODELED_TRANSFORMATION),
            branch.current,
        )
        assertEquals(2, branch.history.values.size)
        assertTrue(branch.history.values.first() is ImpactRepresentationHistoryDocument.Origin)
        val transfer = (branch.history.values.last() as ImpactRepresentationHistoryDocument.CompilerTransfer).transfer
        assertEquals(ImpactTransferKindDocument.DERIVATION, transfer.kind)
        val proof = transfer.evidence as ImpactTransferEvidenceDocument.StringInterpolation
        assertEquals(20, proof.templateRange.start.value)
        assertEquals(50, proof.templateRange.end.value)
        assertEquals(25, proof.entryRange.start.value)
        assertEquals(45, proof.entryRange.end.value)
        assertEquals(2, proof.entryIndex.value)
        assertEquals(ImpactInterpolationScalarKindDocument.STRING, proof.scalarKind)
        assertTrue(transfer.admitsEvidence())
    }
}

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
