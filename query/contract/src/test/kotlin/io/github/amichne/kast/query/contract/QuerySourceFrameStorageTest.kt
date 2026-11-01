package io.github.amichne.kast.query.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CompleteCallbackFlowGraph
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceOwnership
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuerySourceFrameStorageTest {
    @Test
    fun `source return visits its operation while charging the shared implementation frame once`() {
        val fixture = SourceFrameStorageFixture()
        val frame = fixture.frame
        val source =
            ValueSite.fromCompiler(
                    enclosing = frame.callee.enclosing,
                    range = frame.callee.range,
                    role = ValueRole.Return,
                    callStack = ValueCallStack.Empty.enter(frame).stored(),
                )
                .stored()
        val operation = ValueSourceOperation.Return(frame)
        val transfer =
            ValueTransfer.fromCompiler(
                    source = source,
                    target = frame.invocation.resultSite(),
                    kind = ValueTransferKind.SOURCE_CALL_RETURN,
                    evidence = ValueSourceTransfer.fromCompiler(operation),
                )
                .stored()
        val graph = QueryImpactRetainedGraph()
        val retained = transfer.storageBytes(graph)
        assertTrue(retained > 0)
        assertEquals(8L, operation.storageBytes(graph))
        assertEquals(8L, frame.storageBytes(graph))
        assertEquals(8L, fixture.dispatch.storageBytes(graph))
        assertSame(frame, source.callStack.frames.single())
        assertEquals(8L, transfer.storageBytes(graph))
        val distinctOperation = ValueSourceOperation.Return(frame)
        assertTrue(distinctOperation.storageBytes(graph) > 8L)
        assertEquals(8L, distinctOperation.storageBytes(graph))
    }

    @Test
    fun `capture without active stack retains complete supplying implementation authority`() {
        val fixture = SourceFrameStorageFixture()
        val site = fixture.capturedSite()
        assertEquals(ValueCallStack.Empty, site.callStack)
        val context = site.executionOwner.context as ValueExecutionContext.Callback
        val retainedFrame = context.selectedInvocation.supply.frame
        val implementation = retainedFrame.target as ValueCallTarget.Implementation
        val graph = QueryImpactRetainedGraph()
        assertTrue(graph.site(site) > QueryImpactRetainedGraph().site(fixture.base.producer))
        assertSame(fixture.frame, retainedFrame)
        assertEquals(8L, retainedFrame.storageBytes(graph))
        assertEquals(8L, implementation.dispatch.storageBytes(graph))
        assertEquals(8L, graph.node(implementation.relation) { error("Selected relation was not retained") })
        assertEquals(8L, graph.node(implementation.ownership) { error("Source ownership was not retained") })
        val other = SourceFrameStorageFixture()
        assertEquals(fixture.frame, other.frame)
        assertTrue(other.frame.storageBytes(graph) > 8L)
        val expanded = SourceFrameStorageFixture("Concrete." + "segment.".repeat(100) + "accept").capturedSite()
        assertTrue(QueryImpactRetainedGraph().site(expanded) > QueryImpactRetainedGraph().site(site) + 2000L)
    }
}

private class SourceFrameStorageFixture(concreteName: String = "Concrete.accept") {
    val base = QueryImpactLedgerFixture()
    private val declared = endpoint("Base.accept", 210, 300)
    private val concrete = endpoint(concreteName, 310, 400)
    private val request = RelationRequest.start(declared, RelationMeaning.Implementations, base.domain.budget)
    private val fact =
        RelationFact.create(
                request = request,
                source = concrete,
                target = request.subject,
                occurrence = RelationOccurrence.fromSource(concrete.file, concrete.range),
                provenance = RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .stored()
    private val batch =
        RelationBatch.create(
                request = request,
                facts = listOf(fact),
                encodedBytes = RelationByteCount.parse(fact.canonicalProjection().toByteArray().size.toLong()).stored(),
                examinedWorkUnits = RelationWorkCount.parse(1).stored(),
                resultCount = RelationResultCount.parse(1).stored(),
            )
            .stored()
    val dispatch = ValueImplementationDispatch.fromCompiler(RelationCompilation.complete(batch)).stored()
    private val call = ValueInvocation.fromCompiler(base.owner, range(20, 80), declared).stored()
    val frame =
        ValueCallFrame.implementation(
                invocation = call,
                dispatch = dispatch,
                relation = fact,
                ownership = ownership(),
            )
            .stored()

    fun capturedSite(): ValueSite {
        val body = callbackBody()
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    frame = frame,
                    invocationOwner = RelationCallableBody.Named.fromCompiler(base.evidence).stored(),
                    position = ValueArgumentPosition.parse(0).stored(),
                    parameter = RelationOccurrence.fromSource(base.file, range(320, 330)),
                )
                .stored()
        val invoked =
            CallbackParameterInvocation.fromCompiler(
                    RelationOccurrence.fromSource(base.file, range(350, 360)),
                    RelationCallableBody.Named.fromCompiler(concrete.evidence).stored(),
                )
                .stored()
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    basis = base.lease.identity,
                    body = body,
                    binding = CallbackBindingEvidence.Bound(binding),
                    invocations = listOf(invoked),
                    obligations = emptySet(),
                    scan = CallbackInvocationScan.EXHAUSTIVE,
                )
                .stored()
        val owner =
            ValueExecutionOwner.callback(base.owner, body, CompleteCallbackFlowGraph.admit(flow).stored()).stored()
        return ValueSite.fromCompiler(
                enclosing = base.owner,
                range = range(40, 50),
                role = ValueRole.ExpressionResult,
                executionOwner = owner,
            )
            .stored()
    }

    private fun callbackBody(): RelationCallableBody.Anonymous {
        val bodyRange = range(30, 70)
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(base.file, bodyRange),
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = emptyList(),
                    rawTypeParameterCount = 0,
                )
                .stored() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(base.file, bodyRange, signature).stored()
    }

    private fun endpoint(name: String, start: Int, end: Int): RelationEndpoint.Resolved {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "fixture.$name",
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("()->Unit"),
                    rawTypeParameterCount = 0,
                )
                .stored()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file = base.file,
                    rawStartInclusive = start,
                    rawEndExclusive = end,
                    rawName = name.substringAfterLast('.'),
                    rawQualifiedIdentity = "fixture.$name",
                    kind = CompilerSymbolKind.FUNCTION,
                    signature = signature,
                )
                .stored()
        return RelationEndpoint.resolve(base.lease, base.scope, evidence).stored()
    }

    private fun ownership(): ValueSourceOwnership {
        val path = Path.of("/fixture")
        val compiled =
            WorkspaceSearchScopeModel.compile(
                base.lease.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = "fixture",
                        linkedBuildRoot = path,
                        gradleProjectPath = ":",
                        sourceSetName = "main",
                        sourceRoot = path,
                        sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled
        return ValueSourceOwnership.fromCompiler(concrete, compiled.model.sourceRoots.single()).stored()
    }
}

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).stored()

private fun <T, F> Refinement<T, F>.stored(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Source-frame storage fixture rejected: $failure")
    }
