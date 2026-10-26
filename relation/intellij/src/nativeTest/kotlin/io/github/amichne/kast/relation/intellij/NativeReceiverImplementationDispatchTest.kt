package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueImplementationCoverage
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import kotlinx.coroutines.runBlocking

/** Native receiver binding and implementation inventory must preserve the same matched call. */
class NativeReceiverImplementationDispatchTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "receiver-implementation-dispatch-jdk"

    fun testAbstractDispatchReceiverEntersEachExactImplementationAndReturnsToItsCall() {
        val fixture = packetFixture(listOf(MAIN))
        assertImplementations(
            fixture = fixture,
            caller = "dispatchReceiver",
            kind = ValueReceiverKind.DISPATCH,
            owners = listOf("FirstReceiverResult", "SecondReceiverResult"),
            name = "preserve",
        )
    }

    fun testAbstractMemberExtensionKeepsTheExtensionSlotSeparateFromImplicitDispatch() {
        val fixture = packetFixture(listOf(MAIN))
        assertImplementations(
            fixture = fixture,
            caller = "extensionReceiver",
            kind = ValueReceiverKind.EXTENSION,
            owners = listOf("FirstReceiverScope", "SecondReceiverScope"),
            name = "preserveExtension",
        )
    }

    fun testProductionScopeRetainsUnknownDispatchWhenOnlyTestImplementationExists() {
        val fixture = packetFixture(listOf(MAIN, TEST), SymbolSourceKindPolicy.PRODUCTION_ONLY)
        val source = receiver(fixture, "testOnlyReceiver", ValueReceiverKind.DISPATCH)
        val step = step(fixture, source)
        assertTrue(step.transfers.isEmpty())
        val obligation = step.obligations.single()
        assertEquals(ValueFlowUnsupportedCause.UNMODELED_CALL, obligation.cause)
        val dispatch = (obligation.evidence as ValueFlowObligationEvidence.ImplementationDispatch).dispatch
        assertEquals(SymbolSourceKindPolicy.PRODUCTION_ONLY, dispatch.batch.request.searchScope.sourceKinds)
        assertEquals(
            (source.role as ValueRole.Receiver).call.callable.valueIdentity,
            dispatch.batch.request.subject.valueIdentity,
        )
        assertTrue(dispatch.batch.facts.isEmpty())
        assertTrue(dispatch.coverage is ValueImplementationCoverage.Exhausted)
    }

    fun testExplicitExtensionOccurrenceCannotAcquireImplicitDispatchProvenance() {
        val fixture = packetFixture(listOf(MAIN))
        val source = receiver(fixture, "extensionReceiver", ValueReceiverKind.EXTENSION)
        val role = source.role as ValueRole.Receiver
        val wrongSlot =
            ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = source.range,
                    role = ValueRole.Receiver(role.call, ValueReceiverKind.DISPATCH),
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                )
                .nativeRefined()
        assertUnboundBeforeSearch(fixture, wrongSlot)
    }

    fun testAConcreteOverrideCannotReplaceTheCompilerSelectedStaticCallable() {
        val fixture = packetFixture(listOf(MAIN))
        val source = receiver(fixture, "dispatchReceiver", ValueReceiverKind.DISPATCH)
        val role = source.role as ValueRole.Receiver
        val concrete = fixture.callableDeclaration(PacketDeclaration(MAIN, "FirstReceiverResult", "preserve"))
        val wrongCall =
            ValueInvocation.fromCompiler(
                    enclosing = role.call.enclosing,
                    range = role.call.range,
                    callable = RelationEndpoint.subject(concrete),
                    executionOwner = role.call.executionOwner,
                )
                .nativeRefined()
        val wrongTarget =
            ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = source.range,
                    role = ValueRole.Receiver(wrongCall, role.kind),
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                )
                .nativeRefined()
        assertUnboundBeforeSearch(fixture, wrongTarget)
    }

    fun testCombinedScopeAdmitsTheExactTestImplementationAndMatchedReturn() {
        val fixture = packetFixture(listOf(MAIN, TEST), SymbolSourceKindPolicy.PRODUCTION_AND_TEST)
        val source = receiver(fixture, "testOnlyReceiver", ValueReceiverKind.DISPATCH)
        val entered = step(fixture, source)
        assertTrue(entered.obligations.isEmpty())
        val transfer = entered.transfers.single()
        assertEquals(ValueTransferKind.SOURCE_CALL_ENTRY, transfer.kind)
        val frame = transfer.target.callStack.frames.single()
        val target = frame.target as ValueCallTarget.Implementation
        assertEquals(WorkspaceSourceRootKind.TEST, target.ownership.root.sourceKind)
        assertEquals(target.relation.source.valueIdentity, target.ownership.declaration)
        assertMatchedReturn(fixture, source, transfer.target)
    }

    fun testOpenDefaultBodyRemainsExplicitlyUnsupported() {
        val fixture = packetFixture(listOf(MAIN))
        val source = receiver(fixture, "defaultReceiver", ValueReceiverKind.DISPATCH)
        val step = step(fixture, source)
        assertTrue(step.transfers.isEmpty())
        assertEquals(listOf(ValueFlowUnsupportedCause.UNMODELED_CALL), step.obligations.map { it.cause })
        assertTrue(step.obligations.single().evidence is ValueFlowObligationEvidence.ImplementationDispatch)
    }

    fun testFinalDirectReceiverRetainsItsDirectFrameAndMatchedReturn() {
        val fixture = packetFixture(listOf(MAIN))
        val source = receiver(fixture, "finalReceiver", ValueReceiverKind.DISPATCH)
        val entered = step(fixture, source)
        assertTrue(entered.obligations.isEmpty())
        val transfer = entered.transfers.single()
        assertEquals(ValueTransferKind.SOURCE_CALL_ENTRY, transfer.kind)
        assertEquals(ValueCallTarget.Direct, transfer.target.callStack.frames.single().target)
        assertMatchedReturn(fixture, source, transfer.target)
    }

    private fun assertImplementations(
        fixture: NativePacketValueFixture,
        caller: String,
        kind: ValueReceiverKind,
        owners: List<String>,
        name: String,
    ) {
        val source = receiver(fixture, caller, kind)
        val entered = step(fixture, source)
        assertTrue("Receiver dispatch retained obligations: ${entered.obligations}", entered.obligations.isEmpty())
        assertEquals(List(owners.size) { ValueTransferKind.SOURCE_CALL_ENTRY }, entered.transfers.map { it.kind })
        val expected =
            owners
                .map { owner ->
                    RelationEndpoint.subject(fixture.callableDeclaration(PacketDeclaration(MAIN, owner, name)))
                        .valueIdentity
                }
                .toSet()
        assertEquals(expected, entered.transfers.map { it.target.enclosing.valueIdentity }.toSet())
        for (transfer in entered.transfers) {
            assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(kind)), transfer.target.role)
            assertEquals(source.valuePath, transfer.target.valuePath)
            val frame = transfer.target.callStack.frames.single()
            assertEquals((source.role as ValueRole.Receiver).call, frame.invocation)
            val target = frame.target as ValueCallTarget.Implementation
            assertTrue(target.dispatch.coverage is ValueImplementationCoverage.Exhausted)
            assertEquals(target.relation.source.valueIdentity, frame.callee.enclosing.valueIdentity)
            assertMatchedReturn(fixture, source, transfer.target)
        }
    }

    private fun assertUnboundBeforeSearch(fixture: NativePacketValueFixture, source: ValueSite) {
        var implementationSearches = 0
        val observation =
            object : IntellijReadObservation by IntellijReadObservation.None {
                override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
                    if (call == IntellijReadCall.DEFINITION_SEARCH) implementationSearches++
                    return IntellijReadCallScope.None
                }
            }
        val read = step(fixture, source, observation)
        assertTrue(read.transfers.isEmpty())
        assertTrue(read.obligations.isNotEmpty())
        assertTrue(read.obligations.all { it.cause == ValueFlowUnsupportedCause.UNMODELED_CALL })
        assertEquals(0, implementationSearches)
    }

    private fun assertMatchedReturn(fixture: NativePacketValueFixture, receiver: ValueSite, formal: ValueSite) {
        var site = formal
        for (kind in
            listOf(ValueTransferKind.LOCAL_READ, ValueTransferKind.RETURN, ValueTransferKind.SOURCE_CALL_RETURN)) {
            val read = step(fixture, site)
            assertTrue("Matched receiver route retained obligations: ${read.obligations}", read.obligations.isEmpty())
            assertEquals(listOf(kind), read.transfers.map { it.kind })
            site = read.transfers.single().target
        }
        assertEquals((receiver.role as ValueRole.Receiver).call.resultSite().identity, site.identity)
        assertEquals(receiver.valuePath, site.valuePath)
        assertEquals(receiver.callStack, site.callStack)
    }

    private fun receiver(fixture: NativePacketValueFixture, name: String, kind: ValueReceiverKind): ValueSite {
        val selected = fixture.markedExpression(PacketDeclaration(MAIN, "", name), "receiver")
        val source =
            ValueSite.fromCompiler(RelationEndpoint.subject(selected.enclosing), selected.anchor, ValueRole.LocalRead)
                .nativeRefined()
        val read = step(fixture, source)
        assertTrue(read.obligations.isEmpty())
        assertEquals(listOf(ValueTransferKind.RECEIVER), read.transfers.map { it.kind })
        return read.transfers.single().target.also { assertEquals(kind, (it.role as ValueRole.Receiver).kind) }
    }

    private fun step(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): ValueFlowStep {
        // Implementation-qualified frames retain their complete dispatch evidence during local reads and returns.
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(MAX_READ_BYTES).nativeRefined())
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter(observation)
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue(
            "Receiver fixture read failed with ${source.retainedBytes} retained bytes" +
                " and $MAX_READ_BYTES granted bytes: $read",
            read is ValueFlowRead.Observed,
        )
        return (read as ValueFlowRead.Observed).step
    }

    private companion object {
        const val MAIN = "src/main/kotlin/fixture/controls/ReceiverDispatchControls.kt"
        const val TEST = "src/test/kotlin/fixture/controls/TestReceiverDispatch.kt"
        const val MAX_READ_BYTES = 16_000_000L
    }
}
