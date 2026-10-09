package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRetentionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.contract.QueryPresentationExecution
import io.github.amichne.kast.query.contract.QueryRelationObservation
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import io.github.amichne.kast.query.protocol.RelationPagingFixture
import io.github.amichne.kast.query.protocol.executeQueryPage
import io.github.amichne.kast.query.service.QueryNanoClock
import io.github.amichne.kast.query.service.QueryService
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.source.contract.SourceReadOperations
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.ExactSymbolRequest
import io.github.amichne.kast.symbol.contract.SymbolDescription
import io.github.amichne.kast.symbol.contract.SymbolDescriptionResult
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOperations
import io.github.amichne.kast.symbol.contract.SymbolExactOperations
import io.github.amichne.kast.symbol.contract.SymbolResolutionRequest
import io.github.amichne.kast.symbol.contract.SymbolResolutionResult
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalOperations
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real evaluator, protocol and encoded fitter; compiler facts enter only through scripted native ports. */
class HostedCallbackByteAdmissionTest {
    @Test
    fun `encoded callback evidence fits despite a larger detached storage estimate`() = runTest {
        val fixture = CallbackByteFixture()
        val response =
            QueryPresentationExecution.evaluateAndFit(
                evaluate = { owner ->
                    fixture
                        .protocol(owner)
                        .executeQueryPage(fixture.request(), fixture.symbols.authority, fixture.budget)
                },
                fit = { page ->
                    encodeHostedQueryResponse(page, maximumBytes = ReturnedByteLimit.parse(65536).callbackValue())
                },
            )
        val page = assertInstanceOf(HostedResponse.Canonical::class.java, response)
        assertTrue(page.document.toByteArray(Charsets.UTF_8).size <= 65536)
        val complete = assertInstanceOf(OperationOutcome.Complete::class.java, page.semantic)
        val result = complete.evidence.payload as io.github.amichne.kast.protocol.contract.QueryRunResult
        assertTrue(result.items.values.isEmpty())
        val callback = result.relationObservations.values.single().callbackObservations.values.single()
        assertCallbackEvidence(callback)
        assertTrue(fixture.projectedBytes > 65536)
        assertEquals(1, fixture.relationCalls)
    }

    private fun assertCallbackEvidence(callback: QueryCallbackObservationDocument) {
        assertEquals(30, callback.occurrence.range.startInclusive.value)
        assertEquals("read", callback.lexicalOwner.compilerTarget.name.value)
        assertEquals("readPrepared", callback.target.compilerTarget.name.value)
        assertEquals(20, callback.callbackBody.range.startInclusive.value)
        val flow = assertInstanceOf(QueryCallbackFlowDocument.Observed::class.java, callback.flow)
        val binding = assertInstanceOf(QueryCallbackBindingDocument.Bound::class.java, flow.binding)
        assertEquals(5, binding.position.value)
        assertEquals("nativeBoundary", binding.callable.compilerTarget.name.value)
        assertEquals(110, binding.parameter.range.startInclusive.value)
        assertEquals(120, flow.invocations.values.single().occurrence.range.startInclusive.value)
        assertInstanceOf(QueryCallbackBodyDocument.Anonymous::class.java, flow.invocations.values.single().owner)
        assertEquals(listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION), flow.obligations.values)
        assertTrue(callback.occurrence.candidateSelector.value.isNotBlank())
        assertTrue(binding.parameter.candidateSelector.value.isNotBlank())
    }

    @Test
    fun `standalone callback evaluation retains its conservative byte guard`() = runTest {
        val fixture = CallbackByteFixture()
        val result =
            fixture.protocol(null).executeQueryPage(fixture.request(), fixture.symbols.authority, fixture.budget)
        val qualified =
            assertInstanceOf(OperationOutcome.Qualified::class.java, result)
                as
                OperationOutcome.Qualified<
                    io.github.amichne.kast.protocol.contract.QueryRunResult,
                    io.github.amichne.kast.protocol.contract.QueryRunQualification,
                >
        assertTrue(qualified.evidence.payload.relationObservations.values.isEmpty())
        val terminal =
            assertInstanceOf(
                QueryQualifiedProgressDocument.TerminalIncomplete::class.java,
                qualified.qualification.progress,
            )
        assertEquals(QueryTerminalReasonDocument.OUTPUT_ITEM_TOO_LARGE, terminal.reason)
        assertEquals(1, fixture.relationCalls)
    }

    @Test
    fun `paired publisher still rejects an indivisible callback larger than encoded allowance`() = runTest {
        val fixture = CallbackByteFixture()
        val response =
            QueryPresentationExecution.evaluateAndFit(
                evaluate = { owner ->
                    fixture
                        .protocol(owner)
                        .executeQueryPage(fixture.request(), fixture.symbols.authority, fixture.budget)
                },
                fit = { page ->
                    encodeHostedQueryResponse(page, maximumBytes = ReturnedByteLimit.parse(2000).callbackValue())
                },
            )
        assertInstanceOf(HostedResponse.Oversized::class.java, response)
        assertEquals(1, fixture.relationCalls)
    }
}

private class CallbackByteFixture {
    val symbols = RelationPagingFixture(RelationPagingFixture.published().authority, "readPrepared")
    val budget = QueryBudget(symbols.budget.resources, QueryByteLimit.parse(65536).callbackValue())
    var relationCalls = 0
    var projectedBytes = 0L
    private val read = evidence("read", 10, 1000, emptyList())
    private val receiver = evidence("nativeBoundary", 100, 200, List(6) { "() -> kotlin.String" })

    fun protocol(owner: QueryPresentationExecution?): CanonicalQueryProtocol =
        CanonicalQueryProtocol(
            QueryService(
                SymbolDiscoveryOperations { error("Unexpected discovery") },
                object : SymbolExactOperations {
                    override suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult =
                        error("Unexpected resolve")

                    override suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult =
                        SymbolDescriptionResult.Described(SymbolDescription.from(request.selector))
                },
                SourceReadOperations { error("Unexpected source") },
                RelationOperations(::relation),
                TraversalOperations { error("Unexpected traversal") },
                TraversalBudget(
                    budget.resources.resultLimit,
                    TraversalByteLimit.parse(65536).callbackValue(),
                    budget.resources.workUnitLimit,
                    budget.resources.elapsedTimeLimit,
                    TraversalDepthLimit.parse(1).callbackValue(),
                    TraversalFrontierLimit.parse(3).callbackValue(),
                    symbols.budget,
                ),
                clock = QueryNanoClock { 0L },
                presentation = owner,
            ),
            symbols.references,
        )

    private fun relation(request: RelationRequest): RelationReadResult {
        check(relationCalls++ == 0) { "Unexpected additional native expansion" }
        val callback = callback(request)
        val batch =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(callback.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong())
                        .callbackValue(),
                    RelationWorkCount.parse(1).callbackValue(),
                    RelationResultCount.parse(0).callbackValue(),
                    callbackObservations = listOf(callback),
                )
                .callbackValue()
        val result = RelationReadResult.Complete(batch, RelationCompilation.complete(batch).coverage)
        projectedBytes = QueryRelationObservation.from(result).projectedUtf8Size()
        return result
    }

    private fun callback(request: RelationRequest): RelationCallbackObservation {
        return RelationCallbackObservation.fromNativeBoundary(
                request,
                occurrence(30, 42),
                CompilerGroundedSymbolEvidence.fromSelector(symbols.selector),
                read,
                occurrence(20, 80),
                CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.NON_INLINE_ARGUMENT, occurrence(20, 80)),
                CallbackInvocationFlowRead.Observed(flow()),
            )
            .callbackValue()
    }

    private fun flow(): CallbackInvocationFlow {
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(endpoint(read), range(15, 90), endpoint(receiver)).callbackValue(),
                    RelationCallableBody.Named.fromCompiler(read).callbackValue(),
                    ValueArgumentPosition.parse(5).callbackValue(),
                    occurrence(110, 119),
                )
                .callbackValue()
        return CallbackInvocationFlow.fromCompiler(
                symbols.authority.identity,
                anonymous(20, 80),
                CallbackBindingEvidence.Bound(binding),
                listOf(
                    CallbackParameterInvocation.fromCompiler(occurrence(120, 125), anonymous(120, 130)).callbackValue()
                ),
                setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
            )
            .callbackValue()
    }

    private fun evidence(name: String, start: Int, end: Int, parameters: List<String>) =
        CompilerGroundedSymbolEvidence.fromBoundary(
                symbols.selector.file,
                start,
                end,
                name,
                "sample.$name",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("sample.$name", null, emptyList(), parameters, 0).callbackValue(),
            )
            .callbackValue()

    private fun endpoint(evidence: CompilerGroundedSymbolEvidence) =
        RelationEndpoint.resolve(symbols.authority, symbols.selector.scope, evidence).callbackValue()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).callbackValue()

    private fun occurrence(start: Int, end: Int) =
        RelationOccurrence.fromBoundary(symbols.selector.file, start, end).callbackValue()

    private fun anonymous(start: Int, end: Int): RelationCallableBody.Anonymous {
        val range = range(start, end)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(symbols.selector.file, range),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .callbackValue()
        return RelationCallableBody.Anonymous.fromCompiler(
                symbols.selector.file,
                range,
                signature as CanonicalCompilerSignature.Function,
            )
            .callbackValue()
    }

    fun request() =
        QueryRunRequest.Run(
            QueryFromDocument.References(callbackBounded(listOf(QueryReferenceDocument.ExactSymbol(symbols.exact)))),
            callbackBounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLERS))),
            QueryOutputDocument.Occurrences,
            QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            retention = QueryRetentionModeDocument.RETAIN,
        )
}

private fun <T> Refinement<T, *>.callbackValue(): T = (this as Refinement.Refined).value

private fun <T> callbackBounded(values: List<T>) = BoundedProtocolList.create(values).callbackValue()
