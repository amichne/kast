package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallTarget
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteIdentity
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import kotlinx.coroutines.runBlocking

/** Receiver dispatch and generated components compose without merging distinct same-typed fields. */
class NativeCoreValueCompositionTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "core-value-composition-jdk"

    fun testSelectedPayloadReachesOnlyItsDestructuredResult() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = declaration("payloadConsumer")
        val steps = walk(fixture, seeded(fixture, owner, "trackedSource"))
        assertNoFrontiers(steps)
        assertImplementationEntered(fixture, steps, "PayloadStage")
        assertReached(steps, fixture.markedExpression(owner, "payload-receiver"))
        assertPayloadReached(steps, fixture.markedExpression(owner, "payload-result"))
        assertNotReached(steps, fixture.markedExpression(owner, "payload-second"))
    }

    fun testOtherSameTypedFieldReachesOnlyTheSecondComponent() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = declaration("payloadConsumer")
        val steps = walk(fixture, seeded(fixture, owner, "otherSource"))
        assertNoFrontiers(steps)
        assertImplementationEntered(fixture, steps, "PayloadStage")
        assertReached(steps, fixture.markedExpression(owner, "payload-receiver"))
        assertPayloadReached(steps, fixture.markedExpression(owner, "payload-second"))
        assertNotReached(steps, fixture.markedExpression(owner, "payload-result"))
    }

    fun testConstantImplementationDoesNotReturnItsStoredInput() {
        val fixture = packetFixture(listOf(CONTROLS))
        val owner = declaration("constantConsumer")
        val steps = walk(fixture, seeded(fixture, owner, "trackedSource"))
        assertNoFrontiers(steps)
        assertImplementationEntered(fixture, steps, "ConstantStage")
        assertReached(steps, fixture.markedExpression(owner, "constant-receiver"))
        assertNotReached(steps, fixture.markedExpression(owner, "constant-result"))
        assertNotReached(steps, fixture.markedExpression(owner, "constant-second"))
    }

    private fun seeded(fixture: NativePacketValueFixture, owner: PacketDeclaration, callee: String): ValueSite {
        val request = fixture.selection(owner, callee, declaration(callee))
        val read = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue("Composition producer was not admitted: $read", read is ValueProducerSeedRead.Seeded)
        return (read as ValueProducerSeedRead.Seeded).seed.site
    }

    private fun walk(fixture: NativePacketValueFixture, origin: ValueSite): List<ValueFlowStep> = runBlocking {
        // Implementation frames retain inventory and ownership evidence, as in NativePacketSourcePolicyTest.
        val budget = fixture.budget.copy(returnedBytes = RelationByteLimit.parse(MAX_READ_BYTES).nativeRefined())
        val pending = ArrayDeque<ValueSite>()
        val visited = linkedSetOf<ValueSiteIdentity>()
        val steps = mutableListOf<ValueFlowStep>()
        pending.addLast(origin)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            assertTrue("Composition fixture exceeded its finite site bound", visited.size <= MAX_SITES)
            val read =
                IntellijValueFlowCompilerAdapter()
                    .read(
                        project = project,
                        current = fixture.lease,
                        request = ValueFlowRequest(site, budget, RelationSearchBoundary.RETAINED_SUBJECT),
                        model = fixture.model,
                    )
            assertTrue(
                "Composition native read was not observed at ${site.range} with ${site.retainedBytes} retained " +
                    "source bytes and a ${budget.returnedBytes.value}-byte grant: $read",
                read is ValueFlowRead.Observed,
            )
            val step = (read as ValueFlowRead.Observed).step
            assertEquals(site, step.source)
            assertTrue("Core composition unexpectedly required a reviewed transfer", step.reviewedTransfers.isEmpty())
            steps += step
            step.transfers.forEach { pending.addLast(it.target) }
        }
        steps
    }

    private fun assertNoFrontiers(steps: List<ValueFlowStep>) {
        assertTrue("Composition fixture did not execute", steps.isNotEmpty())
        val obligations = steps.flatMap { it.obligations }
        assertTrue("Composition retained unresolved obligations: $obligations", obligations.isEmpty())
        assertTrue(
            "Composition did not exhaust each supported native read",
            steps.all { it.terminal == ValueFlowTerminal.SupportedDomainExhausted },
        )
    }

    private fun assertImplementationEntered(
        fixture: NativePacketValueFixture,
        steps: List<ValueFlowStep>,
        owner: String,
    ) {
        val expected = fixture.callableDeclaration(PacketDeclaration(CONTROLS, owner, "deliver"))
        val entries =
            steps
                .flatMap { it.transfers }
                .mapNotNull { transfer ->
                    (transfer.evidence as? ValueSourceTransfer)?.operation as? ValueSourceOperation.Enter
                }
        assertTrue(
            "Receiver dispatch did not enter the compiler-selected $owner implementation",
            entries.any { entry ->
                val target = entry.frame.target as? ValueCallTarget.Implementation
                target?.relation?.source?.compilerIdentity == expected.compilerIdentity &&
                    entry.position == ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)
            },
        )
    }

    private fun assertReached(steps: List<ValueFlowStep>, expected: PacketExpression) {
        assertTrue(
            "Expected marked endpoint was not reached: ${expected.anchor}",
            matching(steps, expected).isNotEmpty(),
        )
    }

    private fun assertPayloadReached(steps: List<ValueFlowStep>, expected: PacketExpression) {
        val sites = matching(steps, expected)
        assertTrue("Expected marked payload use was not reached: ${expected.anchor}", sites.isNotEmpty())
        assertTrue("Destructured payload retained a wrapper field", sites.all { it.valuePath.segments.isEmpty() })
        assertTrue("Destructured payload retained an unmatched call", sites.all { it.callStack.frames.isEmpty() })
    }

    private fun assertNotReached(steps: List<ValueFlowStep>, forbidden: PacketExpression) {
        assertTrue("Forbidden marked use was reached: ${forbidden.anchor}", matching(steps, forbidden).isEmpty())
    }

    private fun matching(steps: List<ValueFlowStep>, expression: PacketExpression): List<ValueSite> =
        steps
            .map { it.source }
            .filter { site ->
                site.enclosing.compilerIdentity == expression.enclosing.compilerIdentity &&
                    site.range == expression.anchor &&
                    site.role == ValueRole.LocalRead
            }

    private fun declaration(name: String) = PacketDeclaration(CONTROLS, "", name)

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/CoreValueCompositionControls.kt"
        const val MAX_READ_BYTES = 16_000_000L
        const val MAX_SITES = 256
    }
}
