package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackForwardingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackParameterForwarding
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CompleteCallbackForwardingGraph
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real detached records and accounting rules; no native compiler, filesystem, or process execution. */
class CallbackSummaryStorageTest {
    private val fixture = SummaryStorageFixture()

    @Test
    fun `two alpha suppliers share an admitted three formal graph within the transport allowance`() {
        val counts = StorageCounts()
        val summaries = CallbackParameterSummaries(fixture.budget(), counts)
        val reuse = CallbackSummaryReuse(summaries)
        val (firstBody, firstBinding) = fixture.supplier(20)
        assertEquals(
            CallbackSummaryRestore.Missing,
            reuse.restore(fixture.root, firstBody, CallbackBindingEvidence.Bound(firstBinding)).refined(),
        )
        fixture.stage(summaries.retention)
        val summary = fixture.summary()
        val candidate =
            reuse
                .capture(
                    summary.formal,
                    summary.invocations,
                    summary.obligations,
                    summary.ownerBindings,
                    summary.scan,
                    summary.forwarding,
                )
                .refined() as CallbackSummaryCandidate.Admitted
        val first = candidate.summary.instantiate(firstBody, firstBinding).refined()
        assertEquals(
            CallbackInvocationFlowRead.Observed(first),
            reuse.publish(CallbackInvocationFlowRead.Observed(first), emptyList(), candidate),
        )
        assertSame(candidate.summary, summaries.find(fixture.root))
        val (secondBody, secondBinding) = fixture.supplier(90)
        val restored =
            reuse.restore(fixture.root, secondBody, CallbackBindingEvidence.Bound(secondBinding)).refined()
                as CallbackSummaryRestore.Reused
        assertEquals(secondBody, restored.flow.body)
        assertEquals(CallbackBindingEvidence.Bound(secondBinding), restored.flow.binding)
        assertEquals(CallbackInvocationScan.EXHAUSTIVE, restored.flow.scan)
        assertTrue(restored.flow.obligations.isEmpty())
        assertSame(
            (first.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph,
            (restored.flow.forwarding as CallbackForwardingEvidence.ExhaustedGraph).graph,
        )
        counts.assertReuseSequence()
        repeat(3) { summaries.retention.admit(0L).refined() }
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.RESULT_LIMIT_REACHED),
            summaries.retention.admit(0L),
        )
    }

    @Test
    fun `an admitted graph summary only consumes its reference storage allowance`() {
        val summary = fixture.summary()
        assertTrue(summary.retainedBytes > 5000L)
        val summaries = CallbackParameterSummaries(fixture.budget(fixture.stagedBytes + 5000L))
        fixture.stage(summaries.retention)
        summaries.retain(summary).refined()
        assertSame(summary, summaries.find(fixture.root))
    }

    @Test
    fun `equal rebuilt formals invocations and forwarding edges do not inherit storage admission`() {
        val rebuiltRoot =
            CallbackParameterIdentity.fromCompiler(
                    fixture.root.callable,
                    fixture.root.position,
                    fixture.root.parameter,
                )
                .refined()
        val rebuiltInvocation =
            CallbackParameterInvocation.fromCompiler(
                    fixture.invocation.occurrence,
                    fixture.invocation.owner,
                    fixture.invocation.callableTransfers,
                    fixture.invocation.forwardings,
                )
                .refined()
        val originalEdge = fixture.forwardings.first()
        val rebuiltEdge =
            CallbackParameterForwarding.fromCompiler(
                    originalEdge.source,
                    originalEdge.argument,
                    originalEdge.target,
                )
                .refined()
        assertEquals(fixture.root, rebuiltRoot)
        assertNotSame(fixture.root, rebuiltRoot)
        assertEquals(fixture.invocation, rebuiltInvocation)
        assertNotSame(fixture.invocation, rebuiltInvocation)
        assertEquals(originalEdge, rebuiltEdge)
        assertNotSame(originalEdge, rebuiltEdge)
        val rebuilt =
            listOf(
                fixture.summary(root = rebuiltRoot),
                fixture.summary(invocation = rebuiltInvocation),
                fixture.summary(edges = listOf(rebuiltEdge, fixture.forwardings.last())),
            )
        for (summary in rebuilt) {
            assertTrue(summary.retainedBytes > 5000L)
            val summaries = CallbackParameterSummaries(fixture.budget(fixture.stagedBytes + 5000L))
            fixture.stage(summaries.retention)
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
                summaries.retain(summary),
            )
            assertNull(summaries.find(summary.formal))
        }
    }

    @Test
    fun `another retention owner cannot lend its admitted records to an uncharged summary`() {
        val summary = fixture.summary()
        val original = CallbackParameterSummaries(fixture.budget())
        fixture.stage(original.retention)
        val foreign = CallbackParameterSummaries(fixture.budget(summary.retainedBytes - 1L))
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
            foreign.retain(summary),
        )
        assertNull(foreign.find(summary.formal))
        original.retain(summary).refined()
        assertSame(summary, original.find(summary.formal))
    }

    @Test
    fun `an unadmitted large standalone summary retains its full storage requirement`() {
        val target = fixture.endpoint("large", 0, 100, parameter = "x".repeat(40_000))
        val formal = fixture.formal(target)
        val summary =
            CallbackParameterSummary.fromCompiler(
                    formal,
                    emptyList(),
                    emptySet(),
                    scan = CallbackInvocationScan.EXHAUSTIVE,
                )
                .refined()
        assertTrue(summary.retainedBytes > 65_536L)
        val summaries = CallbackParameterSummaries(fixture.budget())
        assertEquals(
            Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
            summaries.retain(summary),
        )
        assertNull(summaries.find(formal))
    }

    @Test
    fun `large signature and hidden constraint storage reject formal and forwarding admission`() {
        val largeSignature = fixture.endpoint("large", 0, 100, parameter = "x".repeat(40_000))
        val constraints =
            SymbolDiscoveryConstraints(
                null,
                SymbolDiscoveryPackageConstraint(
                    SymbolDiscoveryPackage.parse("p".repeat(40_000)).refined(),
                    SymbolDiscoveryContainment.DIRECT,
                ),
            )
        val hidden =
            RelationEndpoint.resolve(
                    fixture.root.callable.lease,
                    fixture.root.callable.scope,
                    (fixture.root.callable as RelationEndpoint.Resolved).evidence,
                    constraints,
                )
                .refined()
        assertEquals(fixture.root.callable.signature, hidden.signature)
        assertEquals(fixture.root.callable.compilerIdentity, hidden.compilerIdentity)
        for (target in listOf(largeSignature, hidden)) {
            val formal = fixture.formal(target)
            val retention = CallbackFlowRetention(fixture.budget())
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
                retention.admitFormal(formal),
            )
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.BYTE_LIMIT_REACHED),
                retention.admitForwarding(fixture.forward(fixture.root, formal, 50)),
            )
            retention.admit(1L).refined()
        }
    }
}

private class SummaryStorageFixture {
    private val request = RelationReadTest().request(RelationMeaning.Callees)
    private val lease = request.subject.lease
    private val forwardingFile = file("forwarding/src/main/kotlin/fixture/staticcallbacks/forwarding/Forwarding.kt")
    private val invocationFile = file("invocation/src/main/kotlin/fixture/staticcallbacks/invocation/Invocation.kt")
    private val supplier =
        endpoint(
            "alphaEntry",
            0,
            180,
            file = file("suppliers/src/main/kotlin/fixture/staticcallbacks/suppliers/Suppliers.kt"),
        )
    val root = formal(endpoint("sharedWrapper", 0, 100))
    private val middle = formal(endpoint("forwardOnce", 200, 300))
    private val terminal = formal(endpoint("invokeCallback", 0, 100, file = invocationFile))
    val forwardings = listOf(forward(root, middle, 50), forward(middle, terminal, 250))
    val invocation =
        CallbackParameterInvocation.fromCompiler(
                occurrence(terminal.callable, 60, 70),
                RelationCallableBody.Named.fromCompiler((terminal.callable as RelationEndpoint.Resolved).evidence)
                    .refined(),
                forwardings = forwardings,
            )
            .refined()
    val stagedBytes = root.retainedBytes + forwardings.sumOf { it.retainedBytes } + invocation.retainedBytes

    fun budget(bytes: Long = 65_536L) =
        RelationBudget(
            request.budget.resources.copy(resultLimit = ResultLimit.parse(8).refined()),
            RelationByteLimit.parse(bytes).refined(),
        )

    fun stage(retention: CallbackFlowRetention) {
        retention.admitFormal(root).refined()
        forwardings.forEach { retention.admitForwarding(it).refined() }
        retention.admitInvocation(invocation).refined()
    }

    fun summary(
        root: CallbackParameterIdentity = this.root,
        invocation: CallbackParameterInvocation = this.invocation,
        edges: List<CallbackParameterForwarding> = forwardings,
    ): CallbackParameterSummary {
        val graph =
            CompleteCallbackForwardingGraph.fromCompiler(
                    root,
                    listOf(root, middle, terminal),
                    edges,
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .refined()
        return CallbackParameterSummary.fromCompiler(
                root,
                listOf(invocation),
                emptySet(),
                scan = CallbackInvocationScan.EXHAUSTIVE,
                forwarding = CallbackForwardingEvidence.ExhaustedGraph(graph),
            )
            .refined()
    }

    fun supplier(start: Int): Pair<RelationCallableBody.Anonymous, CallbackArgumentBinding> {
        val range = ExactDeclarationTextRange.parse(start, start + 20).refined()
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(supplier.file, range),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .refined() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(supplier.file, range, signature).refined()
        val callRange = ExactDeclarationTextRange.parse(start - 5, start + 25).refined()
        val invocation = ValueInvocation.fromCompiler(supplier, callRange, root.callable).refined()
        val owner = RelationCallableBody.Named.fromCompiler(supplier.evidence).refined()
        return body to CallbackArgumentBinding.fromCompiler(invocation, owner, root.position, root.parameter).refined()
    }

    fun formal(endpoint: RelationEndpoint) =
        CallbackParameterIdentity.fromCompiler(
                endpoint,
                ValueArgumentPosition.parse(0).refined(),
                occurrence(endpoint, endpoint.range.startInclusive + 2, endpoint.range.startInclusive + 15),
            )
            .refined()

    fun forward(
        source: CallbackParameterIdentity,
        target: CallbackParameterIdentity,
        start: Int,
    ): CallbackParameterForwarding {
        val invocation =
            ValueInvocation.fromCompiler(
                    source.callable,
                    ExactDeclarationTextRange.parse(start, start + 30).refined(),
                    target.callable,
                )
                .refined()
        val owner =
            RelationCallableBody.Named.fromCompiler((source.callable as RelationEndpoint.Resolved).evidence).refined()
        val binding =
            CallbackArgumentBinding.fromCompiler(invocation, owner, target.position, target.parameter).refined()
        return CallbackParameterForwarding.fromCompiler(
                source,
                occurrence(source.callable, start + 5, start + 10),
                binding,
            )
            .refined()
    }

    fun endpoint(
        name: String,
        start: Int,
        end: Int,
        parameter: String = "kotlin.Function0<kotlin.String>",
        file: SymbolDiscoveryFileIdentity = forwardingFile,
    ): RelationEndpoint.Resolved {
        val identity = "fixture.staticcallbacks.${if (file == invocationFile) "invocation" else "forwarding"}.$name"
        val signature = CanonicalCompilerSignature.function(identity, null, emptyList(), listOf(parameter), 0).refined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    start,
                    end,
                    name,
                    identity,
                    CompilerSymbolKind.FUNCTION,
                    signature,
                )
                .refined()
        return RelationEndpoint.resolve(lease, request.searchScope, evidence).refined()
    }

    private fun occurrence(endpoint: RelationEndpoint, start: Int, end: Int) =
        RelationOccurrence.fromBoundary(endpoint.file, start, end).refined()

    private fun file(relative: String) =
        SymbolDiscoveryFileIdentity.Workspace(
            CanonicalWorkspaceFilePath.fromCanonicalPath(
                    lease.workspaceRoot,
                    Path.of("/workspace/static-callback-fixture/$relative"),
                )
                .refined()
        )
}

private class StorageCounts : IntellijReadObservation {
    val values = mutableListOf<IntellijReadCounter>()

    override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
        assertEquals(IntellijReadContributor.NONE, contributor)
        repeat(amount) { values += counter }
    }

    fun assertReuseSequence() {
        assertEquals(
            listOf(
                IntellijReadCounter.CALLBACK_SUMMARY_MISSES,
                IntellijReadCounter.CALLBACK_SUMMARIES_RETAINED,
                IntellijReadCounter.CALLBACK_SUMMARY_HITS,
            ),
            values,
        )
    }

    override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
        error("Unexpected storage termination: $reason")
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
