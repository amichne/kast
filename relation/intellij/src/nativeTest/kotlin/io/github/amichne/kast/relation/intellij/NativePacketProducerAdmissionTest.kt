package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import kotlinx.coroutines.runBlocking

/** Constructor and function producers use the same exact compiler admission. */
class NativePacketProducerAdmissionTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-producer-admission-jdk"

    fun testOriginalConstructorSeedKeepsItsExactOccurrence() {
        val fixture = packetFixture(listOf(V0))
        val request =
            fixture.selection(
                PacketDeclaration(V0, "Feature", "handle"),
                "ClientRequest",
                PacketDeclaration(V0, "ClientRequest", "<init>"),
            )
        val result = read(fixture, request)
        assertTrue("Constructor producer must be admitted: $result", result is ValueProducerSeedRead.Seeded)
        val seed = (result as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.site.range)
        assertEquals(request.expectedCallable.compilerIdentity, seed.invocation.callable.compilerIdentity)
    }

    fun testOpaqueResultSeedKeepsItsExactOccurrence() {
        val fixture = packetFixture(listOf(V0))
        val request =
            fixture.selection(
                PacketDeclaration(V0, "BackendClient", "fetch"),
                "send",
                PacketDeclaration(V0, "Transport", "send"),
            )
        val result = read(fixture, request)
        assertTrue("Named-function producer must be admitted: $result", result is ValueProducerSeedRead.Seeded)
        assertEquals(request.anchor, (result as ValueProducerSeedRead.Seeded).seed.site.range)
    }

    fun testDifferentConstructorCallableRejects() {
        val fixture = packetFixture(listOf(V0))
        val request =
            fixture.selection(
                PacketDeclaration(V0, "Feature", "handle"),
                "ClientRequest",
                PacketDeclaration(V0, "WireRequest", "<init>"),
            )
        assertEquals(
            ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH),
            read(fixture, request),
        )
    }

    private fun read(
        fixture: NativePacketValueFixture,
        request: io.github.amichne.kast.relation.contract.ValueProducerSeedRequest,
    ): ValueProducerSeedRead = runBlocking {
        IntellijValueFlowCompilerAdapter()
            .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
    }

    private companion object {
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
    }
}
