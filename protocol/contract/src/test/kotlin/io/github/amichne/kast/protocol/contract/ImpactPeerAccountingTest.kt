package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactPeerAccountingTest {
    private val fixture = ImpactPeerFixture()
    private val values = fixture.values
    private val rejected = Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH)

    @Test
    fun `only final reviewed peer edge can retain foreign basis and closure remains unresolved`() {
        assertEquals(Refinement.Refined(Unit), fixture.result().validateImpactAccounting())
        assertEquals(
            rejected,
            fixture.result(fixture.path.copy(producer = values.destination)).validateImpactAccounting(),
        )
        val badConnection =
            fixture.connection.copy(rule = fixture.rule.copy(target = fixture.rule.target.copy(site = fixture.source)))
        val path =
            fixture.path.copy(steps = values.bounded(listOf(ImpactPathStepDocument.ModeledBoundary(badConnection))))
        assertEquals(rejected, fixture.result(path).validateImpactAccounting())
        val accounting = fixture.accounting().copy(status = ImpactAccountingStatusDocument.Conserved)
        assertEquals(rejected, fixture.result().copy(impactAccounting = accounting).validateImpactCompletion())
    }

    @Test
    fun `foreign compiler prefix or foreign earlier provenance cannot borrow final peer receipt`() {
        val foreign =
            ImpactPathStepDocument.Compiler(
                ImpactCompilerTransferDocument(fixture.source, fixture.target, ImpactTransferKindDocument.LOCAL_READ)
            )
        val steps = values.bounded(listOf(foreign) + fixture.path.steps.values)
        assertEquals(rejected, fixture.result(fixture.path.copy(steps = steps)).validateImpactAccounting())
        val history =
            values.bounded(
                listOf(
                    ImpactRepresentationHistoryDocument.Unmodeled(fixture.source, fixture.target),
                    ImpactRepresentationHistoryDocument.BoundaryModel(
                        values.reference,
                        fixture.rule.source,
                        fixture.rule.target,
                    ),
                )
            )
        val representation =
            ImpactRepresentationEvidenceDocument.Present(
                fixture.target,
                values.bounded(
                    listOf(
                        ImpactRepresentationBranchDocument(
                            ImpactRepresentationCurrentDocument.Known(values.state),
                            history,
                        )
                    )
                ),
            )
        assertEquals(
            rejected,
            fixture.result(fixture.path.copy(representation = representation)).validateImpactAccounting(),
        )
    }

    @Test
    fun `unused peer model witness retains exact original model and independent completed target proof`() {
        val result = witnessResult(ImpactWitnessSectionDocument.MODELS, fixture.witness)
        assertEquals(Refinement.Refined(Unit), result.validateImpactAccounting())
        val wrongRule = fixture.witness.copy(rule = fixture.rule.copy(id = values.id("undeclared")))
        assertEquals(rejected, witnessResult(ImpactWitnessSectionDocument.MODELS, wrongRule).validateImpactAccounting())
        val terminalRule =
            fixture.witness.copy(
                rule =
                    ImpactBoundaryRuleDocument.Terminal(
                        values.reference.rule,
                        fixture.rule.source,
                        ImpactBoundaryTerminalDocument.REVIEWED_RETENTION,
                    )
            )
        assertEquals(
            rejected,
            witnessResult(ImpactWitnessSectionDocument.MODELS, terminalRule).validateImpactAccounting(),
        )
        val wrongBasis =
            fixture.witness.copy(
                admission =
                    fixture.admission.copy(
                        acquisition =
                            fixture.admission.acquisition.copy(completedBasis = fixture.source.enclosing.basis)
                    )
            )
        assertEquals(
            rejected,
            witnessResult(ImpactWitnessSectionDocument.MODELS, wrongBasis).validateImpactAccounting(),
        )
    }

    @Test
    fun `compact peer finding requires target destination and final ordered boundary provenance`() {
        val finding =
            values
                .finding(0)
                .copy(
                    destination = fixture.target,
                    representation = ImpactFindingRepresentationDocument.NotModeled,
                    terminal = fixture.findingTerminal,
                )
        assertEquals(
            Refinement.Refined(Unit),
            witnessResult(ImpactWitnessSectionDocument.FINDINGS, ImpactWitnessDocument.Finding(finding))
                .validateImpactAccounting(),
        )
        val wrongDestination = finding.copy(destination = fixture.source)
        assertEquals(
            rejected,
            witnessResult(ImpactWitnessSectionDocument.FINDINGS, ImpactWitnessDocument.Finding(wrongDestination))
                .validateImpactAccounting(),
        )
    }

    @Test
    fun `foreign model or terminal cannot omit completed peer receipt by using ordinary alternatives`() {
        val ordinaryModel = ImpactWitnessDocument.BoundaryModel(values.reference, fixture.rule)
        assertEquals(
            rejected,
            witnessResult(ImpactWitnessSectionDocument.MODELS, ordinaryModel).validateImpactAccounting(),
        )
        val ordinaryTerminal =
            fixture.path.copy(
                terminal =
                    ImpactPathTerminalDocument.UnresolvedFlow(
                        fixture.target,
                        ImpactFlowUnsupportedDocument.UNMODELED_CALL,
                    )
            )
        assertEquals(rejected, fixture.result(ordinaryTerminal).validateImpactAccounting())
        val sourceOnly =
            fixture.path.copy(
                steps = values.bounded(emptyList()),
                terminal =
                    ImpactPathTerminalDocument.UnresolvedFlow(
                        fixture.target,
                        ImpactFlowUnsupportedDocument.UNMODELED_CALL,
                    ),
            )
        assertEquals(rejected, fixture.result(sourceOnly).validateImpactAccounting())
    }

    private fun witnessResult(section: ImpactWitnessSectionDocument, witness: ImpactWitnessDocument): QueryRunResult {
        val original = fixture.accounting()
        val accounting =
            original.copy(
                pagePathCount = values.count(0),
                status =
                    ImpactAccountingStatusDocument.SelectedSubset(
                        ImpactClosureDocument.Unresolved(
                            values.bounded(listOf(ImpactRequiredObligationDocument.BOUNDARY))
                        )
                    ),
                view = ImpactAccountingViewDocument.Witness(section, values.count(0), values.count(1), values.count(1)),
            )
        return fixture
            .result()
            .copy(
                items =
                    values.bounded(
                        listOf(
                            QueryResultItemDocument.ImpactWitness(
                                ImpactWitnessItemDocument(section, values.count(0), witness)
                            )
                        )
                    ),
                impactAccounting = accounting,
            )
    }
}
