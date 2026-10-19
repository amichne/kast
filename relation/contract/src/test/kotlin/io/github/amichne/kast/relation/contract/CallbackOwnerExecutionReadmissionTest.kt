package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.MovingLiveReadAuthorityFixture
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackOwnerExecutionReadmissionTest {
    private val case = CallbackInvocationFlowFixture()
    private val owner = MovingLiveReadAuthorityFixture(case.root)

    @Test
    fun `nested execution dependencies include hidden forwarding endpoints and source files`() {
        val frame = Frame(owner.admit())
        val summary = frame.summary(frame.sourceFlow())
        assertEquals(setOf(frame.target, frame.receiver, frame.leaf), summary.requiredEndpoints().toSet())
        assertEquals(setOf(case.file, frame.leaf.file), summary.requiredSourceFiles())
    }

    @Test
    fun `readmission replaces nested execution authority and rejects an omitted inner endpoint`() {
        val frame = Frame(owner.admit())
        val summary = frame.summary(frame.sourceFlow())
        val current = owner.advance()
        val replacements: Map<RelationEndpoint, RelationEndpoint.Resolved> =
            listOf(frame.target, frame.receiver, frame.leaf).associate {
                it to RelationEndpoint.resolve(current, it.scope, it.evidence, it.constraints).refined()
            }
        val incomplete =
            CallbackEndpointReadmissions.fromCompiler(current, replacements - frame.leaf).refined().readmit(summary)
        assertEquals(
            CallbackSummaryReadmissionFailure.MissingEndpoint(frame.leaf.valueIdentity),
            (incomplete as Refinement.Rejected).failure,
        )
        val restored =
            CallbackEndpointReadmissions.fromCompiler(current, replacements).refined().readmit(summary).refined()
        val flow = (restored.ownerBindings.single().execution as CallbackOwnerExecution.Observed).flow
        assertEquals(current.identity, flow.basis)
        assertEquals(current.identity, (flow.binding as CallbackBindingEvidence.Bound).binding.invocation.basis)
        assertEquals(current.identity, flow.invocations.single().forwardings.single().target.invocation.basis)
        assertEquals(summary.invocations, restored.invocations)
        assertEquals(emptySet<CallbackInvocationFlowCause>(), restored.ownerBindings.single().obligations)
        assertNotEquals(summary.ownerBindings.single(), restored.ownerBindings.single())
    }

    @Test
    fun `canonical provenance and retained bytes include the full owner execution`() {
        val frame = Frame(owner.admit())
        val first = frame.mapping.withExecution(frame.sourceFlow(560)).refined()
        val second = frame.mapping.withExecution(frame.sourceFlow(570)).refined()
        assertNotEquals(first.canonicalProjection(), second.canonicalProjection())
        assertNotEquals(frame.mapping.canonicalProjection(), first.canonicalProjection())
        assertTrue(first.retainedBytes > frame.mapping.retainedBytes)
    }

    @Test
    fun `a dependency inside nested execution needs a fresh contract read`() {
        val frame = Frame(owner.admit())
        val flow = frame.dependencyFlow()
        val summary = frame.summary(flow)
        val current = owner.advance()
        val replacements: Map<RelationEndpoint, RelationEndpoint.Resolved> =
            listOf(frame.target, frame.receiver).associate {
                it to RelationEndpoint.resolve(current, it.scope, it.evidence, it.constraints).refined()
            }
        assertEquals(Refinement.Refined(Unit), flow.admitCallbackWorkspace(case.root))
        assertTrue(summary.requiredSourceFiles().any { it is SymbolDiscoveryFileIdentity.External })
        assertEquals(
            Refinement.Rejected(CallbackSummaryReadmissionFailure.DependencyContractNeedsFreshRead),
            CallbackEndpointReadmissions.fromCompiler(current, replacements).refined().readmit(summary),
        )
    }

    @Test
    fun `an inner source endpoint outside the workspace cannot enter the proof`() {
        val frame = Frame(owner.admit(), "/foreign")
        assertEquals(
            Refinement.Rejected(StaticCallbackGraphFailure.OutsideWorkspace),
            frame.sourceFlow().admitCallbackWorkspace(case.root),
        )
    }

    @Test
    fun `immutable owner readmission restores every transport authority without restoring discharged causes`() {
        val frame = Frame(owner.admit())
        val previous = frame.immutableSummary()
        val current = owner.advance()
        val replacement = RelationEndpoint.resolve(current, frame.target.scope, frame.target.evidence).refined()
        val restored =
            CallbackEndpointReadmissions.fromCompiler(current, mapOf(frame.target to replacement))
                .refined()
                .readmit(previous)
                .refined()
        val binding = restored.ownerBindings.single()
        val execution = binding.execution as CallbackOwnerExecution.Immutable
        val use = execution.flow.uses.single() as ImmutableCallbackInvocationUse.Direct
        assertEquals(setOf(frame.target), previous.requiredEndpoints().toSet())
        assertEquals(setOf(case.file), previous.requiredSourceFiles())
        assertEquals(emptySet<CompilerGroundedSymbolEvidence>(), previous.requiredCompilerDeclarations())
        assertEquals(current.identity, execution.flow.source.basis)
        assertEquals(current.identity, use.binding.basis)
        assertEquals(current.identity, use.value.source.basis)
        assertEquals(current.identity, use.value.destination.basis)
        assertTrue(
            use.value.transfers.all { it.source.basis == current.identity && it.target.basis == current.identity }
        )
        assertEquals(emptySet<CallbackInvocationFlowCause>(), binding.obligations)
        assertEquals(CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK), binding.binding)
        assertEquals(frame.mapping.body, binding.body)
        assertEquals(RelationCallableBody.Named.fromCompiler(frame.target.evidence).refined(), execution.owner)
        assertEquals(Refinement.Refined(Unit), execution.flow.admitCallbackWorkspace(case.root))
    }

    @Test
    fun `immutable owner provenance includes exact later invocation and transport`() {
        val frame = Frame(owner.admit())
        val first = frame.immutableSummary(350).ownerBindings.single()
        val second = frame.immutableSummary(370).ownerBindings.single()
        assertNotEquals(first.canonicalProjection(), second.canonicalProjection())
        assertNotEquals(first.canonicalProjection(), frame.storedMapping.canonicalProjection())
        assertTrue(first.retainedBytes > frame.storedMapping.retainedBytes)
    }

    private inner class Frame(authority: SemanticReadAuthority, leafRoot: String = "/fixture") {
        val target = restore(case.target, authority)
        val receiver = restore(case.endpoint("receiver", 410, 500, listOf("()->Unit")), authority)
        val leaf =
            restore(
                case.endpoint("leaf", 510, 600, listOf("()->Unit")),
                authority,
                SymbolDiscoveryFileIdentity.Workspace(
                    CanonicalWorkspaceFilePath.fromCanonicalPath(
                            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(leafRoot)).refined(),
                            Path.of(leafRoot, "Leaf.kt"),
                        )
                        .refined()
                ),
            )
        private val binding = argument(target, receiver, 255, 310, 425, 445)
        val mapping =
            CallbackBodyBinding.fromCompiler(
                    case.nested.owner as RelationCallableBody.Anonymous,
                    CallbackBodySupply.Invocation(case.occurrence(255, 310)),
                    CallbackBindingEvidence.Bound(binding),
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                )
                .refined()

        val storedMapping =
            CallbackBodyBinding.fromCompiler(
                    mapping.body,
                    CallbackBodySupply.Stored,
                    CallbackBindingEvidence.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                    setOf(
                        CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION,
                        CallbackInvocationFlowCause.STORED_CALLBACK,
                    ),
                )
                .refined()

        fun immutableSummary(offset: Int = 350): CallbackParameterSummary {
            val source = ValueSite.fromCompiler(target, mapping.body.range, ValueRole.ExpressionResult).refined()
            val local = ValueSite.fromCompiler(target, case.range(315, 325), ValueRole.LocalBinding).refined()
            val read = ValueSite.fromCompiler(target, case.range(offset, offset + 5), ValueRole.LocalRead).refined()
            val origin = ImmutableCallbackValueOrigin.Anonymous(mapping.body)
            val value =
                ImmutableCallbackValue.fromCompiler(
                        origin,
                        source,
                        read,
                        listOf(
                            ValueTransfer.fromCompiler(source, local, ValueTransferKind.LOCAL_BINDING).refined(),
                            ValueTransfer.fromCompiler(local, read, ValueTransferKind.LOCAL_READ).refined(),
                        ),
                    )
                    .refined()
            val named = RelationCallableBody.Named.fromCompiler(target.evidence).refined()
            val direct =
                CallbackDirectInvocationBinding.fromCompiler(
                        target.lease.identity,
                        case.occurrence(offset, offset + 10),
                        named,
                    )
                    .refined()
            val flow =
                ImmutableCallbackInvocationFlow.fromCompiler(
                        origin,
                        source,
                        listOf(ImmutableCallbackInvocationUse.Direct(value, direct)),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .refined()
            return CallbackParameterSummary.fromCompiler(
                    CallbackParameterIdentity.fromCompiler(target, case.binding.position, case.binding.parameter)
                        .refined(),
                    listOf(case.nested),
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    listOf(storedMapping.withExecution(flow, named).refined()),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .refined()
        }

        fun summary(flow: CallbackInvocationFlow): CallbackParameterSummary =
            CallbackParameterSummary.fromCompiler(
                    CallbackParameterIdentity.fromCompiler(target, case.binding.position, case.binding.parameter)
                        .refined(),
                    listOf(case.nested),
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    listOf(mapping.withExecution(flow).refined()),
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .refined()

        fun sourceFlow(offset: Int = 560): CallbackInvocationFlow {
            val forwarding =
                CallbackParameterForwarding.fromCompiler(
                        CallbackParameterIdentity.fromCompiler(receiver, binding.position, binding.parameter).refined(),
                        case.occurrence(460, 465),
                        argument(receiver, leaf, 450, 480, 525, 545),
                    )
                    .refined()
            val invocation =
                CallbackParameterInvocation.fromCompiler(
                        RelationOccurrence.fromBoundary(leaf.file, offset, offset + 5).refined(),
                        RelationCallableBody.Named.fromCompiler(leaf.evidence).refined(),
                        forwardings = listOf(forwarding),
                    )
                    .refined()
            return flow(listOf(invocation), emptySet())
        }

        fun dependencyFlow(): CallbackInvocationFlow {
            val body = case.anonymous(460, 490)
            val external =
                evidence(
                    receiver.evidence,
                    SymbolDiscoveryFileIdentity.External(
                        DetachedVirtualFileUrl.parse("jar:///library.jar!/Receiver.class").refined()
                    ),
                )
            val dependency =
                CallbackBindingEvidence.DependencyContract(
                    CallbackDependencyContract.fromCompiler(
                            target.lease.identity,
                            case.occurrence(455, 495),
                            RelationCallableBody.Named.fromCompiler(receiver.evidence).refined(),
                            external,
                            ValueArgumentPosition.parse(0).refined(),
                            DependencyClassDigest.parse("a".repeat(64)).refined(),
                            CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                            CallbackDependencyInvocationKind.EXACTLY_ONCE,
                        )
                        .refined()
                )
            val childFlow =
                CallbackInvocationFlow.fromCompiler(
                        target.lease.identity,
                        body,
                        dependency,
                        emptyList(),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .refined()
            val child =
                CallbackBodyBinding.fromCompiler(
                        body,
                        CallbackBodySupply.Invocation(case.occurrence(455, 495)),
                        dependency,
                        setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                    )
                    .refined()
                    .withExecution(childFlow)
                    .refined()
            return flow(
                    listOf(CallbackParameterInvocation.fromCompiler(case.occurrence(470, 475), body).refined()),
                    setOf(CallbackInvocationFlowCause.NESTED_CALLBACK_EXECUTION),
                )
                .withOwnerBindings(listOf(child))
                .refined()
        }

        private fun flow(invocations: List<CallbackParameterInvocation>, causes: Set<CallbackInvocationFlowCause>) =
            CallbackInvocationFlow.fromCompiler(
                    target.lease.identity,
                    mapping.body,
                    mapping.binding,
                    invocations,
                    causes,
                    CallbackInvocationScan.EXHAUSTIVE,
                )
                .refined()
    }

    private fun argument(
        owner: RelationEndpoint.Resolved,
        target: RelationEndpoint.Resolved,
        start: Int,
        end: Int,
        parameterStart: Int,
        parameterEnd: Int,
    ) =
        CallbackArgumentBinding.fromCompiler(
                ValueInvocation.fromCompiler(owner, case.range(start, end), target).refined(),
                RelationCallableBody.Named.fromCompiler(owner.evidence).refined(),
                ValueArgumentPosition.parse(0).refined(),
                RelationOccurrence.fromBoundary(target.file, parameterStart, parameterEnd).refined(),
            )
            .refined()

    private fun restore(
        old: RelationEndpoint.Resolved,
        authority: SemanticReadAuthority,
        file: SymbolDiscoveryFileIdentity = old.file,
    ) = RelationEndpoint.resolve(authority, old.scope, evidence(old.evidence, file), old.constraints).refined()

    private fun evidence(old: CompilerGroundedSymbolEvidence, file: SymbolDiscoveryFileIdentity) =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                old.range.startInclusive,
                old.range.endExclusive,
                old.name.value,
                when (val identity = old.qualifiedIdentity) {
                    is ExactDeclarationQualifiedIdentity.Available -> identity.value
                    ExactDeclarationQualifiedIdentity.Unavailable -> null
                },
                old.kind,
                old.signature,
            )
            .refined()

    private fun <V> Refinement<V, *>.refined(): V = (this as Refinement.Refined).value
}
