package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackOwnerExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackUseDocument
import io.github.amichne.kast.protocol.contract.QueryImmutableCallbackValueOriginDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.RelationReferenceOwnershipDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.protocol.CanonicalQueryProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Exact independent fixture baseline; no installed-host or genuine Gradle-import claim. */
class NativeDecoderCallbackBaselineTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testUseReferencesResolveAllThreeOccurrences() = references(DecoderCallbackRequests.useScope)

    fun testWorkspaceReferencesResolveAllThreeOccurrences() = references(QueryExpansionScopeDocument.Workspace)

    fun testUseCallersKeepNamedOwnershipSeparate() = callers(DecoderCallbackRequests.useScope)

    fun testWorkspaceCallersKeepNamedOwnershipSeparate() = callers(QueryExpansionScopeDocument.Workspace)

    fun testUseDepthOneCallers() = walk(DecoderCallbackRequests.useScope, 1)

    fun testUseDepthTwoCallers() = walk(DecoderCallbackRequests.useScope, 2)

    fun testWorkspaceDepthOneCallers() = walk(QueryExpansionScopeDocument.Workspace, 1)

    fun testWorkspaceDepthTwoCallers() = walk(QueryExpansionScopeDocument.Workspace, 2)

    private fun references(scope: QueryExpansionScopeDocument) = runBlocking {
        val result =
            query(QueryStepDocument.Related(RelationKindDocument.REFERENCES, scope), QueryOutputDocument.Occurrences)
        val references = result.items.values.map { (it as QueryResultItemDocument.ReferenceOccurrence).occurrence }
        val source = DecoderFixtureModule.USE.source()
        val starts = Regex("decode\\(value\\)").findAll(source).map { it.range.first }.toSet()
        val owners = setOf("example.use.direct", "example.use.nested", "example.use.deferred")
        assertEquals(owners.size, references.size)
        assertEquals(
            owners,
            references
                .map { reference ->
                    val owner = reference.ownership as RelationReferenceOwnershipDocument.DeclarationOwned
                    (owner.declaration.compilerEvidence.signature as CompilerSignatureDocument.Function)
                        .qualifiedIdentity
                        .value
                }
                .toSet(),
        )
        assertEquals(starts, references.map { it.occurrence.range.startInclusive.value }.toSet())
        val file = getOrCreateProjectBaseDir().toNioPath().resolve(DecoderFixtureModule.USE.relativePath).toString()
        assertTrue(references.all { it.occurrence.file.value == file })
        assertEquals(1, references.map { it.target.compilerEvidence }.distinct().size)
        references.forEach { reference ->
            val signature = reference.target.compilerEvidence.signature as CompilerSignatureDocument.Function
            assertEquals("example.contract.Decoder.decode", signature.qualifiedIdentity.value)
            assertTrue(reference.target.file.value.endsWith(DecoderFixtureModule.CONTRACT.relativePath))
        }
        println("Decoder exact reference occurrences=$references")
    }

    private fun callers(scope: QueryExpansionScopeDocument) = runBlocking {
        val result =
            query(QueryStepDocument.Related(RelationKindDocument.CALLERS, scope), DecoderCallbackRequests.output)
        assertEquals(
            listOf("example.use.direct"),
            result.items.values.map { identity(it as QueryResultItemDocument.ExactSymbol) },
        )
        assertCallbacks(result.relationObservations.values.flatMap { it.callbackObservations.values })
    }

    private fun walk(scope: QueryExpansionScopeDocument, depth: Int) = runBlocking {
        val step =
            QueryStepDocument.Walk(
                RelationKindDocument.CALLERS,
                ProtocolCount.parse(depth).traceRefined(),
                expansionScope = scope,
            )
        val result = query(step, DecoderCallbackRequests.output)
        val names = result.items.values.map { identity(it as QueryResultItemDocument.ExactSymbol) }.toSet()
        assertEquals(setOf("example.use.direct"), names)
        assertCallbacks(
            result.walkObservations.values.flatMap { observation ->
                observation.callbackObservations.values.map { it.observation }
            }
        )
    }

    private fun assertCallbacks(callbacks: List<QueryCallbackObservationDocument>) {
        val byOwner = callbacks.associateBy { callback ->
            (callback.lexicalOwner.compilerTarget.compilerEvidence.signature as CompilerSignatureDocument.Function)
                .qualifiedIdentity
                .value
        }
        assertEquals(setOf("example.use.nested", "example.use.deferred"), byOwner.keys)
        assertEquals(2, callbacks.size)
        assertNested(byOwner.getValue("example.use.nested"))
        assertDeferred(byOwner.getValue("example.use.deferred"))
    }

    private fun assertNested(callback: QueryCallbackObservationDocument) {
        val policy = callback.namedPolicy as QueryCallbackNamedPolicyDocument.Excluded
        assertEquals(QueryCallbackExclusionReasonDocument.NON_INLINE_ARGUMENT, policy.reason)
        assertEquals(callback.callbackBody, policy.excludedBoundary)
        val inner = callback.flow as QueryCallbackFlowDocument.Observed
        assertEquals(callback.callbackBody, inner.body.occurrence)
        assertSource(inner.body.occurrence, DecoderFixtureModule.USE, "{\n        decoder.decode(value)\n    }")
        assertSource(callback.occurrence, DecoderFixtureModule.USE, "decode")
        assertTrue(inner.obligations.values.isEmpty())
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, inner.scan)
        val capture = inner.binding as QueryCallbackBindingDocument.Bound
        assertEquals(0, capture.position.value)
        assertSource(capture.parameter, DecoderFixtureModule.FLOW, "block: () -> T")
        assertSource(
            capture.invocationOccurrence,
            DecoderFixtureModule.USE,
            "capture {\n        decoder.decode(value)\n    }",
        )
        val block = inner.invocations.values.single()
        assertSource(block.occurrence, DecoderFixtureModule.FLOW, "block()")
        assertEquals(capture.callable, (block.owner as QueryCallbackBodyDocument.Named).callable)
        val outerBinding = inner.ownerBindings.values.single()
        assertEquals(outerBinding.body, capture.invocationOwner)
        assertTrue(outerBinding.obligations.values.isEmpty())
        val outer = (outerBinding.execution as QueryCallbackOwnerExecutionDocument.Observed).flow
        assertEquals(outerBinding.body, outer.body)
        assertEquals(outerBinding.binding, outer.binding)
        assertTrue(outer.obligations.values.isEmpty())
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, outer.scan)
        val runFlow = outer.binding as QueryCallbackBindingDocument.Bound
        assertEquals(0, runFlow.position.value)
        assertSource(runFlow.parameter, DecoderFixtureModule.FLOW, "block: FlowScope.() -> T")
        assertEquals(callback.lexicalOwner, (runFlow.invocationOwner as QueryCallbackBodyDocument.Named).callable)
        val invoke = outer.invocations.values.single()
        assertSource(invoke.occurrence, DecoderFixtureModule.FLOW, "FlowScope().block()")
        assertEquals(runFlow.callable, (invoke.owner as QueryCallbackBodyDocument.Named).callable)
    }

    private fun assertDeferred(callback: QueryCallbackObservationDocument) {
        val policy = callback.namedPolicy as QueryCallbackNamedPolicyDocument.Excluded
        assertEquals(QueryCallbackExclusionReasonDocument.RETURNED_CALLBACK, policy.reason)
        val flow = (callback.flow as QueryCallbackFlowDocument.Immutable).flow
        val origin = flow.sourceValue.origin as QueryImmutableCallbackValueOriginDocument.Anonymous
        assertEquals(callback.callbackBody, origin.body.occurrence)
        assertSource(origin.body.occurrence, DecoderFixtureModule.USE, "{\n    decoder.decode(value)\n}")
        assertTrue(flow.uses.values.single() is QueryImmutableCallbackUseDocument.Unused)
        assertTrue(flow.sourceValue.transfers.values.isEmpty())
        assertTrue(flow.sourceValue.invokedCallables.values.isEmpty())
        assertTrue(flow.ownerBindings.values.isEmpty())
        assertTrue(flow.obligations.values.isEmpty())
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, flow.scan)
    }

    private fun assertSource(occurrence: RelationOccurrenceDocument, unit: DecoderFixtureModule, expected: String) {
        assertTrue(occurrence.file.value.endsWith(unit.relativePath))
        val range = occurrence.range
        assertEquals(expected, unit.source().substring(range.startInclusive.value, range.endExclusive.value))
    }

    private suspend fun query(step: QueryStepDocument, output: QueryOutputDocument): QueryRunResult {
        val fixture =
            NativeDecoderCallbackFixture(project, getOrCreateProjectBaseDir(), module).prepare(::registerTestProjectJdk)
        val protocol = fixture.protocol()
        val discovered = execute(protocol, fixture, DecoderCallbackRequests.discover())
        assertTrue("The interface declaration must resolve: $discovered", discovered is OperationOutcome.Complete)
        val selected =
            (discovered as OperationOutcome.Complete).evidence.payload.items.values.single()
                as QueryResultItemDocument.ExactSymbol
        assertEquals("example.contract.Decoder.decode", identity(selected))
        val request = DecoderCallbackRequests.query(selected.ref, step, output)
        val outcome = execute(protocol, fixture, request)
        if (outcome is OperationOutcome.Rejected) {
            val reason = outcome.reason
            if (reason is QueryRunRejection.CompletionUnproven) {
                val retained = reason.evidence
                if (retained is QueryCompletionEvidenceDocument.Retained) {
                    execute(protocol, fixture, retained.readRequest())
                }
            }
        }
        println("Decoder native terminations=${fixture.observation.terminations}")
        println("Decoder native unexpected=${fixture.observation.unexpectedFailures}")
        assertTrue(
            "Expected complete independent-fixture baseline; inspect serialized retained evidence",
            outcome is OperationOutcome.Complete,
        )
        return (outcome as OperationOutcome.Complete).evidence.payload
    }

    private suspend fun execute(
        protocol: CanonicalQueryProtocol,
        fixture: NativeScopedTraceFixture,
        request: QueryRunRequest,
    ): io.github.amichne.kast.kernel.OperationOutcome<
        QueryRunResult,
        io.github.amichne.kast.protocol.contract.QueryRunQualification,
        io.github.amichne.kast.protocol.contract.QueryRunFailure,
    > {
        println("Decoder query input=${Json.encodeToString(request)}")
        val outcome =
            protocol.execute(
                request = request,
                lease = fixture.lease,
                budget = CrossModuleCallbackRequests.budget,
                policy = CrossModuleCallbackRequests.policy,
            )
        val document =
            when (val projected = CanonicalQueryCliDocuments.project(outcome)) {
                is ProjectedOperationOutcome.Complete -> projected.document
                is ProjectedOperationOutcome.Qualified -> projected.document
                is ProjectedOperationOutcome.Rejected -> projected.document
            }
        println("Decoder query response=${document.value}")
        return outcome
    }

    private fun identity(item: QueryResultItemDocument.ExactSymbol): String =
        (item.signature as CompilerSignatureDocument.Function).qualifiedIdentity.value
}
