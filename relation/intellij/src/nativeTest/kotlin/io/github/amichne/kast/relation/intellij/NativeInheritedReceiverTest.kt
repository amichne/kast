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
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Final dispatch types prove inherited body selection without a runtime-target inventory assumption. */
class NativeInheritedReceiverTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "inherited-receiver-jdk"

    fun testFinalDispatchTypeEntersInheritedExtensionAndKeepsItsMatchedReturn() {
        val fixture = packetFixture(listOf(CONTROLS))
        val source = receiver(fixture, "inheritedExtension")
        val formal = enter(fixture = fixture, source = source, owner = "InheritedScope", name = "preserveInherited")
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)), formal.role)
        assertMatchedReturn(fixture, source, formal)
    }

    fun testInheritedOrdinaryMemberUsesTheSameFinalDispatchProof() {
        val fixture = packetFixture(listOf(CONTROLS))
        val source = receiver(fixture, "inheritedMember")
        val formal = enter(fixture = fixture, source = source, owner = "InheritedMember", name = "preserveMember")
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)), formal.role)
        assertMatchedReturn(fixture, source, formal)
    }

    fun testInheritedExtensionCannotReturnTheDistinctDispatchReceiverPayload() {
        val fixture = packetFixture(listOf(CONTROLS))
        val source = receiver(fixture, "inheritedDispatch")
        val formal = enter(fixture = fixture, source = source, owner = "InheritedScope", name = "selectDispatch")
        val read = step(fixture, formal)
        assertTrue(read.transfers.isEmpty())
        assertTrue(read.obligations.isEmpty())
    }

    fun testFinalOverrideNeverEntersTheInheritedDefaultBody() {
        val fixture = packetFixture(listOf(CONTROLS))
        val source = receiver(fixture, "overriddenExtension")
        val formal =
            enter(fixture = fixture, source = source, owner = "ReplacingInheritedScope", name = "preserveInherited")
        val read = step(fixture, formal)
        assertTrue(read.transfers.isEmpty())
        assertTrue(read.obligations.isEmpty())
    }

    fun testInheritedDispatchReceiverReturnsOnlyItsOwnPayload() {
        val fixture = packetFixture(listOf(CONTROLS))
        val caller = fixture.callableDeclaration(PacketDeclaration(CONTROLS, "", "inheritedDispatch"))
        val outerReceiver =
            ValueSite.fromCompiler(
                    RelationEndpoint.subject(caller),
                    caller.range,
                    ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)),
                )
                .nativeRefined()
        val source = next(fixture, outerReceiver, ValueTransferKind.RECEIVER)
        assertEquals(ValueReceiverKind.DISPATCH, (source.role as ValueRole.Receiver).kind)
        val formal = enter(fixture = fixture, source = source, owner = "InheritedScope", name = "selectDispatch")
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)), formal.role)
        assertMatchedReturn(fixture, source, formal)
    }

    fun testInheritedExplicitExtensionCannotBecomeTheImplicitDispatchReceiver() {
        val fixture = packetFixture(listOf(CONTROLS))
        val source = receiver(fixture, "inheritedExtension")
        val call = (source.role as ValueRole.Receiver).call
        val wrongSlot =
            ValueSite.fromCompiler(
                    enclosing = source.enclosing,
                    range = source.range,
                    role = ValueRole.Receiver(call, ValueReceiverKind.DISPATCH),
                    executionOwner = source.executionOwner,
                    valuePath = source.valuePath,
                    callStack = source.callStack,
                )
                .nativeRefined()
        val read = step(fixture, wrongSlot)
        assertTrue(read.transfers.isEmpty())
        assertEquals(listOf(ValueFlowUnsupportedCause.UNMODELED_CALL), read.obligations.map { it.cause })
    }

    fun testInterfaceDispatchTypeKeepsItsUnselectedDefaultBodyObligation() = unsupported("interfaceExtension")

    fun testOpenDispatchTypeKeepsItsUnselectedDefaultBodyObligation() = unsupported("openExtension")

    fun testFinalDispatchTypeKeepsOrdinaryArgumentAndMatchedReturn() {
        val fixture = packetFixture(listOf(CONTROLS))
        val source = argument(fixture, "inheritedArgument")
        val formal = enter(fixture = fixture, source = source, owner = "InheritedScope", name = "keepArgument")
        assertTrue((formal.role as ValueRole.Formal).position is ValueFormalPosition.Parameter)
        assertMatchedReturn(fixture, source, formal)
    }

    fun testFinalOverrideCannotReturnIgnoredArgumentFromTheExtensionReceiver() {
        val fixture = packetFixture(listOf(CONTROLS))
        val source = argument(fixture, "overriddenArgument")
        val formal = enter(fixture = fixture, source = source, owner = "ReplacingInheritedScope", name = "keepArgument")
        val read = step(fixture, formal)
        assertTrue(read.transfers.isEmpty())
        assertTrue(read.obligations.isEmpty())
    }

    private fun unsupported(caller: String) {
        val fixture = packetFixture(listOf(CONTROLS))
        val read = step(fixture, receiver(fixture, caller))
        assertTrue(read.obligations.any { it.cause == ValueFlowUnsupportedCause.UNMODELED_CALL })
        assertTrue(read.obligations.all { it.evidence is ValueFlowObligationEvidence.ImplementationDispatch })
        val inherited = fixture.callableDeclaration(PacketDeclaration(CONTROLS, "InheritedScope", "preserveInherited"))
        assertTrue(read.transfers.none { it.target.enclosing.compilerIdentity == inherited.compilerIdentity })
    }

    private fun receiver(fixture: NativePacketValueFixture, caller: String): ValueSite {
        val selected = fixture.markedExpression(PacketDeclaration(CONTROLS, "", caller), "receiver")
        val source =
            ValueSite.fromCompiler(RelationEndpoint.subject(selected.enclosing), selected.anchor, ValueRole.LocalRead)
                .nativeRefined()
        return next(fixture, source, ValueTransferKind.RECEIVER)
    }

    private fun enter(fixture: NativePacketValueFixture, source: ValueSite, owner: String, name: String): ValueSite {
        val formal = next(fixture, source, ValueTransferKind.SOURCE_CALL_ENTRY)
        val expected = fixture.callableDeclaration(PacketDeclaration(CONTROLS, owner, name))
        assertEquals(expected.compilerIdentity, formal.enclosing.compilerIdentity)
        assertEquals(ValueCallTarget.Direct, formal.callStack.frames.single().target)
        assertEquals(invocation(source), formal.callStack.frames.single().invocation)
        return formal
    }

    private fun assertMatchedReturn(fixture: NativePacketValueFixture, source: ValueSite, formal: ValueSite) {
        var site = formal
        for (kind in
            listOf(ValueTransferKind.LOCAL_READ, ValueTransferKind.RETURN, ValueTransferKind.SOURCE_CALL_RETURN)) {
            site = next(fixture, site, kind)
        }
        assertEquals(invocation(source).resultSite().identity, site.identity)
        assertEquals(source.callStack, site.callStack)
        assertEquals(source.valuePath, site.valuePath)
    }

    private fun argument(fixture: NativePacketValueFixture, caller: String): ValueSite {
        val selected = fixture.markedExpression(PacketDeclaration(CONTROLS, "", caller), "argument")
        val source =
            ValueSite.fromCompiler(RelationEndpoint.subject(selected.enclosing), selected.anchor, ValueRole.LocalRead)
                .nativeRefined()
        return next(fixture, source, ValueTransferKind.ARGUMENT)
    }

    private fun invocation(source: ValueSite): ValueInvocation =
        when (val role = source.role) {
            is ValueRole.Argument -> role.call
            is ValueRole.Receiver -> role.call
            else -> error("The fixture needs an argument or receiver arrival")
        }

    private fun next(fixture: NativePacketValueFixture, source: ValueSite, kind: ValueTransferKind): ValueSite {
        val read = step(fixture, source)
        assertTrue("Unexpected inherited receiver obligations: ${read.obligations}", read.obligations.isEmpty())
        assertEquals(listOf(kind), read.transfers.map { it.kind })
        return read.transfers.single().target
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep = runBlocking {
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(16_000_000L).nativeRefined())
        val read =
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        assertTrue("Inherited receiver read failed: $read", read is ValueFlowRead.Observed)
        (read as ValueFlowRead.Observed).step
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/InheritedReceiverControls.kt"
    }
}
