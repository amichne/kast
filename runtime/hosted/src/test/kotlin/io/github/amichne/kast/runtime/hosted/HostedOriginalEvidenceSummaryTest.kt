package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryExactFailureDocument
import io.github.amichne.kast.protocol.contract.QueryItemFailureDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.presentationPrefix
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedOriginalEvidenceSummaryTest {
    @Test
    fun `empty prefix keeps original failure count and reason`() {
        val original = HostedQueryRetainedPresentationTest().page(0, 1, 1)
        val row = original.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol
        val full =
            original.evidence.payload.copy(
                failures =
                    BoundedProtocolList.create(
                            listOf<QueryItemFailureDocument>(
                                QueryItemFailureDocument.ExactReference(
                                    row.ref,
                                    QueryExactFailureDocument.AMBIGUOUS_DECLARATION,
                                ),
                                QueryItemFailureDocument.ExactReference(
                                    row.ref,
                                    QueryExactFailureDocument.COMPILER_IDENTITY_UNAVAILABLE,
                                ),
                            )
                        )
                        .refined()
            )
        val captured =
            full.copy(
                originalEvidenceSummary =
                    io.github.amichne.kast.protocol.contract.QueryEvidenceSummaryState.Captured(
                        io.github.amichne.kast.protocol.contract.QueryOriginalEvidenceSummaryDocument.from(full)
                    )
            )
        val prefix = captured.presentationPrefix(0).refined()
        val projected =
            CanonicalQueryCliDocuments.project(original.copy(evidence = original.evidence.copy(payload = prefix)))
                as ProjectedOperationOutcome.Complete
        val json = Json.parseToJsonElement(projected.document.value).jsonObject
        assertTrue("original_evidence_summary" in json, json.toString())
        val summary = json.getValue("original_evidence_summary").jsonObject
        assertEquals("2", summary.getValue("failure_count").jsonPrimitive.content)
        assertEquals("2", summary.getValue("evidence_count").jsonPrimitive.content)
        assertTrue(summary.getValue("exact_reasons").toString().contains("AMBIGUOUS_DECLARATION"))
        assertTrue(summary.getValue("exact_reasons").toString().contains("COMPILER_IDENTITY_UNAVAILABLE"))
        assertEquals(0, prefix.failures.values.size)
    }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
