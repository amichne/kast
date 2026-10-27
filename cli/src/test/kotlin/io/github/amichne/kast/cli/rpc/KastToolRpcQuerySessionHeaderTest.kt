package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.LiveReadOutputSchemaTest
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecovery
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecoveryResume
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecoveryUnavailable
import io.github.amichne.kast.protocol.contract.QuerySemanticHeaderDocumentRecoveryUnavailableReason
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.QueryPresentationHeaderDocument
import java.io.ByteArrayInputStream
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class KastToolRpcQuerySessionHeaderTest {
    private val cases = Json.decodeFromString<Map<String, ToolRpcReply>>(QueryDeliveryFixtures.serialized())
    private val result = parsed(QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001"))
    private val otherResult = parsed(QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000003"))
    private val output =
        parsed(QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000002"))

    @Test
    fun `bounded headers keep advertised retained reads in the same session and preserve outcomes`() {
        val headers = QueryDeliveryFixtures.presentationHeaders()
        assertEquals(
            listOf("complete", "qualified", "rejected_document"),
            headers.map { reply ->
                Json.encodeToJsonElement<ToolRpcReply>(reply).jsonObject.getValue("type").jsonPrimitive.content
            },
        )
        assertAll(
            headers.mapIndexed { index, header ->
                Executable {
                    val document =
                        Json.encodeToJsonElement<ToolRpcReply>(header).jsonObject.getValue("document").jsonObject
                    LiveReadOutputSchemaTest().assertAdmits(CanonicalOperation.QUERY_RUN, document)
                    assertEquals("QUERY_PRESENTATION_HEADER", document.getValue("type").jsonPrimitive.content)
                    val semantic = document.getValue("semantic").jsonObject
                    assertEquals(
                        listOf("complete", "qualified", "rejected_document")[index],
                        semantic.getValue("original_outcome").jsonPrimitive.content,
                    )
                    val recovery = semantic.getValue("recovery").jsonObject
                    assertEquals("READ_RESULT", recovery.getValue("type").jsonPrimitive.content)
                    assertEquals(result.value, recovery.getValue("result").jsonPrimitive.content)
                    val advertised = document.getValue("nextQuery").jsonObject
                    val successor = advertised.getValue("request").jsonObject
                    assertEquals("READ_RESULT", successor.getValue("type").jsonPrimitive.content)
                    assertEquals(result.value, successor.getValue("result").jsonPrimitive.content)
                    assertEquals("0", successor.getValue("cursor").jsonPrimitive.content)
                    val final = cases.getValue(if (index == 2) "proofTail" else "tail")
                    val expected = listOf(header, final)
                    val invoked = mutableListOf<QueryRunRequest>()
                    assertEquals(expected, run(listOf(runRequest(), advertised.toString()), expected, invoked))
                    assertEquals(1, invoked.count { it is QueryRunRequest.Run })
                    val read = invoked[1] as QueryRunRequest.ReadResult
                    assertEquals(result, read.result)
                    assertEquals(parsed(QueryResultCursor.parse(0)), read.cursor)
                }
            }
        )
    }

    @Test
    fun `bounded headers reject unissued result and continuation before dispatch`() {
        for (header in QueryDeliveryFixtures.presentationHeaders()) {
            for ((successor, detail) in
                listOf(
                    readRequest(reference = otherResult) to
                        QuerySessionRejectionDetail.ResultNotIssued(result, otherResult),
                    resumeRequest() to QuerySessionRejectionDetail.ContinuationNotIssued(null, output),
                )) {
                val invoked = mutableListOf<QueryRunRequest>()
                assertEquals(
                    listOf(header, ToolRpcReply.Rejected.querySession(detail)),
                    run(listOf(runRequest(), successor), listOf(header), invoked),
                )
                assertEquals(1, invoked.size)
            }
        }
    }

    @Test
    fun `bounded header authority ends when retained read reports stale or expired result`() {
        for (header in QueryDeliveryFixtures.presentationHeaders()) {
            for (failure in listOf(cases.getValue("stale"), cases.getValue("expiredResult"))) {
                val expected = listOf(header, failure)
                val invoked = mutableListOf<QueryRunRequest>()
                assertEquals(expected, run(listOf(runRequest(), readRequest(), readRequest()), expected, invoked))
                assertEquals(2, invoked.size)
            }
        }
    }

    @Test
    fun `headers without valid recovery authority close without dispatching a successor`() {
        for (header in QueryDeliveryFixtures.presentationHeaders()) {
            val encoded = Json.encodeToString<ToolRpcReply>(header)
            val document = Json.encodeToJsonElement<ToolRpcReply>(header).jsonObject.getValue("document").jsonObject
            val decoded = Json.decodeFromJsonElement(QueryPresentationHeaderDocument.serializer(), document)
            val recovery = Json.encodeToString<QuerySemanticHeaderDocumentRecovery>(decoded.semantic.recovery)
            // Corrupt only serialized recovery; nextQuery and semantic.authority still carry the original identity.
            val invalid =
                listOf(
                    encoded.replace("\"recovery\":", "\"missingRecovery\":"),
                    encoded.replace(recovery, recovery.replace("\"result\":", "\"missingResult\":")),
                    encoded.replace(recovery, recovery.replace(result.value, "invalid")),
                    encoded.replace(recovery, recovery.replace("READ_RESULT", "UNKNOWN")),
                )
            for (broken in invalid) {
                assertTrue(broken != encoded, "Each negative control must alter the canonical fixture")
                val reply = Json.decodeFromString<ToolRpcReply>(broken)
                val invoked = mutableListOf<QueryRunRequest>()
                assertEquals(listOf(reply), run(listOf(runRequest(), readRequest()), listOf(reply), invoked))
                assertEquals(1, invoked.size)
            }
        }
    }

    @Test
    fun `unavailable header recovery does not borrow a retained reference from other fields`() {
        for (header in QueryDeliveryFixtures.presentationHeaders()) {
            val reply =
                header.withRecovery(
                    QuerySemanticHeaderDocumentRecoveryUnavailable(
                        QuerySemanticHeaderDocumentRecoveryUnavailableReason.NO_RETAINED_AUTHORITY
                    )
                )
            val invoked = mutableListOf<QueryRunRequest>()
            assertEquals(listOf(reply), run(listOf(runRequest(), readRequest()), listOf(reply), invoked))
            assertEquals(1, invoked.size)
        }
    }

    @Test
    fun `header resume recovery refines only output continuations and issues no retained result`() {
        val original = QueryDeliveryFixtures.presentationHeaders()[1]
        val header =
            original.withRecovery(QuerySemanticHeaderDocumentRecoveryResume(parsed(ProtocolText.parse(output.value))))
        val expected = listOf(header, cases.getValue("outputFinal"))
        val invoked = mutableListOf<QueryRunRequest>()
        assertEquals(expected, run(listOf(runRequest(), resumeRequest()), expected, invoked))
        assertEquals(output, (invoked[1] as QueryRunRequest.Resume).continuation)
        val rejected = mutableListOf<QueryRunRequest>()
        assertEquals(
            listOf(
                header,
                ToolRpcReply.Rejected.querySession(QuerySessionRejectionDetail.ResultNotIssued(null, result)),
            ),
            run(listOf(runRequest(), readRequest()), listOf(header), rejected),
        )
        val invalid =
            original.withRecovery(QuerySemanticHeaderDocumentRecoveryResume(parsed(ProtocolText.parse("invalid"))))
        val closed = mutableListOf<QueryRunRequest>()
        assertEquals(listOf(invalid), run(listOf(runRequest(), resumeRequest()), listOf(invalid), closed))
    }

    private fun ToolRpcReply.withRecovery(recovery: QuerySemanticHeaderDocumentRecovery): ToolRpcReply {
        val document = Json.encodeToJsonElement<ToolRpcReply>(this).jsonObject.getValue("document").jsonObject
        val decoded = Json.decodeFromJsonElement(QueryPresentationHeaderDocument.serializer(), document)
        val changed = decoded.copy(semantic = decoded.semantic.copy(recovery = recovery))
        val replacement = Json { encodeDefaults = true }.encodeToJsonElement(changed).jsonObject
        LiveReadOutputSchemaTest().assertAdmits(CanonicalOperation.QUERY_RUN, replacement)
        return when (this) {
            is ToolRpcReply.Complete -> ToolRpcReply.Complete(replacement)
            is ToolRpcReply.Qualified -> ToolRpcReply.Qualified(replacement)
            is ToolRpcReply.RejectedDocument -> ToolRpcReply.RejectedDocument(replacement)
            else -> error("Expected semantic fixture")
        }
    }

    private fun run(
        records: List<String>,
        expected: List<ToolRpcReply>,
        invoked: MutableList<QueryRunRequest>,
    ): List<ToolRpcReply> {
        val actual = mutableListOf<ToolRpcReply>()
        KastToolRpcQuerySession(scripted(expected, invoked)).run(input(records), actual::add)
        assertTrue(invoked.size <= expected.size)
        assertEquals(expected.size, invoked.size, "Every scripted provider response must be consumed")
        return actual
    }

    private fun scripted(
        expected: List<ToolRpcReply>,
        invoked: MutableList<QueryRunRequest>,
    ): (AdmittedPublicTool) -> ToolRpcReply = { admitted ->
        assertEquals(PublicToolIdentity.QUERY_SYMBOLS, admitted.identity)
        val reply = expected.getOrElse(invoked.size) { error("Excess provider invocation") }
        invoked.add((admitted.canonical as PublicToolCanonical.Query).request)
        reply
    }

    private fun input(records: List<String>) =
        ByteArrayInputStream(records.joinToString("\n", postfix = "\n").toByteArray())

    private fun runRequest(): String =
        PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).examples.getValue("runByName").value.toString()

    private fun readRequest(cursor: Int = 0, reference: QueryResultReference = result): String {
        val selectedCursor = parsed(QueryResultCursor.parse(cursor))
        return Json.encodeToString(QuerySessionTestReadQuery(QuerySessionTestRead(reference, selectedCursor)))
    }

    private fun resumeRequest(continuation: QueryExecutionContinuation.Output = output): String =
        Json.encodeToString(HeaderSessionResumeQuery(HeaderSessionResume(continuation)))

    private fun <T> parsed(refinement: Refinement<T, *>): T =
        when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error("Invalid explicit test value: ${refinement.failure}")
        }
}

@Serializable private data class HeaderSessionResumeQuery(val request: HeaderSessionResume)

@Serializable
private data class HeaderSessionResume(
    val continuation: QueryExecutionContinuation.Output,
    @Required val type: QuerySessionTestAction = QuerySessionTestAction.RESUME,
)
