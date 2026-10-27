package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryEntryDetailKind
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSection
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization
import io.github.amichne.kast.query.contract.QueryCount
import io.github.amichne.kast.query.contract.QueryCoverage
import io.github.amichne.kast.query.contract.QueryExecutionResult
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryLimitation
import io.github.amichne.kast.query.contract.QueryOperations
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRetainedResult
import io.github.amichne.kast.query.contract.QueryRows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RetainedWitnessEvidenceDetailTest {
    @Test
    fun `evidence detail skips selected witness rows rather than original path rows`() = runTest {
        val case = WitnessEvidenceDetailCase()
        val fixture = case.fixture
        val captured = case.captured
        val issued = case.issued
        val protocol = case.protocol
        val output = QueryOutputDocument.ImpactWitness(ImpactWitnessSectionDocument.MODELS)
        val ordinary =
            protocol.executePage(
                QueryRunRequest.ReadResult.impactWitness(issued.reference, ImpactWitnessSectionDocument.MODELS),
                fixture.symbols.authority,
                fixture.budget,
            )
        assertInstanceOf(OperationOutcome.Qualified::class.java, ordinary)
        val ordinaryPage = ordinary.detailPayload()
        assertEquals(1, captured.rowCount)
        assertEquals(0, ordinaryPage.presentationWindow?.resultEnd?.value)
        assertEquals(1, ordinaryPage.failures.values.size)
        val entry = QueryEntryDetailSerialization.evidence(ordinaryPage, 0).value()
        val selector =
            QueryEntryDetailSelectorDocument(
                issued.reference,
                QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
                QueryEntryDetailTarget.EVIDENCE,
                QueryResultCursor.Start,
                QueryEntryDetailKind.FAILURE,
                ProtocolText.parse(QueryEntryDetailSerialization.digest(entry.text)).value(),
                ProtocolOffset.parse(0).value(),
            )
        val detailed =
            protocol.executePage(
                QueryRunRequest.ReadResult.detail(issued.reference, selector, output),
                fixture.symbols.authority,
                fixture.budget,
            )
        assertInstanceOf(OperationOutcome.Qualified::class.java, detailed)
        val detailedPage = detailed.detailPayload()
        assertEquals(selector, detailedPage.detailSelection)
        assertEquals(ordinaryPage.failures, detailedPage.failures)
        assertEquals(0, detailedPage.presentationWindow?.start?.value)
        assertEquals(0, detailedPage.presentationWindow?.resultEnd?.value)
    }

    @Test
    fun `explicit row cursor past selected empty section remains rejected`() = runTest {
        val case = WitnessEvidenceDetailCase()
        val fixture = case.fixture
        val protocol = case.protocol
        val issued = case.issued
        val outOfRange =
            protocol.executePage(
                QueryRunRequest.ReadResult.impactWitness(
                    issued.reference,
                    ImpactWitnessSectionDocument.MODELS,
                    QueryResultCursor.parse(1).value(),
                ),
                fixture.symbols.authority,
                fixture.budget,
            )
        assertEquals(
            OperationOutcome.Rejected(
                QueryRunRejection.ExecutionRejected(QueryExecutionRejectionDocument.RESULT_CURSOR_OUT_OF_RANGE)
            ),
            outOfRange,
        )
    }
}

private class WitnessEvidenceDetailCase {
    val fixture = ImpactWitnessPresentationFixture()
    val original =
        QueryResult(
            QueryRows.ValuePaths.fromInvestigation(fixture.ledger).value(),
            listOf(QueryItemFailure.PredicateUnproven(fixture.symbols.selector)),
        )
    val coverage =
        QueryCoverage.Qualified.create(
                QueryCount.parse(1).value(),
                setOf(QueryLimitation.IMPACT_COVERAGE_UNPROVEN),
            )
            .value()
    val captured =
        QueryRetainedResult.capture(
                fixture.symbols.authority,
                QueryExecutionResult.Qualified(original, coverage),
            )
            .value()
    val issued = fixture.store.issueResult(fixture.request, captured) as QueryResultIssuance.Issued
    val protocol =
        CanonicalQueryProtocol(
            QueryOperations { error("Unexpected semantic replay") },
            fixture.symbols.references,
            fixture.store,
        )
}

private fun QueryPublishedPage.detailPayload(): QueryRunResult =
    when (this) {
        is OperationOutcome.Complete -> evidence.payload
        is OperationOutcome.Qualified -> evidence.payload
        is OperationOutcome.Rejected -> error("Expected retained evidence, got $reason")
    }

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
