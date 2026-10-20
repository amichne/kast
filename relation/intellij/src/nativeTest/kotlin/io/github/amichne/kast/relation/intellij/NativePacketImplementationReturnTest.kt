package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import kotlinx.coroutines.runBlocking

/** The frozen echo override supplies a real nonempty implementation frame and a distinct constant counterexample. */
class NativePacketImplementationReturnTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-implementation-return-jdk"

    fun testEchoReturnPopsOnlyItsFreshEquivalentImplementationFrame() {
        val fixture = fixture()
        val case = echoReturn(fixture)
        val frame = case.returned.callStack.frames.single()
        assertTrue(frame.target is ValueCallTarget.Implementation)
        val read = step(fixture, case.returned)
        assertEquals(listOf(ValueTransferKind.SOURCE_CALL_RETURN), read.transfers.map { it.kind })
        assertEquals(emptyList<Any>(), read.obligations)
        val transfer = read.transfers.single()
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.Return
        assertEquals(frame, operation.frame)
        assertEquals(frame.invocation.range, transfer.target.range)
        assertEquals(frame.invocation.enclosing.valueIdentity, transfer.target.enclosing.valueIdentity)
        assertEquals(frame.invocation.executionOwner, transfer.target.executionOwner)
        assertEquals(emptyList<Any>(), transfer.target.callStack.frames)
        assertEquals(case.returned.valuePath, transfer.target.valuePath)
    }

    fun testEchoReturnRejectsTheConstantImplementationFrame() {
        val fixture = fixture()
        val case = echoReturn(fixture)
        val wrongStack = ValueCallStack.Empty.enter(case.constant).nativeRefined()
        val wrong = case.returned.withState(callStack = wrongStack).nativeRefined()
        val read = step(fixture, wrong)
        assertEquals(emptyList<Any>(), read.transfers)
        assertEquals(listOf(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN), read.obligations.map { it.cause })
        assertEquals(listOf(wrong.identity), read.obligations.map { it.site.identity })
        assertEquals(listOf(case.constant), wrong.callStack.frames)
    }

    private fun fixture(): NativePacketValueFixture =
        packetFixture(listOf(DEFAULT, TEST_ECHO), SymbolSourceKindPolicy.PRODUCTION_AND_TEST)

    private fun echoReturn(fixture: NativePacketValueFixture): EchoReturnCase {
        val selection =
            fixture.selection(
                owner = PacketDeclaration(DEFAULT, "DefaultIdentityControls", "apply"),
                callee = "DispatchRequest",
                callable = PacketDeclaration(DEFAULT, "DispatchRequest", "<init>"),
            )
        val seeded = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = selection, model = fixture.model)
        }
        assertTrue(seeded is ValueProducerSeedRead.Seeded)
        var argument = (seeded as ValueProducerSeedRead.Seeded).seed.site
        for (kind in
            listOf(
                ValueTransferKind.LOCAL_BINDING,
                ValueTransferKind.LOCAL_READ,
                ValueTransferKind.ARGUMENT,
            )) argument = next(fixture, argument, kind)
        val entered = step(fixture, argument)
        assertEquals(
            listOf(ValueTransferKind.SOURCE_CALL_ENTRY, ValueTransferKind.SOURCE_CALL_ENTRY),
            entered.transfers.map { it.kind },
        )
        assertEquals(emptyList<Any>(), entered.obligations)
        val echo = entered.transfers.single { identity(it.target) == ECHO }.target
        val constant = entered.transfers.single { identity(it.target) == CONSTANT }.target.callStack.frames.single()
        assertEquals((argument.role as ValueRole.Argument).call, echo.callStack.frames.single().invocation)
        val read = next(fixture, echo, ValueTransferKind.LOCAL_READ)
        return EchoReturnCase(next(fixture, read, ValueTransferKind.RETURN), constant)
    }

    private fun next(fixture: NativePacketValueFixture, site: ValueSite, kind: ValueTransferKind): ValueSite {
        val read = step(fixture, site)
        assertEquals("Frontier=${read.obligations.map { it.cause }}", listOf(kind), read.transfers.map { it.kind })
        assertEquals(emptyList<Any>(), read.obligations)
        return read.transfers.single().target
    }

    private fun step(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowStep {
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Implementation return failed: $read", read is ValueFlowRead.Observed)
        return (read as ValueFlowRead.Observed).step
    }

    private fun identity(site: ValueSite) =
        (site.enclosing.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value

    private data class EchoReturnCase(val returned: ValueSite, val constant: ValueCallFrame)

    private companion object {
        const val DEFAULT = "src/main/kotlin/fixture/controls/DefaultIdentityControls.kt"
        const val TEST_ECHO = "src/test/kotlin/fixture/controls/TestEchoMapper.kt"
        const val CONSTANT = "fixture.controls.ProductionConstantMapper.identity"
        const val ECHO = "fixture.controls.TestEchoMapper.identity"
    }
}
