package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.runBlocking

/** Minimal report-equivalent compiler edges; these are not a reproduction of the unavailable enterprise source. */
class NativeAtomicReturnControlTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "atomic-return-controls-jdk"

    fun testForwarderExpressionResultReturnsToItsCompilerResolvedMemberInvocation() {
        assertSourceReturn(PacketDeclaration(CONTROLS, "Forwarder", "forward"), "member")
    }

    fun testExtensionExpressionResultReturnsToItsCompilerResolvedInvocation() {
        assertSourceReturn(PacketDeclaration(CONTROLS, "", "forwardExtension"), "extension")
    }

    fun testTruncatedProducerAnchorRejectsBeforeFlow() {
        val fixture = packetFixture(listOf(CONTROLS))
        val expression = fixture.markedExpression(PacketDeclaration(CONTROLS, "Forwarder", "forward"), "member-seed")
        val truncated =
            expression.copy(
                anchor =
                    ExactDeclarationTextRange.parse(
                            expression.anchor.startInclusive,
                            expression.anchor.endExclusive - 1,
                        )
                        .nativeRefined()
            )
        assertEquals(
            ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.ANCHOR_MISMATCH),
            seed(fixture, truncated),
        )
    }

    fun testStraightLineMutableBindingReachesOnlyItsCompilerAdmittedRead() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = PacketDeclaration(CONTROLS, "", "mutableCase")
        val expression = fixture.markedExpression(owner, "mutable-seed")
        val expected = fixture.argumentExpression(owner, "consumeAtomic", "packet")
        val binding = next(fixture, seeded(fixture, expression), ValueTransferKind.LOCAL_BINDING)
        assertEquals(ValueRole.LocalBinding, binding.role)
        assertTrue(binding.localVersion is ValueLocalVersion.Defined)
        val step = step(fixture, binding)
        assertEquals(listOf(ValueTransferKind.LOCAL_READ), step.transfers.map { it.kind })
        assertEquals(listOf(expected.anchor), step.transfers.map { it.target.range })
        assertEquals(binding.enclosing, step.transfers.single().target.enclosing)
        assertTrue("Straight-line version acquired an obligation: ${step.obligations}", step.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, step.terminal)
    }

    private fun assertSourceReturn(owner: PacketDeclaration, marker: String) {
        val fixture = packetFixture(listOf(CONTROLS))
        val expression = fixture.markedExpression(owner, "$marker-seed")
        val expected = fixture.markedExpression(PacketDeclaration(CONTROLS, "", "${marker}Caller"), "$marker-call")
        val origin = seeded(fixture, expression)
        assertEquals(expression.anchor, origin.range)
        assertEquals(ValueRole.ExpressionResult, origin.role)
        val returned = next(fixture, origin, ValueTransferKind.RETURN)
        assertEquals(ValueRole.Return, returned.role)
        assertEquals(origin.enclosing, returned.enclosing)
        val read = step(fixture, returned)
        assertEquals(listOf(ValueTransferKind.SOURCE_CALL_RETURN), read.transfers.map { it.kind })
        assertTrue("Compiler-resolved return acquired an obligation: ${read.obligations}", read.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, read.terminal)
        val transfer = read.transfers.single()
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.SeedReturn
        assertEquals(returned.executionOwner, operation.frame.callee)
        assertEquals(origin.enclosing.compilerIdentity, operation.frame.invocation.callable.compilerIdentity)
        assertEquals(expected.anchor, operation.frame.invocation.range)
        assertEquals(expected.enclosing.compilerIdentity, operation.frame.invocation.enclosing.compilerIdentity)
        assertEquals(operation.frame.invocation.resultSite(), transfer.target)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertEquals(origin.callStack, transfer.target.callStack)
        assertEquals(origin.valuePath, transfer.target.valuePath)
    }

    private fun seeded(fixture: NativePacketValueFixture, expression: PacketExpression): ValueSite {
        val result = seed(fixture, expression)
        assertTrue("Exact marked producer was not admitted: $result", result is ValueProducerSeedRead.Seeded)
        return (result as ValueProducerSeedRead.Seeded).seed.site
    }

    private fun seed(fixture: NativePacketValueFixture, expression: PacketExpression): ValueProducerSeedRead =
        runBlocking {
            val request =
                ValueProducerSeedRequest.create(
                        enclosing = expression.enclosing,
                        anchor = expression.anchor,
                        expectedCallable =
                            fixture.callableDeclaration(PacketDeclaration(CONTROLS, "AtomicPacket", "<init>")),
                        budget = fixture.budget,
                        boundary = RelationSearchBoundary.RETAINED_SUBJECT,
                    )
                    .nativeRefined()
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }

    private fun next(fixture: NativePacketValueFixture, site: ValueSite, kind: ValueTransferKind): ValueSite {
        val read = step(fixture, site)
        assertEquals(
            "Missing $kind at ${site.range}: ${read.obligations}",
            listOf(kind),
            read.transfers.map { it.kind },
        )
        assertTrue("Supported edge acquired an obligation: ${read.obligations}", read.obligations.isEmpty())
        assertEquals(site, read.transfers.single().source)
        return read.transfers.single().target
    }

    private fun step(fixture: NativePacketValueFixture, site: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Native flow was not observed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/AtomicReturnControls.kt"
    }
}
