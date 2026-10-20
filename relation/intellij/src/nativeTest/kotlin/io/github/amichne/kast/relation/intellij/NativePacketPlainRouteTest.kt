package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import kotlinx.coroutines.runBlocking

/** Original packet origins must reach their exact consumers through compiler transfers. */
class NativePacketPlainRouteTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-plain-route-jdk"

    fun testConstructorOriginReachesOnlyTheOpaqueRequestArgument() {
        val fixture = packetFixture(listOf(V0))
        val source =
            fixture.selection(
                PacketDeclaration(V0, "Feature", "handle"),
                "ClientRequest",
                PacketDeclaration(V0, "ClientRequest", "<init>"),
            )
        val expected =
            fixture.argumentExpression(PacketDeclaration(V0, "BackendClient", "fetch"), "send", "wireRequest")
        val send = fixture.callableDeclaration(PacketDeclaration(V0, "Transport", "send"))
        val steps = walk(fixture, source)
        val reached = steps.singleOrNull { step ->
            val role = step.source.role as? ValueRole.Argument
            step.source.range == expected.anchor &&
                role?.position?.value == 0 &&
                role.call.callable.compilerIdentity == send.compilerIdentity
        }
        assertNotNull("Missing opaque argument. Causes=${steps.flatMap { it.obligations }.map { it.cause }}", reached)
        assertEquals(
            listOf(ValueFlowUnsupportedCause.UNMODELED_CALL),
            checkNotNull(reached).obligations.map { it.cause },
        )
        assertTrue(reached.transfers.isEmpty())
        assertTrue(
            steps.none { step ->
                step.source.role == ValueRole.Return &&
                    step.source.enclosing.compilerIdentity == source.enclosing.compilerIdentity
            }
        )
    }

    fun testOpaqueResultOriginReachesTheFeatureReturn() {
        val fixture = packetFixture(listOf(V0))
        val source =
            fixture.selection(
                PacketDeclaration(V0, "BackendClient", "fetch"),
                "send",
                PacketDeclaration(V0, "Transport", "send"),
            )
        val feature = fixture.callableDeclaration(PacketDeclaration(V0, "Feature", "handle"))
        val steps = walk(fixture, source)
        assertTrue(
            "Missing feature return. Causes=${steps.flatMap { it.obligations }.map { it.cause }}",
            steps.any { step ->
                step.source.role == ValueRole.Return &&
                    step.source.enclosing.compilerIdentity == feature.compilerIdentity
            },
        )
        assertTrue(
            steps.none { step ->
                (step.source.role as? ValueRole.Argument)?.call?.callable?.compilerIdentity ==
                    source.expectedCallable.compilerIdentity
            }
        )
    }

    private fun walk(fixture: NativePacketValueFixture, request: ValueProducerSeedRequest): List<ValueFlowStep> =
        runBlocking {
            val compiler = IntellijValueFlowCompilerAdapter()
            val seed =
                compiler.seed(project = project, current = fixture.lease, request = request, model = fixture.model)
            assertTrue("Seed admission failed: $seed", seed is ValueProducerSeedRead.Seeded)
            val pending = ArrayDeque<ValueSite>()
            pending.add((seed as ValueProducerSeedRead.Seeded).seed.site)
            val seen = mutableSetOf<ValueSite>()
            val steps = mutableListOf<ValueFlowStep>()
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (!seen.add(site)) continue
                assertTrue("Native route exceeded its finite fixture capacity", seen.size <= MAX_SITES)
                val read =
                    compiler.read(
                        project = project,
                        current = fixture.lease,
                        request = ValueFlowRequest(site, fixture.budget, request.boundary),
                        model = fixture.model,
                    )
                assertTrue("Native route read failed: $read", read is ValueFlowRead.Observed)
                val step = (read as ValueFlowRead.Observed).step
                steps.add(step)
                step.transfers.forEach { pending.add(it.target) }
            }
            steps
        }

    private companion object {
        const val MAX_SITES = 128
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
    }
}
