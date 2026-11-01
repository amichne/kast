package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryEntryDetailKind
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSection
import io.github.amichne.kast.protocol.contract.QueryEntryDetailSelectorDocument
import io.github.amichne.kast.protocol.contract.QueryEntryDetailTarget
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicDetailSelectorContractTest {
    private val result = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001").refined()
    private val selector =
        QueryEntryDetailSelectorDocument(
            result,
            QueryEntryDetailSection.CANONICAL_ENTRY_JSON,
            QueryEntryDetailTarget.ROW,
            QueryResultCursor.parse(2).refined(),
            QueryEntryDetailKind.EXACT_SYMBOL,
            ProtocolText.parse("a".repeat(64)).refined(),
            ProtocolOffset.parse(7).refined(),
        )

    @Test
    fun `detail read preserves independently expected canonical selector output and allowance`() {
        val output = PublicToolSymbolsOutput(BoundedProtocolList.create(listOf(PublicToolFields.NAME)).refined())
        val input =
            PublicToolReadResultAction(
                result,
                output = output,
                detail = selector,
                executionBudget = PublicToolExecutionBudget(maxReturnedBytes = 4096),
            )
        val expected =
            QueryRunRequest.ReadResult.detail(
                result,
                selector,
                QueryOutputDocument.Symbols(
                    BoundedProtocolList.create(listOf(QuerySymbolFieldDocument.NAME)).refined()
                ),
                ExecutionBudgetDocument(
                    maxReturnedBytes = io.github.amichne.kast.kernel.ReturnedByteLimit.parse(4096).refined()
                ),
            )
        for (catalog in PublicToolCatalog.entries) {
            val admitted = admit(input, catalog) as Refinement.Refined
            assertEquals(PublicToolCanonical.Query(expected), admitted.value.canonical)
            assertEquals(catalog, admitted.value.catalog)
        }
    }

    @Test
    fun `detail rejects a foreign result incompatible kind and conflicting cursors before native work`() {
        val foreign = QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000002").refined()
        val inputs =
            listOf(
                PublicToolReadResultAction(result, detail = selector.copy(result = foreign)) to
                    PublicToolInputFailure.Parameter(
                        PublicToolParameter.DETAIL_RESULT,
                        PublicToolRule.SAME_RETAINED_RESULT,
                    ),
                PublicToolReadResultAction(result, detail = selector.copy(kind = QueryEntryDetailKind.OMISSION)) to
                    PublicToolInputFailure.Parameter(
                        PublicToolParameter.DETAIL_KIND,
                        PublicToolRule.DETAIL_TARGET_KIND,
                    ),
                PublicToolReadResultAction(result, cursor = QueryResultCursor.Start, detail = selector) to
                    PublicToolInputFailure.Parameter(
                        PublicToolParameter.DETAIL_CURSOR,
                        PublicToolRule.DETAIL_CURSOR_CONTROL,
                    ),
                PublicToolReadResultAction(result, evidenceCursor = QueryEvidenceCursor.Start, detail = selector) to
                    PublicToolInputFailure.Parameter(
                        PublicToolParameter.DETAIL_CURSOR,
                        PublicToolRule.DETAIL_CURSOR_CONTROL,
                    ),
            )
        for (catalog in PublicToolCatalog.entries) for ((input, failure) in inputs) assertEquals(
            Refinement.Rejected(failure),
            admit(input, catalog),
        )
    }

    @Test
    fun `detail schema rejects unknown kind wrong token family and malformed digest`() {
        val raw =
            Json.encodeToJsonElement(
                    PublicToolQuerySymbols.serializer(),
                    PublicToolQuerySymbols(PublicToolReadResultAction(result, detail = selector)),
                )
                .toString()
        val invalid =
            listOf(
                raw.replace("EXACT_SYMBOL", "UNKNOWN_KIND"),
                raw.replace(result.value, "exact:v5:NON_ISSUED"),
                raw.replace("a".repeat(64), "G".repeat(64)),
            )
        for (catalog in PublicToolCatalog.entries) for (candidate in invalid) assertEquals(
            Refinement.Rejected(PublicToolInputFailure.SchemaRejected),
            PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, Json.parseToJsonElement(candidate), catalog),
        )
    }

    @Test
    fun `advanced retained outputs need the complete catalog`() {
        val input =
            PublicToolReadResultAction(
                result,
                output = PublicToolImpactWitnessOutput(ImpactWitnessSectionDocument.FINDINGS),
            )
        assertTrue(admit(input, PublicToolCatalog.COMMON) is Refinement.Rejected)
        assertTrue(admit(input, PublicToolCatalog.COMPLETE) is Refinement.Refined)
    }

    private fun admit(input: PublicToolReadResultAction, catalog: PublicToolCatalog) =
        PublicToolContract.admit(
            PublicToolIdentity.QUERY_SYMBOLS,
            Json.encodeToJsonElement(PublicToolQuerySymbols.serializer(), PublicToolQuerySymbols(input)),
            catalog,
        )

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value
}
