package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.EmptyImpactConditions
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.ImpactCastKindDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionOriginDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.admitsEvidence
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedInheritedConditionTest {
    @Test
    fun `compact receiver input cannot discard a producer cast premise`() = runTest {
        val transfer = qualifiedReceiverInput()
        val omitted = transfer.copy(target = transfer.target.copy(conditions = EmptyImpactConditions))
        assertTrue(transfer.admitsEvidence())
        assertFalse(omitted.admitsEvidence(), "Full transfer already preserves the producer premise")
        val fixture = ImpactFindingFixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val page = fixture.readWitness(protocol, ImpactWitnessSectionDocument.FINDINGS).evidence.payload
        assertEquals(Refinement.Refined(Unit), page.withReviewedTransfer(transfer, false).validateImpactAccounting())
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            page.withReviewedTransfer(omitted, false).validateImpactAccounting(),
        )
    }
}

private fun qualifiedReceiverInput(): ImpactReviewedValueTransferDocument {
    val input =
        ImpactReviewedReceiverScopeFixture(ReceiverScopeFamily.RECEIVER_RUN).input().impactDocument().componentValue()
    val source = input.source
    val premise =
        ImpactValueConditionDocument.CastSuccess(
            ImpactValueConditionOriginDocument(
                ImpactExecutionReferenceDocument(source.enclosing, source.executionOwner),
                source.range,
                ImpactValueRoleDocument.ExpressionResult,
                source.valuePath,
                source.callStack,
            ),
            source.range,
            ImpactCastKindDocument.UNSAFE,
            ProtocolText.parse("kotlin/Any").componentValue(),
            ProtocolText.parse("kotlin/String").componentValue(),
        )
    val conditions = BoundedProtocolList.create<ImpactValueConditionDocument>(listOf(premise)).componentValue()
    return input.copy(
        source = source.copy(conditions = conditions),
        target = input.target.copy(conditions = conditions),
    )
}
