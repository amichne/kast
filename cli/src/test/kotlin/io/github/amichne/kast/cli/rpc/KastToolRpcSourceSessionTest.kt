package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReferenceFailure
import io.github.amichne.kast.protocol.contract.SourceReferenceRole
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.canonicalReadRejectedDocument
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastToolRpcSourceSessionTest {
    @Test
    fun `exact source read dispatches strict snapshot authority and preserves its failure`() {
        val request =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .examples
                .getValue("readSourceExactSymbol")
                .value
                .toString()
        val expected =
            ToolRpcReply.RejectedDocument(
                Json.parseToJsonElement(
                        canonicalReadRejectedDocument(
                                SourceReadFailureDetail.ReferenceRejected(
                                    SourceReferenceRole.SYMBOL,
                                    SourceReferenceFailure.STALE_AUTHORITY,
                                )
                            )
                            .value
                    )
                    .jsonObject
            )
        val actual = mutableListOf<ToolRpcReply>()
        var invoked = 0
        KastToolRpcQuerySession { admitted ->
                val source = admitted.canonical as PublicToolCanonical.Source
                val anchor = source.request.anchor as SourceReadAnchorDocument.SnapshotSymbol
                assertEquals("exact:v5:AAAAAAAAAAAAAAAAAAAAAA", anchor.selector.value)
                assertEquals(SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE), source.request.region)
                invoked++
                expected
            }
            .run(input(listOf(request)), actual::add)
        assertEquals(listOf(expected), actual)
        assertEquals(1, invoked)
    }

    @Test
    fun `truncated exact symbol cannot dispatch a source read`() {
        val request =
            PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
                .invalidExamples
                .getValue("readSourceTruncatedExactSymbol")
                .value
                .toString()
        val actual = mutableListOf<ToolRpcReply>()
        KastToolRpcQuerySession { error("Exact symbol cannot dispatch a source read") }
            .run(input(listOf(request)), actual::add)
        assertEquals(listOf(ToolRpcReply.Rejected(ToolRpcFailure.INVALID_ARGUMENTS)), actual)
    }

    private fun input(records: List<String>) =
        ByteArrayInputStream(records.joinToString("\n", postfix = "\n").toByteArray())
}
