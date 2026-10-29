package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.EmptyImpactConditions
import io.github.amichne.kast.protocol.contract.ImpactAccountingDocument
import io.github.amichne.kast.protocol.contract.ImpactAccountingFailure
import io.github.amichne.kast.protocol.contract.ImpactFindingExecutionStopDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingRepresentationDocument
import io.github.amichne.kast.protocol.contract.ImpactFindingTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactPathClassificationDocument
import io.github.amichne.kast.protocol.contract.ImpactRelationshipDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedEntryKeyEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.admitsEvidence
import io.github.amichne.kast.protocol.contract.validateImpactAccounting
import io.github.amichne.kast.query.contract.QueryOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedCompactPremiseTest {
    @Test
    fun `compact apply rejects an omitted normal callback return premise`() = runTest {
        assertRequiredPremise(
            ImpactReviewedReceiverScopeFixture(ReceiverScopeFamily.APPLY)
                .applyResult()
                .impactDocument()
                .componentValue()
        )
    }

    @Test
    fun `compact group key rejects an omitted representative premise`() = runTest {
        assertRequiredPremise(
            ImpactReviewedGroupFixture().key().impactDocument().componentValue(),
            relationship = ImpactRelationshipDocument.DERIVED,
        )
    }

    @Test
    fun `compact group value rejects an omitted normal membership premise`() = runTest {
        assertRequiredPremise(ImpactReviewedGroupFixture().transformed().impactDocument().componentValue())
    }

    @Test
    fun `compact second component rejects only the omitted prefix premise`() = runTest {
        val transfer = ImpactReviewedEntryProjectionFixture().destructuredSecond().impactDocument().componentValue()
        val omitted =
            transfer.target.copy(
                conditions = BoundedProtocolList.create(transfer.target.conditions.values.dropLast(1)).componentValue()
            )
        assertRequiredPremise(transfer, omitted)
    }

    @Test
    fun `compact preserved key rejects an omitted survival premise`() = runTest {
        assertRequiredPremise(ImpactReviewedEntryProjectionFixture().preserved().impactDocument().componentValue())
    }

    @Test
    fun `compact entry getter rejects an omitted stability premise`() = runTest {
        assertRequiredPremise(ImpactReviewedEntryProjectionFixture().read(false).impactDocument().componentValue())
    }

    @Test
    fun `compact mapped finding rejects an omitted key survival premise`() = runTest {
        assertRequiredPremise(ImpactReviewedMappedProjectionFixture().transfer.impactDocument().componentValue())
    }

    @Test
    fun `compact association finding rejects an omitted unique key premise`() = runTest {
        val component = ImpactReviewedComponentFixture()
        assertRequiredPremise(component.associated(component.construct()).impactDocument().componentValue())
    }

    @Test
    fun `compact sorting finding rejects an omitted normal result premise`() = runTest {
        assertRequiredPremise(ImpactReviewedComponentFixture().sorted(whole = false).impactDocument().componentValue())
    }

    @Test
    fun `compact also finding rejects an omitted normal callback return premise`() = runTest {
        assertRequiredPremise(
            ReviewedScopeProjectionFixture().transfer(receiver = true).impactDocument().componentValue()
        )
    }

    @Test
    fun `compact distinct entry keeps its proof while a conditional claim still needs the premise`() = runTest {
        val transfer = ImpactReviewedDistinctFixture().distinct.impactDocument().componentValue()
        assertTrue(transfer.admitsEvidence())
        val fixture = ImpactFindingFixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val page = fixture.readWitness(protocol, ImpactWitnessSectionDocument.FINDINGS).evidence.payload
        assertEquals(Refinement.Refined(Unit), page.withReviewedTransfer(transfer, true).validateImpactAccounting())
        val relation = transfer.binding.relation as ImpactReviewedValueRelationDocument.AssociationComponent
        val conditional =
            transfer.copy(
                binding =
                    transfer.binding.copy(
                        relation =
                            relation.copy(
                                entry =
                                    relation.entry.copy(
                                        keyEvidence = ImpactReviewedEntryKeyEvidenceDocument.Conditional
                                    )
                            )
                    )
            )
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            page.withReviewedTransfer(conditional, true).validateImpactAccounting(),
        )
    }

    @Test
    fun `compact content finding rejects an exact classification`() = runTest {
        assertContentClassification(forgeComponent = false)
    }

    @Test
    fun `compact mapped finding cannot erase inherited content evidence`() = runTest {
        assertContentClassification(forgeComponent = true)
    }

    private suspend fun assertContentClassification(forgeComponent: Boolean) {
        val transfer = ImpactReviewedEntryProjectionFixture().mappedContentReturn().impactDocument().componentValue()
        assertTrue(transfer.admitsEvidence())
        val fixture = ImpactFindingFixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val page = fixture.readWitness(protocol, ImpactWitnessSectionDocument.FINDINGS).evidence.payload
        assertEquals(
            Refinement.Refined(Unit),
            page.withReviewedTransfer(transfer, false, ImpactRelationshipDocument.DERIVED).validateImpactAccounting(),
        )
        val claimed = if (forgeComponent) transfer.withComponentReturn() else transfer
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            page.withReviewedTransfer(claimed, false).validateImpactAccounting(),
        )
    }

    @Test
    fun `compact filter rejects an omitted predicate acceptance`() = runTest {
        val transfer = ImpactReviewedSelectionFixture().filtered().impactDocument().componentValue()
        val omitted =
            transfer.target.copy(
                conditions = BoundedProtocolList.create(transfer.target.conditions.values.dropLast(1)).componentValue()
            )
        assertRequiredPremise(transfer, omitted)
    }

    @Test
    fun `compact mapNotNull rejects an omitted nonnull premise`() = runTest {
        assertRequiredPremise(ImpactReviewedSelectionFixture().nonNullResult().impactDocument().componentValue())
    }

    private suspend fun assertRequiredPremise(
        transfer: ImpactReviewedValueTransferDocument,
        omittedTarget: ImpactValueSiteReferenceDocument = transfer.target.copy(conditions = EmptyImpactConditions),
        relationship: ImpactRelationshipDocument = ImpactRelationshipDocument.EXACT,
    ) {
        assertTrue(transfer.admitsEvidence())
        val fixture = ImpactFindingFixture()
        val protocol =
            CanonicalQueryProtocol(
                QueryOperations { error("Unexpected semantic replay") },
                fixture.symbols.references,
                fixture.store,
            )
        val page = fixture.readWitness(protocol, ImpactWitnessSectionDocument.FINDINGS).evidence.payload
        assertEquals(
            Refinement.Refined(Unit),
            page.withReviewedTransfer(transfer, false, relationship).validateImpactAccounting(),
        )
        if (relationship == ImpactRelationshipDocument.DERIVED) {
            assertEquals(
                Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
                page.withReviewedTransfer(transfer, false, ImpactRelationshipDocument.EXACT).validateImpactAccounting(),
            )
        }

        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            page
                .withReviewedTransfer(transfer.copy(target = omittedTarget), false, relationship)
                .validateImpactAccounting(),
        )
    }
}

internal fun QueryRunResult.withReviewedTransfer(
    transfer: ImpactReviewedValueTransferDocument,
    omitCondition: Boolean,
    relationship: ImpactRelationshipDocument = ImpactRelationshipDocument.EXACT,
): QueryRunResult {
    val accounting = impactAccounting as ImpactAccountingDocument.Investigated
    val projected: BoundedProtocolList<QueryResultItemDocument> = items.map { item ->
        val row = item as QueryResultItemDocument.ImpactWitness
        val finding = (row.item.witness as ImpactWitnessDocument.Finding).finding
        val updated =
            finding.copy(
                producer = transfer.source,
                destination =
                    if (omitCondition) transfer.target.copy(conditions = EmptyImpactConditions) else transfer.target,
                representation = ImpactFindingRepresentationDocument.NotModeled,
                terminal =
                    ImpactFindingTerminalDocument.ExecutionStop(
                        ImpactFindingExecutionStopDocument.Cycle(QueryDiscoveryCountDocument.parse(0).componentValue())
                    ),
                reviewedRelations = BoundedProtocolList.create(listOf(transfer.binding)).componentValue(),
                classification = ImpactPathClassificationDocument.Established(relationship),
            )
        row.copy(item = row.item.copy(witness = ImpactWitnessDocument.Finding(updated)))
    }
    return copy(
        items = projected,
        impactAccounting = accounting.copy(seeds = accounting.seeds.map { transfer.source }),
    )
}
