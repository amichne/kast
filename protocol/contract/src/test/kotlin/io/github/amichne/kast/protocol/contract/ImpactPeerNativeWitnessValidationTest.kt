package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImpactPeerNativeWitnessValidationTest {
    private val fixture = ImpactPeerFixture()
    private val values = fixture.values

    @Test
    fun `ordinary native witness alternatives cannot claim peer basis as source compiler proof`() {
        val native = sourceNativeRead()
        for (witness in foreignWitnesses(native)) assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            result(witness).validateImpactAccounting(),
        )
        assertEquals(Refinement.Refined(Unit), result(native).validateImpactAccounting())
    }

    @Test
    fun `compact source finding cannot hide foreign domain end inside a source destination`() {
        val end =
            ImpactFlowEndObservationDocument(
                fixture.target,
                values.flowDomain,
                values.count(1),
                values.count(100),
                ImpactFlowTerminalDocument.SUPPORTED_DOMAIN_EXHAUSTED,
            )
        val finding = values.finding(0).copy(terminal = ImpactFindingTerminalDocument.SupportedDomainEnd(end))
        assertEquals(
            Refinement.Rejected(ImpactAccountingFailure.WITNESS_VIEW_MISMATCH),
            result(ImpactWitnessDocument.Finding(finding)).validateImpactAccounting(),
        )
    }

    private fun sourceNativeRead() =
        ImpactWitnessDocument.NativeRead(
            values.count(0),
            fixture.source,
            values.flowDomain,
            values.count(1),
            values.count(100),
            ImpactNativeObservationTerminalDocument.SUPPORTED_DOMAIN_EXHAUSTED,
            values.count(0),
            values.count(0),
        )

    private fun foreignWitnesses(native: ImpactWitnessDocument.NativeRead): List<ImpactWitnessDocument> =
        listOf(
            ImpactWitnessDocument.ProducerSiteOnly(fixture.target),
            ImpactWitnessDocument.Producer(
                fixture.source,
                ImpactInvocationReferenceDocument(fixture.source.range, fixture.target.enclosing),
            ),
            native.copy(source = fixture.target),
            native.copy(domain = values.flowDomain.copy(subject = fixture.target.enclosing)),
            ImpactWitnessDocument.CompilerTransfer(
                values.count(0),
                values.count(0),
                ImpactCompilerTransferDocument(
                    fixture.source,
                    fixture.target,
                    ImpactTransferKindDocument.LOCAL_READ,
                ),
            ),
            ImpactWitnessDocument.FlowObligation(
                values.count(0),
                values.count(0),
                fixture.target,
                ImpactFlowUnsupportedDocument.UNMODELED_CALL,
            ),
            ImpactWitnessDocument.ReadRejection(
                ImpactReadRejectionDocument.Native(
                    fixture.target,
                    ImpactRequestedBoundaryDocument.Workspace,
                    ImpactNativeReadRejectionDocument.AUTHORITY_MOVED,
                    values.count(1),
                )
            ),
            ImpactWitnessDocument.ReadRejection(
                ImpactReadRejectionDocument.Contract(
                    fixture.target,
                    ImpactRequestedBoundaryDocument.Workspace,
                    ImpactReadContractRejectionDocument.BASIS_MISMATCH,
                    values.count(1),
                )
            ),
        )

    private fun result(witness: ImpactWitnessDocument): QueryRunResult {
        val section =
            when (witness) {
                is ImpactWitnessDocument.Producer,
                is ImpactWitnessDocument.ProducerSiteOnly -> ImpactWitnessSectionDocument.PRODUCERS
                is ImpactWitnessDocument.ReadRejection -> ImpactWitnessSectionDocument.READ_REJECTIONS
                is ImpactWitnessDocument.Finding -> ImpactWitnessSectionDocument.FINDINGS
                else -> ImpactWitnessSectionDocument.NATIVE_READS
            }
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
                impactAccounting =
                    fixture
                        .accounting()
                        .copy(
                            originalObservationCount = values.count(1),
                            pagePathCount = values.count(0),
                            status =
                                ImpactAccountingStatusDocument.SelectedSubset(
                                    ImpactClosureDocument.Unresolved(
                                        values.bounded(listOf(ImpactRequiredObligationDocument.BOUNDARY))
                                    )
                                ),
                            view =
                                ImpactAccountingViewDocument.Witness(
                                    section,
                                    values.count(0),
                                    values.count(1),
                                    values.count(1),
                                ),
                        ),
            )
    }
}
