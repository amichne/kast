package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.query.protocol.QueryPublicationCommit
import io.github.amichne.kast.query.protocol.QueryPublicationPageCharge
import io.github.amichne.kast.query.protocol.QueryPublishedPage
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadRejectedPublication
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class HostedDeferredRejectionPublicationTest {
    @Test
    fun `deferred rejected prefix commits original retained detail and rejects changed selectors or facts`() = runTest {
        val owner = RelationPagingFixture.published()
        val facts =
            (owner.firstPage() as io.github.amichne.kast.relation.contract.RelationReadResult.Qualified).batch.facts
        val case = HostedCompletionRejectionCase(owner, List(20) { facts[it % 3] })
        val prepared = case.run()
        var fitted: QueryPublicationPageCharge.Encoded? = null
        val response =
            encodeHostedQueryResponse(
                prepared,
                maximumBytes = ReturnedByteLimit.parse(8_000).refined(),
                published = { fitted = it },
            )
        assertInstanceOf(HostedResponse.Canonical::class.java, response)
        val charge = checkNotNull(fitted)
        val rejection = (charge.page as OperationOutcome.Rejected).reason as QueryRunRejection.CompletionUnproven
        val retained = rejection.evidence as QueryCompletionEvidenceDocument.Retained
        val summary = retained.preview.values.single() as QueryResultItemDocument.DeferredDetail
        assertInstanceOf(
            HostedReadRejectedPublication.RetainedEvidence::class.java,
            fittedRejectedQueryPublication(prepared, charge),
        )
        assertChangedEvidenceRejected(prepared, charge)
        assertEquals(QueryPublicationCommit.Committed, case.store.commitPublication(case.claim, charge.page, charge))
        case.store.releasePublication(case.claim)
        assertDetailReadable(case, prepared, summary.detail.selector)
        assertEquals(1, case.executions)
    }

    private fun assertChangedEvidenceRejected(
        prepared: QueryPublishedPage,
        charge: QueryPublicationPageCharge.Encoded,
    ) {
        val rejection = (charge.page as OperationOutcome.Rejected).reason as QueryRunRejection.CompletionUnproven
        val retained = rejection.evidence as QueryCompletionEvidenceDocument.Retained
        val summary = retained.preview.values.single() as QueryResultItemDocument.DeferredDetail
        val badSelectors =
            listOf(
                summary.detail.selector.copy(sha256 = ProtocolText.parse("0".repeat(64)).refined()),
                summary.detail.selector.copy(
                    result = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000099").refined()
                ),
            )
        val invalid =
            badSelectors.map { selector ->
                OperationOutcome.Rejected(
                    rejection.copy(
                        evidence =
                            retained.copy(
                                preview =
                                    bounded(listOf(summary.copy(detail = summary.detail.copy(selector = selector))))
                            )
                    )
                )
            } + OperationOutcome.Rejected(rejection.copy(cause = QueryCompletionCauseDocument.OmittedEvidence))
        for (wrong in invalid) {
            val encoded =
                HostedResponse.Canonical.encode(CanonicalOperationWireBindings.queryRun, wrong)
                    as HostedResponse.Canonical<*, *, *>
            assertEquals(
                HostedReadRejectedPublication.Discard,
                fittedRejectedQueryPublication(prepared, encodedPublication(wrong, encoded)),
            )
        }
    }

    private suspend fun assertDetailReadable(
        case: HostedCompletionRejectionCase,
        prepared: OperationOutcome.Rejected<QueryRunRejection>,
        selector: io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument,
    ) {
        val restored = case.read(prepared) as OperationOutcome.Qualified
        assertEquals(
            20,
            (restored.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol)
                .connections
                .values
                .size,
        )
        val detail = case.readDetail(prepared, selector)
        assertInstanceOf(OperationOutcome.Qualified::class.java, detail)
        val physical =
            encodeHostedQueryResponse(detail, maximumBytes = ReturnedByteLimit.parse(8_000).refined())
                as HostedResponse.Canonical<*, *, *>
        assertNotNull(((physical.semantic as OperationOutcome.Qualified).evidence.payload as QueryRunResult).detail)
    }
}

private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).refined()

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
