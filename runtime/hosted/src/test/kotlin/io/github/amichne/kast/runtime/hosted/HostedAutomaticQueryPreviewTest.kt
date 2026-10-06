package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.QueryRunResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedAutomaticQueryPreviewTest {
    @Test
    fun `automatic retained complete query stays complete after full envelope preview fitting`() {
        val original = HostedQueryRetainedPresentationTest().page(0, 10, 120, longTokens = true)
        val invocation =
            io.github.amichne.kast.protocol.contract.QueryInvocationDocument.create(
                    120,
                    io.github.amichne.kast.protocol.contract.QueryPreviewDocument.Prefix(10, 30_000),
                    io.github.amichne.kast.protocol.contract.QueryInvocationStop.COMPLETED,
                )
                .refined()
        val automatic =
            OperationOutcome.Complete(
                original.evidence.copy(payload = original.evidence.payload.copy(invocation = invocation))
            )
        val response =
            encodeHostedQueryResponse(
                automatic,
                maximumResults = ResultLimit.parse(2).refined(),
                maximumBytes = ReturnedByteLimit.parse(8_000).refined(),
                retain = { error("Already retained preview must not issue an output continuation") },
            )
                as HostedResponse.Canonical<*, *, *>
        assertInstanceOf(OperationOutcome.Complete::class.java, response.semantic)
        assertTrue(response.document.toByteArray().size <= 8_000)
        val fitted = (response.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult
        assertTrue(fitted.items.values.size <= 2)
        assertEquals(120, fitted.invocation!!.accumulatedRowCount)
        assertEquals(fitted.items.values.size, fitted.invocation!!.preview.rowCount)
        assertEquals(fitted.items.values.size, fitted.nextCursor!!.value)
        assertEquals(original.evidence.payload.retention, fitted.retention)
        val publicDocument =
            io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments.project(
                OperationOutcome.Complete(original.evidence.copy(payload = fitted))
            ) as io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome.Complete
        val items = Json.parseToJsonElement(publicDocument.document.value).jsonObject.getValue("items")
        assertEquals(
            items.toString().toByteArray(Charsets.UTF_8).size.toLong(),
            fitted.invocation!!.preview.encodedBytes,
        )
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid automatic preview fixture: $failure")
        }
}
