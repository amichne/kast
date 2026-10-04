package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactFindingAccountingTest {
    private val fixture = ImpactFindingFixture()

    @Test
    fun `present finding representation cannot claim zero admitted alternatives`() {
        val finding =
            fixture
                .finding()
                .copy(representation = ImpactFindingRepresentationDocument.Present(fixture.bounded(emptyList())))
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            result(listOf(item(2, finding))).validateImpactAccounting(),
        )
        assertEquals(Refinement.Refined(Unit), result().validateImpactAccounting())
    }

    @Test
    fun `finding path ordinal is admitted only with matching original path accounting and witness ordinal`() {
        assertEquals(Refinement.Refined(Unit), result().validateImpactAccounting())
        val mismatched = item(2, fixture.finding(1))
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH),
            result(listOf(mismatched)).validateImpactAccounting(),
        )
        val outside = item(3, fixture.finding(3))
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH),
            result(listOf(outside), first = 3, next = 4).validateImpactAccounting(),
        )
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH),
            result(sectionCount = 4).validateImpactAccounting(),
        )
    }

    @Test
    fun `two original path ordinals cannot present one issued path row identity`() {
        val sameId = listOf(item(1, fixture.finding(1)), item(2, fixture.finding(2)))
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            result(sameId, first = 1).validateImpactAccounting(),
        )
        val distinctIds = listOf(item(1, fixture.finding(1)), item(2, fixture.finding(2, 2)))
        assertEquals(Refinement.Refined(Unit), result(distinctIds, first = 1).validateImpactAccounting())
    }

    @Test
    fun `finding requires mandatory investigated accounting and cannot strengthen completion`() {
        val result = result()
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.MISSING_VALUE_ACCOUNTING),
            result.copy(impactAccounting = ImpactAccountingDocument.NotApplicable).validateImpactAccounting(),
        )
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.COMPLETION_NOT_CONSERVED),
            result.validateImpactCompletion(),
        )
        val accounting = result.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            result
                .copy(impactAccounting = accounting.copy(status = ImpactAccountingStatusDocument.Conserved))
                .validateImpactAccounting(),
        )
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            result
                .copy(
                    impactAccounting =
                        accounting.copy(
                            view =
                                (accounting.view as ImpactAccountingViewDocument.Witness).copy(
                                    section = ImpactWitnessSectionDocument.READ_REJECTIONS
                                )
                        )
                )
                .validateImpactAccounting(),
        )
    }

    private fun item(ordinal: Long, finding: ImpactFindingDocument) =
        QueryResultItemDocument.ImpactWitness(
            ImpactWitnessItemDocument(
                ImpactWitnessSectionDocument.FINDINGS,
                fixture.count(ordinal),
                ImpactWitnessDocument.Finding(finding),
            )
        )

    private fun result(
        items: List<QueryResultItemDocument> = listOf(item(2, fixture.finding())),
        first: Long = 2,
        next: Long = 3,
        sectionCount: Long = 3,
    ): QueryRunResult =
        QueryRunResult(
            question = question(),
            items = fixture.bounded(items),
            failures = fixture.bounded(emptyList()),
            impactAccounting = accounting(first, next, sectionCount),
        )

    private fun question() =
        QueryQuestionDocument(
            QueryFromDocument.Impact(
                QueryImpactSourceDocument(
                    seeds =
                        fixture.bounded(
                            listOf(
                                QueryImpactProducerDocument(
                                    fixture.text("exact:fixture-owner"),
                                    fixture.text("exact:fixture-callable"),
                                    fixture.producer.range,
                                )
                            )
                        ),
                    declarations = fixture.bounded(emptyList()),
                    domain = QueryExpansionScopeDocument.Workspace,
                    flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                    models = fixture.bounded(emptyList()),
                )
            ),
            fixture.bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
        )

    private fun accounting(first: Long, next: Long, sectionCount: Long) =
        ImpactAccountingDocument.Investigated(
            seeds = fixture.bounded(listOf(fixture.producer)),
            requestedDomain = ImpactRequestedBoundaryDocument.Workspace,
            semantics = ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1,
            representationModelReferences = fixture.bounded(emptyList()),
            boundaryModelReferences = fixture.bounded(emptyList()),
            originalReadRejectionCount = fixture.count(0),
            originalObservationCount = fixture.count(4),
            originalPathCount = fixture.count(3),
            pagePathCount = fixture.count(0),
            status =
                ImpactAccountingStatusDocument.SelectedSubset(
                    ImpactClosureDocument.Unresolved(
                        fixture.bounded(listOf(ImpactRequiredObligationDocument.NATIVE_FLOW))
                    )
                ),
            view =
                ImpactAccountingViewDocument.Witness(
                    section = ImpactWitnessSectionDocument.FINDINGS,
                    firstOrdinal = fixture.count(first),
                    nextOrdinal = fixture.count(next),
                    sectionCount = fixture.count(sectionCount),
                ),
            requestedSites = fixture.bounded(emptyList()),
        )
}
