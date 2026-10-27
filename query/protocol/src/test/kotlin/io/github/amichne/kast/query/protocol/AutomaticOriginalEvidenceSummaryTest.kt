package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryEvidenceSummaryState
import io.github.amichne.kast.protocol.contract.QueryPredicateFailureDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.query.contract.QueryItemFailure
import io.github.amichne.kast.query.contract.QueryRelationOmission
import io.github.amichne.kast.query.contract.QueryResult
import io.github.amichne.kast.query.contract.QueryRows
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOmissionEvidence
import io.github.amichne.kast.relation.contract.RelationProviderKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

internal class AutomaticOriginalEvidenceSummaryTest : AutomaticSymbolQueryCase() {
    @Test
    fun `empty rejected preview keeps full original failure and omission summaries`() = runTest {
        val omission = omittedEvidence()
        val script =
            Script(
                listOf(listOf(row)),
                terminal = true,
                resultForPage = { rows, _ ->
                    QueryResult(
                        QueryRows.Symbols.of(rows),
                        List(3) { QueryItemFailure.PredicateUnproven(fixture.selector) },
                        omissions = List(5) { omission },
                    )
                },
            )
        val protocol = CanonicalQueryProtocol(script.operations, fixture.references)
        val rejection = completionRejection(protocol.execute(request, fixture.authority, budget, emptyPreviewPolicy()))
        val retained = retainedEvidence(rejection)
        assertEquals(0, retained.preview.values.size)
        val summary =
            assertInstanceOf(QueryEvidenceSummaryState.Captured::class.java, retained.originalEvidenceSummary).summary
        assertEquals(3, summary.failureCount.value)
        assertEquals(5, summary.omissionCount.value)
        assertEquals(8, summary.evidenceCount.value)
        assertEquals(listOf(QueryPredicateFailureDocument.PREDICATE_UNPROVEN), summary.predicateReasons.values)
        assertEquals(listOf(RelationLimitationDocument.UNSUPPORTED_ITEM), summary.omissionReasons.values)
        assertEquals(1, script.calls)
        val detail =
            protocol.executePage(
                QueryRunRequest.ReadResult.symbols(retained.result, QueryResultCursor.Start, output),
                fixture.authority,
                budget.copy(
                    resources = budget.resources.bounded().copy(resultLimit = ResultLimit.parse(1000).refined())
                ),
            ) as OperationOutcome.Qualified
        assertEquals(3, detail.evidence.payload.failures.values.size)
        assertEquals(5, detail.evidence.payload.omissions.values.size)
        assertEquals(1, script.calls)
        script.assertDrained()
    }

    private fun omittedEvidence(): QueryRelationOmission =
        QueryRelationOmission.create(
                fixture.selector,
                RelationMeaning.Callees,
                RelationOmissionEvidence.unmeasured(
                    RelationProviderKind.INTELLIJ_CALLEES_V2,
                    RelationLimitation.UNSUPPORTED_ITEM,
                ),
            )
            .refined()

    private fun emptyPreviewPolicy(): QueryInvocationPolicy =
        policy(
            rows = 1,
            retainedBytes = 128_000_000,
            inlinePresentation = { page ->
                val retained =
                    ((page as OperationOutcome.Rejected).reason as QueryRunRejection.CompletionUnproven).evidence
                        as QueryCompletionEvidenceDocument.Retained
                if (retained.preview.values.isEmpty()) QueryInlinePresentation.FITS
                else QueryInlinePresentation.RETENTION_REQUIRED
            },
        )
}
