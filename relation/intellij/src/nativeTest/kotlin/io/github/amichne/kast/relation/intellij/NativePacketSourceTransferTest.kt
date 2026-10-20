package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import kotlinx.coroutines.runBlocking

/** Fixed original V0 routes isolate source-call entry and field projection before matched return composition. */
class NativePacketSourceTransferTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-source-transfer-jdk"

    fun testOriginalV0OutgoingEntersTheConcreteSourceFormal() {
        val fixture = packetFixture(listOf(V0))
        val request =
            fixture.selection(
                PacketDeclaration(V0, "Feature", "handle"),
                "ClientRequest",
                PacketDeclaration(V0, "ClientRequest", "<init>"),
            )
        val seed = seed(fixture, request)
        val local = step(fixture, seed).transfers.single().target
        assertEquals(ValueRole.LocalBinding, local.role)
        val read = step(fixture, local).transfers.single().target
        assertEquals(ValueRole.LocalRead, read.role)
        val argument = step(fixture, read).transfers.single().target
        val call = argument.role as ValueRole.Argument
        assertEquals("fetch", call.call.callable.name.value)
        assertEquals(0, call.position.value)
        val sourceEntry = step(fixture, argument)
        val expected = fixture.formalParameter(PacketDeclaration(V0, "BackendClient", "fetch"), 0)
        assertTrue(
            "V0 concrete actual-to-formal transfer is absent. " +
                "Actual=${argument.range}. ExpectedFormal=${expected.anchor}. " +
                "Frontier=${sourceEntry.obligations.map { it.cause }}. Matched return=NOT_REACHED",
            sourceEntry.transfers.any { transfer ->
                transfer.target.enclosing.compilerIdentity == expected.enclosing.compilerIdentity &&
                    transfer.target.range == expected.anchor
            },
        )
    }

    fun testOriginalV0IncomingProjectsTheResolvedResponseField() {
        val fixture = packetFixture(listOf(V0))
        val request =
            fixture.selection(
                PacketDeclaration(V0, "BackendClient", "fetch"),
                "send",
                PacketDeclaration(V0, "Transport", "send"),
            )
        val seed = seed(fixture, request)
        val local = step(fixture, seed).transfers.single().target
        assertEquals(ValueRole.LocalBinding, local.role)
        val read = step(fixture, local).transfers.single().target
        assertEquals(ValueRole.LocalRead, read.role)
        val projected = step(fixture, read)
        val expected =
            fixture.argumentExpression(
                PacketDeclaration(V0, "BackendClient", "fetch"),
                "ClientResponse",
                "wireResponse.link",
            )
        assertTrue(
            "V0 response field projection is absent. " +
                "Input=${read.range}. ExpectedProjection=${expected.anchor}. " +
                "Frontier=${projected.obligations.map { it.cause }}. Matched return=NOT_REACHED",
            projected.transfers.any { it.target.range == expected.anchor },
        )
    }

    private fun seed(fixture: NativePacketValueFixture, request: ValueProducerSeedRequest): ValueSite {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue("Original V0 seed failed before source transfer: $result", result is ValueProducerSeedRead.Seeded)
        return (result as ValueProducerSeedRead.Seeded).seed.site
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
        assertTrue("Original V0 one-hop read failed: $result", result is ValueFlowRead.Observed)
        val step = (result as ValueFlowRead.Observed).step
        println(
            "V0_SOURCE_STAGE source=${site.range} role=${site.role} " +
                "transfers=${step.transfers.map { it.kind to it.target.range }} " +
                "frontier=${step.obligations.map { it.cause }}"
        )
        return step
    }

    private companion object {
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
    }
}
