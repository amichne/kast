package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.OperationQualification
import io.github.amichne.kast.protocol.contract.OperationRejection
import io.github.amichne.kast.protocol.contract.OperationRequest
import io.github.amichne.kast.protocol.contract.OperationResult
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.SourceEntityLimitDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadPageDocument
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextByteLimitDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedReadBudgetAdmissionTest {
    @Test
    fun `all read decoders reject malformed budget dimensions before provider dispatch`() {
        var providerCalls = 0
        val budget = budget()
        val budgetJson = Json.encodeToString(budget)
        for (request in requests(budget)) {
            assertInstanceOf(Refinement.Refined::class.java, decode(request) { providerCalls += 1 })
            for (invalid in listOf("0", "-1", "9223372036854775808", "1.5", "\"10\"")) {
                val scalar = Json.parseToJsonElement(invalid)
                for (invalidBudget in
                    listOf(
                        InvalidReadBudget(elapsed = scalar),
                        InvalidReadBudget(work = scalar),
                        InvalidReadBudget(results = scalar),
                        InvalidReadBudget(bytes = scalar),
                    )) {
                    val malformed =
                        request.copy(
                            document = request.document.replace(budgetJson, Json.encodeToString(invalidBudget))
                        )
                    assertEquals(
                        Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST),
                        decode(malformed) { providerCalls += 1 },
                    )
                }
            }
            for (invalidBudget in
                listOf(
                    Json.encodeToString(UnsupportedReadBudget(1)),
                    Json.encodeToString(emptyList<String>()),
                    Json.encodeToString("unknown"),
                )) {
                val malformed = request.copy(document = request.document.replace(budgetJson, invalidBudget))
                assertEquals(
                    Refinement.Rejected(HostedEndpointFailure.INVALID_REQUEST),
                    decode(malformed) { providerCalls += 1 },
                )
            }
        }
        assertEquals(5, providerCalls, "Only the five valid controls may enter provider dispatch")
    }

    @Test
    fun `one byte cannot admit a canonical read envelope or start provider work`() {
        var providerCalls = 0
        for (request in requests(budget(bytes = 1))) {
            val rejected = assertInstanceOf(Refinement.Rejected::class.java, decode(request) { providerCalls += 1 })
            assertEquals(HostedEndpointFailure.INVALID_REQUEST, rejected.failure)
        }
        assertEquals(0, providerCalls)
    }

    @Test
    fun `only diagnostic requests select caller elapsed completion`() {
        for (request in requests(budget())) {
            val admitted = decode(request) {}.proven() as HostedRequest.Read
            val expected =
                if (admitted is HostedRequest.Diagnostic)
                    io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadCompletionPolicy.CALLER_ELAPSED
                else io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadCompletionPolicy.HOST_CONTAINMENT
            assertEquals(expected, admitted.completionPolicy())
        }
    }

    @Test
    fun `default controls remain admissible through every outer read decoder`() {
        for (request in requests(ExecutionBudgetDocument())) {
            assertInstanceOf(Refinement.Refined::class.java, decode(request) {})
        }
    }

    private fun decode(
        request: HostedReadInput,
        provider: (HostedRequest) -> Unit,
    ): Refinement<HostedRequest, HostedEndpointFailure> =
        HostedRequests.decode(Json.encodeToString(request)).also { result ->
            if (result is Refinement.Refined) provider(result.value)
        }

    private fun diagnosticInput(budget: ExecutionBudgetDocument) =
        input(
            CanonicalOperationWireBindings.diagnosticCheck,
            DiagnosticCheckRequest(
                ProtocolText.parse(".").proven(),
                ProtocolCount.parse(1000).proven(),
                executionBudget = budget,
            ),
        )

    private fun requests(budget: ExecutionBudgetDocument): List<HostedReadInput> {
        val fixture = RelationPagingFixture.live()
        return listOf(
            diagnosticInput(budget),
            input(
                CanonicalOperationWireBindings.queryRun,
                QueryRunRequest(
                    QueryFromDocument.References(bounded(listOf(QueryReferenceDocument.ExactSymbol(fixture.exact)))),
                    bounded(emptyList()),
                    QueryOutputDocument.Symbols(bounded(emptyList())),
                    QueryExecutionDocument(
                        QueryExecutionKindDocument.EXHAUSTIVE,
                        QueryExecutionBudgetDocument.INTERACTIVE,
                    ),
                    executionBudget = budget,
                ),
            ),
            input(
                CanonicalOperationWireBindings.sourceRead,
                SourceReadRequest(
                    SourceReadAnchorDocument.Symbol(fixture.exact),
                    SourceRegionSelectionDocument.Anchor,
                    SourceEntitySelectionDocument.None,
                    SourceTextRequestDocument.None,
                    SourceEntityLimitDocument.parse(100).proven(),
                    SourceTextByteLimitDocument.parse(65536).proven(),
                    SourceReadPageDocument.First,
                    executionBudget = budget,
                ),
            ),
            input(
                CanonicalOperationWireBindings.relationRead,
                fixture.request(RelationReadPositionDocument.Start).copy(executionBudget = budget),
            ),
            input(
                CanonicalOperationWireBindings.traversalRun,
                TraversalRunRequest(
                    fixture.exact,
                    RelationKindDocument.CALLEES,
                    ProtocolCount.parse(2).proven(),
                    ProtocolCount.parse(100).proven(),
                    executionBudget = budget,
                ),
            ),
        )
    }

    private fun <
        Request : OperationRequest,
        Result : OperationResult,
        Qualification : OperationQualification,
        Rejection : OperationRejection,
    > input(
        binding: OperationWireBinding<Request, Result, Qualification, Rejection>,
        request: Request,
    ) =
        HostedReadInput(
            binding.operation.name,
            (binding.encodeRequest(request) as WireEncoding.Encoded).document,
            "/workspace",
        )

    private fun budget(bytes: Long = 2048) =
        ExecutionBudgetDocument(
            ElapsedTimeLimitMillis.parse(200).proven(),
            WorkUnitLimit.parse(7).proven(),
            ResultLimit.parse(2).proven(),
            ReturnedByteLimit.parse(bytes).proven(),
        )

    private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).proven()

    private fun <Value> Refinement<Value, *>.proven(): Value = (this as Refinement.Refined).value
}

@Serializable private data class HostedReadInput(val type: String, val document: String, val root: String)

/** Invalid scalar fixtures intentionally remain opaque until the real request decoder rejects them. */
@Serializable
private data class InvalidReadBudget(
    @SerialName("max_elapsed_ms") val elapsed: JsonElement? = null,
    @SerialName("max_work_units") val work: JsonElement? = null,
    @SerialName("max_results") val results: JsonElement? = null,
    @SerialName("max_returned_bytes") val bytes: JsonElement? = null,
)

@Serializable private data class UnsupportedReadBudget(val unsupported: Int)
