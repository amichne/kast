package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryExecutionContinuation
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.SourceReadFailureDetail
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReferenceFailure
import io.github.amichne.kast.protocol.contract.SourceReferenceRole
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.canonicalReadRejectedDocument
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastToolRpcQuerySessionTest {
    private val cases = Json.decodeFromString<Map<String, ToolRpcReply>>(QueryDeliveryFixtures.serialized())
    private val result = parsed(QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001"))
    private val otherResult = parsed(QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000003"))
    private val output =
        parsed(QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000002"))

    @Test
    fun `initial source read preserves canonical stale and unsupported authority failures`() {
        val reasons =
            listOf(
                SourceReadRejection.CANDIDATE_STALE,
                SourceReadFailureDetail.ReferenceRejected(
                    SourceReferenceRole.CANDIDATE,
                    SourceReferenceFailure.STALE_AUTHORITY,
                ),
                SourceReadFailureDetail.ReferenceRejected(
                    SourceReferenceRole.CANDIDATE,
                    SourceReferenceFailure.REVALIDATION_UNSUPPORTED_DECLARATION,
                ),
            )
        for (reason in reasons) {
            val document = Json.parseToJsonElement(canonicalReadRejectedDocument(reason).value).jsonObject
            val expected = ToolRpcReply.RejectedDocument(document)
            val actual = mutableListOf<ToolRpcReply>()
            var invoked = 0
            KastToolRpcQuerySession { admitted ->
                    assertTrue(admitted.canonical is PublicToolCanonical.Source)
                    invoked++
                    expected
                }
                .run(input(listOf(sourceRequest(), runRequest())), actual::add)
            assertEquals(listOf(expected), actual)
            assertEquals(1, invoked)
            assertTrue("next_action" in document)
        }
    }

    @Test
    fun `source read after a query names session policy without claiming candidate staleness`() {
        val invoked = mutableListOf<QueryRunRequest>()
        val actual = run(listOf(runRequest(), sourceRequest()), listOf(cases.getValue("prefix")), invoked)
        val rejection = Json.encodeToJsonElement<ToolRpcReply>(actual.last()).jsonObject
        assertEquals("QUERY_SESSION_TRANSITION_REJECTED", rejection.getValue("failure").jsonPrimitive.content)
        val detail = rejection.getValue("detail").jsonObject
        assertEquals("SOURCE_READ_REQUIRES_NEW_SESSION", detail.getValue("type").jsonPrimitive.content)
        assertEquals("START_NEW_QUERY_SESSION", detail.getValue("next_action").jsonPrimitive.content)
        assertEquals(setOf("type", "next_action"), detail.keys)
        assertEquals(1, invoked.size)
    }

    @Test
    fun `malformed source selector preserves anchor admission cause and safe action`() {
        val request =
            Json.encodeToString(
                QuerySessionTestSourceQuery(QuerySessionTestSource("candidate:v3:YQ:" + "0".repeat(64)))
            )
        val actual = mutableListOf<ToolRpcReply>()
        KastToolRpcQuerySession { error("Invalid source selector must not dispatch") }
            .run(input(listOf(request)), actual::add)
        val rejection = Json.encodeToJsonElement<ToolRpcReply>(actual.single()).jsonObject
        assertEquals("INVALID_ARGUMENTS", rejection.getValue("failure").jsonPrimitive.content)
        val detail = rejection.getValue("detail").jsonObject
        assertEquals("SOURCE_ANCHOR_REJECTED", detail.getValue("type").jsonPrimitive.content)
        assertEquals("PAYLOAD_DIGEST_MISMATCH", detail.getValue("cause").jsonPrimitive.content)
        assertEquals("USE_ISSUED_CANDIDATE_SELECTOR", detail.getValue("next_action").jsonPrimitive.content)
    }

    @Test
    fun `missing preceding capability rejects without inventing expected authority`() {
        val cases =
            listOf(
                Triple(
                    this.cases.getValue("prefix"),
                    resumeRequest(),
                    QuerySessionRejectionDetail.ContinuationNotIssued(null, output),
                ),
                Triple(
                    this.cases.getValue("outputPrefix"),
                    readRequest(),
                    QuerySessionRejectionDetail.ResultNotIssued(null, result),
                ),
            )
        for ((initial, successor, detail) in cases) {
            val invoked = mutableListOf<QueryRunRequest>()
            assertEquals(
                listOf(initial, ToolRpcReply.Rejected.querySession(detail)),
                run(listOf(runRequest(), successor), listOf(initial), invoked),
            )
            assertEquals(1, invoked.size)
        }
    }

    @Test
    fun `one run drains exact canonical result and evidence replies without semantic replay`() {
        val expected = listOf(cases.getValue("prefix"), cases.getValue("rows"), cases.getValue("tail"))
        val invoked = mutableListOf<QueryRunRequest>()
        val actual = run(listOf(runRequest(), readRequest(1), readRequest(43)), expected, invoked)
        assertEquals(expected, actual)
        assertEquals(1, invoked.count { it is QueryRunRequest.Run })
        assertEquals(2, invoked.count { it is QueryRunRequest.ReadResult })
    }

    @Test
    fun `saved retained and output handles can start a new session and fail honestly`() {
        for ((request, failure) in
            listOf(readRequest() to cases.getValue("stale"), resumeRequest() to cases.getValue("expiredOutput"))) {
            val invoked = mutableListOf<QueryRunRequest>()
            assertEquals(listOf(failure), run(listOf(request, runRequest()), listOf(failure), invoked))
            assertEquals(1, invoked.size)
        }
    }

    @Test
    fun `second run and unrelated retained handle fail before dispatch`() {
        for ((successor, detail) in
            listOf(
                runRequest() to QuerySessionRejectionDetail.NewRun.value,
                readRequest(reference = otherResult) to
                    QuerySessionRejectionDetail.ResultNotIssued(result, otherResult),
            )) {
            val invoked = mutableListOf<QueryRunRequest>()
            assertEquals(
                listOf(cases.getValue("prefix"), ToolRpcReply.Rejected.querySession(detail)),
                run(listOf(runRequest(), successor, readRequest()), listOf(cases.getValue("prefix")), invoked),
            )
            assertEquals(1, invoked.size)
        }
    }

    @Test
    fun `only preceding issued output continuation resumes without new run`() {
        val expected = listOf(cases.getValue("outputPrefix"), cases.getValue("outputFinal"))
        val invoked = mutableListOf<QueryRunRequest>()
        assertEquals(expected, run(listOf(runRequest(), resumeRequest(), runRequest()), expected, invoked))
        assertEquals(1, invoked.count { it is QueryRunRequest.Run })
        assertEquals(output, (invoked.last() as QueryRunRequest.Resume).continuation)
        val unissued =
            parsed(QueryExecutionContinuation.Output.parse("query-output:v1:00000000-0000-0000-0000-000000000003"))
        val rejectedInvoked = mutableListOf<QueryRunRequest>()
        assertEquals(
            listOf(
                expected.first(),
                ToolRpcReply.Rejected.querySession(QuerySessionRejectionDetail.ContinuationNotIssued(output, unissued)),
            ),
            run(listOf(runRequest(), resumeRequest(unissued)), listOf(expected.first()), rejectedInvoked),
        )
        assertEquals(1, rejectedInvoked.size)
    }

    @Test
    fun `policy rejection remains first reply while its retained evidence drains`() {
        val expected = listOf(cases.getValue("rejected"), cases.getValue("proof"), cases.getValue("proofTail"))
        val invoked = mutableListOf<QueryRunRequest>()
        assertEquals(expected, run(listOf(runRequest(), readRequest(), readRequest(43)), expected, invoked))
        assertEquals(1, invoked.count { it is QueryRunRequest.Run })
        assertEquals(2, invoked.count { it is QueryRunRequest.ReadResult })
    }

    @Test
    fun `first transport failure ends session unchanged without consuming another invocation`() {
        val failure = ToolRpcReply.Rejected(ToolRpcFailure.INSTALLATION_STOPPED)
        val invoked = mutableListOf<QueryRunRequest>()
        assertEquals(listOf(failure), run(listOf(runRequest(), runRequest()), listOf(failure), invoked))
        assertEquals(1, invoked.size)
    }

    @Test
    fun `invalid utf8 oversized and malformed records reject before invoking`() {
        val inputs =
            listOf(
                byteArrayOf(0xC3.toByte(), 0x28, 0x0A) to ToolRpcFailure.INVALID_ARGUMENTS,
                ByteArray(MAXIMUM_PROTOCOL_TEXT_LENGTH + 2) { 'x'.code.toByte() } to ToolRpcFailure.REQUEST_TOO_LARGE,
                "[\n".toByteArray() to ToolRpcFailure.INVALID_ARGUMENTS,
                "\n".toByteArray() to ToolRpcFailure.INVALID_ARGUMENTS,
            )
        for ((bytes, failure) in inputs) {
            val actual = mutableListOf<ToolRpcReply>()
            KastToolRpcQuerySession { error("Rejected framing must not dispatch") }
                .run(ByteArrayInputStream(bytes), actual::add)
            assertEquals(listOf(ToolRpcReply.Rejected(failure)), actual)
        }
    }

    @Test
    fun `framing bound applies to each record and unterminated final record is accepted`() {
        val invoked = mutableListOf<QueryRunRequest>()
        val expected = listOf(cases.getValue("prefix"), cases.getValue("tail"))
        val records = listOf(runRequest(), readRequest(43)).joinToString("\n")
        val actual = mutableListOf<ToolRpcReply>()
        val provider = scripted(expected, invoked)
        KastToolRpcQuerySession(provider).run(ByteArrayInputStream(records.toByteArray()), actual::add)
        assertEquals(expected, actual)
        assertEquals(2, invoked.size)
        val empty = mutableListOf<ToolRpcReply>()
        KastToolRpcQuerySession { error("EOF cannot invoke") }.run(ByteArrayInputStream(byteArrayOf()), empty::add)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `two maximum sized records remain separately bounded`() {
        val records =
            listOf(runRequest(), readRequest(43)).map { it + " ".repeat(MAXIMUM_PROTOCOL_TEXT_LENGTH - it.length) }
        val expected = listOf(cases.getValue("prefix"), cases.getValue("tail"))
        val invoked = mutableListOf<QueryRunRequest>()
        assertEquals(expected, run(records, expected, invoked))
        assertEquals(2, invoked.size)
    }

    @Test
    fun `fixed process session cap permits exactly sixty four requests`() {
        val records = listOf(runRequest()) + List(MAXIMUM_QUERY_SESSION_REQUESTS) { readRequest() }
        val expected = List(MAXIMUM_QUERY_SESSION_REQUESTS) { cases.getValue("prefix") }
        val invoked = mutableListOf<QueryRunRequest>()
        assertEquals(expected, run(records, expected, invoked))
        assertEquals(64, invoked.size)
        assertEquals(1, invoked.count { it is QueryRunRequest.Run })
    }

    @Test
    fun `cancellation propagates to owner without dispatching a successor or inventing a reply`() {
        var invoked = 0
        val actual = mutableListOf<ToolRpcReply>()
        val cancellation = CancellationException("owner cancelled")
        val thrown =
            assertThrows(CancellationException::class.java) {
                KastToolRpcQuerySession {
                        invoked++
                        throw cancellation
                    }
                    .run(input(listOf(runRequest(), readRequest())), actual::add)
            }
        assertEquals(cancellation, thrown)
        assertEquals(1, invoked)
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `query session capability is optional and encoded without changing schema version`() {
        val encoded = Json.encodeToString(ToolRpcCatalog(emptyList()))
        val catalog = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("true", catalog.getValue("querySession").jsonPrimitive.content)
        assertEquals("16", catalog.getValue("schemaVersion").jsonPrimitive.content)
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

    private fun sourceRequest(): String =
        PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS)
            .examples
            .getValue("readCallbackSource")
            .value
            .toString()

    private fun readRequest(cursor: Int = 0, reference: QueryResultReference = result): String {
        val selectedCursor = parsed(QueryResultCursor.parse(cursor))
        return Json.encodeToString(QuerySessionTestReadQuery(QuerySessionTestRead(reference, selectedCursor)))
    }

    private fun resumeRequest(continuation: QueryExecutionContinuation.Output = output): String =
        Json.encodeToString(QuerySessionTestResumeQuery(QuerySessionTestResume(continuation)))

    private fun <T> parsed(refinement: Refinement<T, *>): T =
        when (refinement) {
            is Refinement.Refined -> refinement.value
            is Refinement.Rejected -> error("Invalid explicit test value: ${refinement.failure}")
        }
}

@Serializable internal data class QuerySessionTestReadQuery(val request: QuerySessionTestRead)

@Serializable
internal data class QuerySessionTestRead(
    val result: QueryResultReference,
    val cursor: QueryResultCursor,
    @Required val type: QuerySessionTestAction = QuerySessionTestAction.READ_RESULT,
)

@Serializable private data class QuerySessionTestResumeQuery(val request: QuerySessionTestResume)

@Serializable
private data class QuerySessionTestResume(
    val continuation: QueryExecutionContinuation.Output,
    @Required val type: QuerySessionTestAction = QuerySessionTestAction.RESUME,
)

@Serializable private data class QuerySessionTestSourceQuery(val request: QuerySessionTestSource)

@Serializable
private data class QuerySessionTestSource(
    val candidateRef: String,
    @Required val type: QuerySessionTestAction = QuerySessionTestAction.READ_SOURCE,
)

@Serializable
internal enum class QuerySessionTestAction {
    READ_RESULT,
    RESUME,
    READ_SOURCE,
}
