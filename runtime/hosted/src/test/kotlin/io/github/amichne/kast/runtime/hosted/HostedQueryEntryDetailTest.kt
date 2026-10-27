package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCompletionCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailKind
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSection
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget
import io.github.amichne.kast.protocol.contract.QueryInvocationStop
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryResultRetention
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStaticModelDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostedQueryEntryDetailTest {
    @Test
    fun `selected detail remains readable when the original question exceeds the byte allowance`() {
        val original = HostedQueryRetainedPresentationTest().page(0, 1, 1)
        val expected = Json {
            encodeDefaults = true
        }
            .encodeToString(ExpectedRow.serializer(), ExpectedRow(name = "row0"))
        val digest =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected.toByteArray(Charsets.UTF_8)))
        val retained = original.evidence.payload.retention as QueryResultRetention.Retained
        val selector =
            QueryEntryDetailSelectorDocument(
                retained.reference,
                QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
                QueryEntryDetailTarget.ROW,
                QueryResultCursor.Start,
                QueryEntryDetailKind.EXACT_SYMBOL,
                text(digest),
                offset(0),
            )
        val semantic =
            original.copy(
                evidence =
                    original.evidence.copy(
                        payload =
                            original.evidence.payload.copy(
                                question =
                                    original.evidence.payload.question.copy(
                                        steps = bounded(List(300) { QueryStepDocument.Distinct })
                                    ),
                                detailSelection = selector,
                            )
                    )
            )
        val response = fitHostedQueryEntryDetail(semantic, ReadLimits.Default, ReturnedByteLimit.parse(4096).value())
        assertInstanceOf(HostedResponse.Canonical::class.java, response)
        assertTrue((response as HostedResponse.Canonical<*, *, *>).document.toByteArray(Charsets.UTF_8).size <= 4096)
        assertInstanceOf(OperationOutcome.Complete::class.java, response.semantic, response.document)
        val detail = ((response.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult).detail!!
        assertEquals(expected, detail.text.value)
        assertEquals(expected.length, detail.total.value)
    }

    @Test
    fun `oversized retained rejection keeps original cause and presents a detail summary`() {
        val original = HostedQueryRetainedPresentationTest().page(0, 1, 1)
        val row =
            (original.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol).copy(
                name = text("λ\"\\".repeat(4000))
            )
        val retained = original.evidence.payload.retention as QueryResultRetention.Retained
        val rejection =
            QueryRunRejection.CompletionUnproven(
                QueryStaticModelDocument.COMPILER_RESOLVED_STATIC_V1,
                QueryCompletionCauseDocument.ItemFailure,
                QueryCompletionCoverageDocument.Complete,
                QueryInvocationStop.COMPLETED,
                QueryCompletionEvidenceDocument.Retained(
                    retained.reference,
                    bounded(listOf(row)),
                    original.evidence.payload.question,
                ),
            )
        val response =
            encodeHostedQueryResponse(
                OperationOutcome.Rejected(rejection),
                maximumBytes = ReturnedByteLimit.parse(8_000).value(),
            )
        assertInstanceOf(HostedResponse.Canonical::class.java, response)
        assertTrue((response as HostedResponse.Canonical<*, *, *>).document.toByteArray(Charsets.UTF_8).size <= 8_000)
        val actual = ((response.semantic as OperationOutcome.Rejected).reason as QueryRunRejection.CompletionUnproven)
        assertEquals(rejection.cause, actual.cause)
        assertEquals(rejection.originalCoverage, actual.originalCoverage)
        val preview = (actual.evidence as QueryCompletionEvidenceDocument.Retained).preview.values
        assertEquals(1, preview.size)
        assertInstanceOf(QueryResultItemDocument.DeferredDetail::class.java, preview.single())
    }

    @Test
    fun `selected chunks reconstruct independent canonical bytes with multibyte escapes`() {
        val case = independentChunkCase("λ\"\\🙂".repeat(1800))
        var cursor = 0
        val pieces = mutableListOf<String>()
        while (cursor < case.expected.length) {
            assertTrue(pieces.size < 100)
            val detail = fragment(case, case.selector.copy(offset = offset(cursor)))
            assertEquals(cursor, detail.selector.offset.value)
            assertEquals(case.expected.length, detail.total.value)
            assertTrue(detail.end.value > cursor)
            pieces += detail.text.value
            cursor = detail.end.value
        }
        assertEquals(case.expected, pieces.joinToString(""))
        assertEquals(case.selector.sha256.value, independentDigest(pieces.joinToString("")))
    }

    @Test
    fun `wrong kind foreign result and changed digest reject without a detail fragment`() {
        val case = independentChunkCase("row0")
        fragment(case, case.selector)
        val invalid =
            listOf(
                case.selector.copy(kind = QueryEntryDetailKind.VALUE_PATH),
                case.selector.copy(sha256 = text("0".repeat(64))),
                case.selector.copy(
                    result = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000099").value()
                ),
            )
        for (selector in invalid) {
            val response =
                fitHostedQueryEntryDetail(
                    case.semantic(selector),
                    ReadLimits.Default,
                    ReturnedByteLimit.parse(4096).value(),
                )
            assertInstanceOf(HostedResponse.Canonical::class.java, response)
            assertInstanceOf(
                OperationOutcome.Rejected::class.java,
                (response as HostedResponse.Canonical<*, *, *>).semantic,
            )
            assertFalse(response.document.contains("\"text\""))
        }
    }

    private data class ChunkCase(
        val original: OperationOutcome.Complete<QueryRunResult>,
        val row: QueryResultItemDocument.ExactSymbol,
        val expected: String,
        val selector: QueryEntryDetailSelectorDocument,
    ) {
        fun semantic(selection: QueryEntryDetailSelectorDocument) =
            original.copy(
                evidence =
                    original.evidence.copy(
                        payload =
                            original.evidence.payload.copy(
                                items =
                                    (BoundedProtocolList.create<QueryResultItemDocument>(listOf(row))
                                            as Refinement.Refined)
                                        .value,
                                detailSelection = selection,
                            )
                    )
            )
    }

    private fun independentChunkCase(name: String): ChunkCase {
        val original = HostedQueryRetainedPresentationTest().page(0, 1, 1)
        val row =
            (original.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol).copy(
                name = text(name)
            )
        val expected = Json { encodeDefaults = true }.encodeToString(ExpectedRow.serializer(), ExpectedRow(name = name))
        val actual = io.github.amichne.kast.protocol.wire.QueryEntryDetailSerialization.row(row).text
        val mismatch = expected.indices.firstOrNull { expected[it] != actual.getOrNull(it) }
        assertEquals(
            expected.length,
            actual.length,
            "Expected prefix ${expected.take(130)} actual prefix ${actual.take(130)}",
        )
        assertEquals(
            null,
            mismatch,
            "First mismatch at $mismatch expected ${expected.take(130)} actual ${actual.take(130)}",
        )
        val retained = original.evidence.payload.retention as QueryResultRetention.Retained
        val selector =
            QueryEntryDetailSelectorDocument(
                retained.reference,
                QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
                QueryEntryDetailTarget.ROW,
                QueryResultCursor.Start,
                QueryEntryDetailKind.EXACT_SYMBOL,
                text(independentDigest(expected)),
                offset(0),
            )
        return ChunkCase(original, row, expected, selector)
    }

    private fun fragment(case: ChunkCase, selector: QueryEntryDetailSelectorDocument): QueryEntryDetailDocument {
        val response =
            fitHostedQueryEntryDetail(
                case.semantic(selector),
                ReadLimits.Default,
                ReturnedByteLimit.parse(4096).value(),
            )
        assertInstanceOf(HostedResponse.Canonical::class.java, response)
        assertTrue((response as HostedResponse.Canonical<*, *, *>).document.toByteArray(Charsets.UTF_8).size <= 4096)
        assertInstanceOf(OperationOutcome.Complete::class.java, response.semantic, response.document)
        return ((response.semantic as OperationOutcome.Complete).evidence.payload as QueryRunResult).detail!!
    }

    private fun independentDigest(value: String) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

    @Test
    fun `unretained oversized entry issues no detail selector`() {
        val original = HostedQueryRetainedPresentationTest().page(0, 1, 1)
        val row =
            (original.evidence.payload.items.values.single() as QueryResultItemDocument.ExactSymbol).copy(
                name = text("λ".repeat(12000))
            )
        val unretained =
            original.evidence.payload.copy(
                items = bounded(listOf(row)),
                retention = QueryResultRetention.NotRequested,
                presentationWindow = null,
            )
        assertEquals(unretained, unretained.deferredRow(0))
    }

    @Serializable private data class ExpectedReference(val token: String = "exact:v3:row0")

    @Serializable
    private data class ExpectedRow(
        val type: String = "exact-symbol",
        val ref: ExpectedReference = ExpectedReference(),
        val kind: String = "function",
        val name: String,
        val location: String? = null,
        val signature: String? = null,
        val connections: List<String> = emptyList(),
        val symbolId: String = "sym:" + "A".repeat(43),
    )

    private fun text(value: String) = ProtocolText.parse(value).value()

    private fun offset(value: Int) = ProtocolOffset.parse(value).value()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

    private fun <T, F> Refinement<T, F>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
