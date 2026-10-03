package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactSiteAccountingValidationTest {
    private val f = ImpactFindingFixture()
    private val target = f.destination
    private val admission = ImpactSiteAdmissionDocument(f.flowDomain.budget, f.count(1))

    @Test
    fun `unmatched requested site requires original relationship obligation and cannot claim completion`() {
        val valid = result(ImpactSiteOutcomeDocument.RelationshipUnproven)
        assertEquals(Refinement.Refined(Unit), valid.validateImpactAccounting())
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.COMPLETION_NOT_CONSERVED),
            valid.validateImpactCompletion(),
        )
        val accounting = valid.impactAccounting as ImpactAccountingDocument.Investigated
        val erased =
            valid.copy(
                impactAccounting =
                    accounting.copy(
                        status = ImpactAccountingStatusDocument.SelectedSubset(ImpactClosureDocument.Discharged)
                    )
            )
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            erased.validateImpactAccounting(),
        )
    }

    @Test
    fun `site identity ordinal and whole requested universe cannot be substituted in retained witness`() {
        val valid = result(ImpactSiteOutcomeDocument.RelationshipUnproven)
        val item = (valid.items.values.single() as QueryResultItemDocument.ImpactWitness).item
        val witness = item.witness as ImpactWitnessDocument.SiteAccounting
        val changed = item.copy(witness = witness.copy(accounting = witness.accounting.copy(site = f.producer)))
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_ORDINAL_MISMATCH),
            valid
                .copy(items = f.bounded(listOf(QueryResultItemDocument.ImpactWitness(changed))))
                .validateImpactAccounting(),
        )
        val accounting = valid.impactAccounting as ImpactAccountingDocument.Investigated
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            valid
                .copy(impactAccounting = accounting.copy(requestedSites = f.bounded(emptyList())))
                .validateImpactAccounting(),
        )
    }

    @Test
    fun `reached links require nonempty unique original ordinals and recorded admission work within grant`() {
        val first = ImpactFindingEvidenceReferenceDocument(f.count(0), f.row(1))
        val good = result(ImpactSiteOutcomeDocument.Reached(f.bounded(listOf(first)), f.bounded(emptyList())))
        assertEquals(Refinement.Refined(Unit), good.validateImpactAccounting())
        for (links in listOf(emptyList(), listOf(first, first), listOf(first.copy(pathOrdinal = f.count(2))))) {
            assertEquals(
                Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
                result(ImpactSiteOutcomeDocument.Reached(f.bounded(links), f.bounded(emptyList())))
                    .validateImpactAccounting(),
            )
        }
        val item = (good.items.values.single() as QueryResultItemDocument.ImpactWitness).item
        val witness = item.witness as ImpactWitnessDocument.SiteAccounting
        val excessive =
            witness.copy(
                accounting = witness.accounting.copy(admission = admission.copy(examinedWorkUnits = f.count(11)))
            )
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            good
                .copy(items = f.bounded(listOf(QueryResultItemDocument.ImpactWitness(item.copy(witness = excessive)))))
                .validateImpactAccounting(),
        )
    }

    @Test
    fun `exclusions retain exact explicit domain and distinct original row identities`() {
        val boundary =
            ImpactRequestedBoundaryDocument.SourceDomain(
                f.domain.copy(
                    directory = QueryDirectoryScopeDocument(f.text("selected"), QueryContainmentDocument.DIRECT)
                )
            )
        val exit =
            ImpactSiteExclusionDocument(
                ImpactFindingEvidenceReferenceDocument(f.count(0), f.row(1)),
                boundary,
                ImpactScopeExclusionDocument.OUTSIDE_DIRECTORY,
            )
        val original = result(ImpactSiteOutcomeDocument.Excluded(f.bounded(listOf(exit))))
        val accounting = original.impactAccounting as ImpactAccountingDocument.Investigated
        val valid = original.copy(impactAccounting = accounting.copy(requestedDomain = boundary))
        assertEquals(Refinement.Refined(Unit), valid.validateImpactAccounting())
        for (exits in
            listOf(
                listOf(exit.copy(domain = ImpactRequestedBoundaryDocument.Workspace)),
                listOf(exit, exit.copy(path = exit.path.copy(pathOrdinal = f.count(1)))),
            )) {
            val invalid =
                result(ImpactSiteOutcomeDocument.Excluded(f.bounded(exits)))
                    .copy(impactAccounting = accounting.copy(requestedDomain = boundary))
            assertEquals(
                Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
                invalid.validateImpactAccounting(),
            )
        }
    }

    private fun result(outcome: ImpactSiteOutcomeDocument): QueryRunResult =
        QueryRunResult(
            question = question(),
            items =
                f.bounded(
                    listOf(
                        QueryResultItemDocument.ImpactWitness(
                            ImpactWitnessItemDocument(
                                ImpactWitnessSectionDocument.SITE_ACCOUNTING,
                                f.count(0),
                                ImpactWitnessDocument.SiteAccounting(
                                    ImpactSiteAccountingDocument(f.count(0), target, outcome, admission)
                                ),
                            )
                        )
                    )
                ),
            failures = f.bounded(emptyList()),
            impactAccounting =
                ImpactAccountingDocument.Investigated(
                    f.bounded(listOf(f.producer)),
                    ImpactRequestedBoundaryDocument.Workspace,
                    ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1,
                    f.bounded(emptyList()),
                    f.bounded(emptyList()),
                    f.count(0),
                    f.count(4),
                    f.count(2),
                    f.count(0),
                    ImpactAccountingStatusDocument.SelectedSubset(
                        ImpactClosureDocument.Unresolved(
                            f.bounded(listOf(ImpactRequiredObligationDocument.REQUESTED_SITE_RELATIONSHIP))
                        )
                    ),
                    ImpactAccountingViewDocument.Witness(
                        ImpactWitnessSectionDocument.SITE_ACCOUNTING,
                        f.count(0),
                        f.count(1),
                        f.count(1),
                    ),
                    f.bounded(listOf(target)),
                ),
        )

    private fun question() =
        QueryQuestionDocument(
            QueryFromDocument.Impact(
                QueryImpactSourceDocument(
                    f.bounded(
                        listOf(
                            QueryImpactProducerDocument(
                                f.text("exact:owner"),
                                f.text("exact:callable"),
                                f.producer.range,
                            )
                        )
                    ),
                    f.bounded(emptyList()),
                    QueryExpansionScopeDocument.Workspace,
                    QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                    f.bounded(emptyList()),
                    f.bounded(listOf(target)),
                )
            ),
            f.bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
        )
}
