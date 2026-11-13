package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.protocol.QueryPublishedPage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression for the callers false negative on `ContainerStore.rebuildRetiredGeneration`.
 *
 * A callback passed as a non-inline argument yields no named caller row by contract; its callback observation is the
 * only evidence of the caller. When that observation is larger than the returned-byte page, the automatic preview
 * defers it behind a detail selector. Every page that keeps the deferral, the first page and a later
 * `--evidence-cursor` read alike, must still name the callback's lexical owner (`read`).
 */
class HostedDeferredCallbackCallerPresentationTest {
    @Test
    fun `deferred callback caller is named on the canonical first page`() = runTest {
        val page = deferredFirstPage()
        assertTrue(
            namesOwner(page.canonical),
            "The canonical first page must name the deferred callback caller $OWNER: ${page.canonical}",
        )
    }

    @Test
    fun `deferred callback caller is named on the CLI first page`() = runTest {
        val page = deferredFirstPage()
        assertTrue(
            namesOwner(page.cli),
            "The CLI first page must name the deferred callback caller $OWNER: ${page.cli}",
        )
    }

    @Test
    fun `evidence cursor read at the deferred callback names its caller`() = runTest {
        val first = deferredFirstPage()
        val cursor = first.page.deferredEvidence.values.single().selector.ordinal.value
        val read =
            QueryRunRequest.ReadResult.occurrences(
                result = (first.page.retention as QueryResultRetention.Retained).reference,
                cursor = first.page.nextCursor ?: io.github.amichne.kast.protocol.contract.QueryResultCursor.Start,
                evidenceCursor = (QueryEvidenceCursor.parse(cursor) as Refinement.Refined).value,
            )
        val semantic =
            first.fixture
                .protocol(null)
                .execute(
                    request = read,
                    lease = first.fixture.symbols.authority,
                    budget = first.fixture.budget,
                    policy = HostedQueryRetainedPresentationTest().retainedPolicy(),
                )
        val response =
            encodeHostedQueryResponse(semantic, maximumBytes = first.budget) {
                error("A retained evidence read must not issue another output continuation")
            }
        val canonical = assertInstanceOf(HostedResponse.Canonical::class.java, response, "The read must fit: $response")
        val complete = assertInstanceOf(OperationOutcome.Complete::class.java, canonical.semantic)
        val fitted = complete.evidence.payload as QueryRunResult
        assertTrue(
            fitted.relationObservations.values.isEmpty(),
            "The read at the same budget defers the callback again",
        )
        assertEquals(1, fitted.deferredEvidence.values.size, "The read presents only the deferred callback selector")
        assertTrue(
            namesOwner(canonical.document),
            "The evidence-cursor read must name the deferred callback caller $OWNER: ${canonical.document}",
        )
    }

    private class FirstPage(
        val fixture: CallbackByteFixture,
        val budget: ReturnedByteLimit,
        val page: QueryRunResult,
        val canonical: String,
        val cli: String,
    )

    /** Fits the complete answer into a page one byte smaller than its callback observation, as the CLI default does. */
    private suspend fun deferredFirstPage(): FirstPage {
        val fixture = CallbackByteFixture(CallbackByteFlow.DIRECT_PROVEN)
        val policy = HostedQueryRetainedPresentationTest().retainedPolicy()
        val (semantic, budget) =
            QueryPresentationExecution.evaluateAndFit(
                evaluate = { owner ->
                    fixture
                        .protocol(owner)
                        .execute(
                            request = fixture.request(),
                            lease = fixture.symbols.authority,
                            budget = fixture.budget,
                            policy = policy,
                        )
                },
                fit = { page -> page to belowCallbackEntry(page) },
            )
        val response = encodeHostedQueryResponse(semantic, maximumBytes = budget)
        val canonical = assertInstanceOf(HostedResponse.Canonical::class.java, response)
        val complete = assertInstanceOf(OperationOutcome.Complete::class.java, canonical.semantic)
        val first = complete.evidence.payload as QueryRunResult
        assertEquals(1, first.deferredEvidence.values.size, "The callback observation must be deferred")
        assertTrue(first.relationObservations.values.isEmpty(), "The deferred page presents no relation observation")
        assertTrue(first.items.values.isEmpty(), "A non-inline callback has no named caller row by contract")
        val typed =
            OperationOutcome.Complete(EvidenceEnvelope(complete.evidence.operation, complete.evidence.basis, first))
        val cli =
            when (val projected = CanonicalQueryCliDocuments.project(typed)) {
                is ProjectedOperationOutcome.Complete -> projected.document.value
                is ProjectedOperationOutcome.Qualified,
                is ProjectedOperationOutcome.Rejected -> error("The complete first page must stay complete: $projected")
            }
        return FirstPage(fixture, budget, first, canonical.document, cli)
    }

    /** Preconditions on the undeferred answer, so the red assertion can only fail on disclosure. */
    private fun belowCallbackEntry(page: QueryPublishedPage): ReturnedByteLimit {
        val complete = assertInstanceOf(OperationOutcome.Complete::class.java, page, "Callers must complete: $page")
        val full = complete.evidence.payload as QueryRunResult
        val callbacks = full.relationObservations.values.single().callbackObservations.values
        assertEquals("read", callbacks.single().lexicalOwner.compilerTarget.name.value)
        val entry =
            when (val detail = QueryEntryDetailSerialization.evidence(full, 0)) {
                is Refinement.Refined -> detail.value.text
                is Refinement.Rejected -> error("The relation observation must have a detail entry: ${detail.failure}")
            }
        assertTrue(namesOwner(entry), "The callback detail entry names its lexical owner")
        val wide = encodeHostedQueryResponse(page) as HostedResponse.Canonical<*, *, *>
        assertTrue(namesOwner(wide.document), "The undeferred page names the callback owner")
        val budget = entry.toByteArray(Charsets.UTF_8).size - 1L
        return when (val limit = ReturnedByteLimit.parse(budget)) {
            is Refinement.Refined -> limit.value
            is Refinement.Rejected -> error("Invalid page budget $budget: ${limit.failure}")
        }
    }

    private companion object {
        /** The callback's lexical owner; the subject `readPrepared` and receiver `nativeBoundary` are other names. */
        const val OWNER = "read"
        val ownerName = Regex("(?<![A-Za-z0-9_$])$OWNER(?![A-Za-z0-9_$])")

        fun namesOwner(text: String): Boolean = ownerName.containsMatchIn(text)
    }
}
