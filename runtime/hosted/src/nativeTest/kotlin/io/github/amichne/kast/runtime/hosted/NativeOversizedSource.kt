package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.PUBLIC_QUERY_OUTPUT_MINIMUM_BYTES
import io.github.amichne.kast.protocol.contract.QueryEntryDetailDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

internal suspend fun assertNativeOversizedSource(
    services: NativePacketQueryServices,
    request: QueryRunRequest.Run,
    expected: String,
) {
    val budget =
        NativePacketQueryServices.budget.copy(
            returnedBytes = QueryByteLimit.parse(PUBLIC_QUERY_OUTPUT_MINIMUM_BYTES).packetQueryRefined()
        )
    val limit = ReturnedByteLimit.parse(PUBLIC_QUERY_OUTPUT_MINIMUM_BYTES).packetQueryRefined()
    val policy =
        QueryInvocationPolicy(
            previewRows = budget.pageResults,
            previewBytesLimit = budget.returnedBytes,
            retainedBytes = budget.checkpointBytes,
            previewBytes = CanonicalQueryCliDocuments::previewBytes,
            deferRow = ::deferredRowSummary,
        )
    suspend fun fitted(action: QueryRunRequest): QueryRunResult {
        val page =
            QueryPresentationExecution.evaluateAndFit(
                evaluate = { owner ->
                    services
                        .protocol(owner)
                        .execute(request = action, lease = services.lease, budget = budget, policy = policy)
                },
                fit = { encodeHostedQueryResponse(it, maximumBytes = limit) },
            )
        check(page is HostedResponse.Canonical<*, *, *>) { page.document }
        assertTrue(page.document.toByteArray().size <= limit.value)
        val outcome = page.semantic
        check(outcome is OperationOutcome.Complete) { page.document }
        return outcome.evidence.payload as QueryRunResult
    }
    val first = fitted(request)
    val entry = (first.items.values.single() as QueryResultItemDocument.DeferredDetail).detail
    var selector = entry.selector
    val reconstructed = StringBuilder()
    do {
        val chunk: QueryEntryDetailDocument =
            checkNotNull(fitted(QueryRunRequest.ReadResult.detail(selector.result, selector, request.output)).detail)
        assertEquals(selector, chunk.selector)
        assertTrue(chunk.end.value > selector.offset.value)
        reconstructed.append(chunk.text.value)
        selector = selector.copy(offset = chunk.end)
    } while (selector.offset.value < chunk.total.value)
    assertEquals(entry.selector.sha256.value, entryDigest(reconstructed.toString()))
    assertTrue(reconstructed.contains(expected))
}
