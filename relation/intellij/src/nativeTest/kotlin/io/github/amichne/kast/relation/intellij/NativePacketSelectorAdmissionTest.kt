package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueProducerSeed
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRejection
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import kotlinx.coroutines.runBlocking

class NativePacketSelectorAdmissionTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-selector-admission-jdk"

    fun testNestedQualifiedAndSelectorAnchorsShareOneExactResult() {
        val fixture = packetFixture(listOf(V3))
        val full = request(fixture, PacketSeedSyntax.QUALIFIED)
        val selector = request(fixture, PacketSeedSyntax.SELECTOR_ONLY)
        assertFalse(full.anchor == selector.anchor)
        val fullSeed = seeded(fixture, full)
        val selectedSeed = seeded(fixture, selector)
        assertEquals(full.anchor, selectedSeed.site.range)
        assertEquals(fullSeed.site.identity, selectedSeed.site.identity)
        assertEquals(fullSeed.invocation.identity, selectedSeed.invocation.identity)
    }

    fun testCalleeTextAloneDoesNotSelectAnInvocationResult() {
        val fixture = packetFixture(listOf(V3))
        assertEquals(
            ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.UNSUPPORTED_INVOCATION),
            read(fixture, request(fixture, PacketSeedSyntax.CALLEE_ONLY)),
        )
    }

    fun testSelectorForAnotherCallableCannotClaimTheOpaqueResult() {
        val fixture = packetFixture(listOf(V3))
        val wrong =
            fixture.selection(
                owner = PacketDeclaration(V3, "BackendClient", "fetch"),
                callee = "send",
                callable = PacketDeclaration(V3, "WireRequest", "<init>"),
                syntax = PacketSeedSyntax.SELECTOR_ONLY,
            )
        assertEquals(ValueProducerSeedRead.Rejected(ValueProducerSeedRejection.CALLABLE_MISMATCH), read(fixture, wrong))
    }

    private fun request(fixture: NativePacketValueFixture, syntax: PacketSeedSyntax) =
        fixture.selection(
            owner = PacketDeclaration(V3, "BackendClient", "fetch"),
            callee = "send",
            callable = PacketDeclaration(V3, "Transport", "send"),
            syntax = syntax,
        )

    private fun seeded(fixture: NativePacketValueFixture, request: ValueProducerSeedRequest): ValueProducerSeed {
        val result = read(fixture, request)
        assertTrue("Exact invocation selection failed: $result", result is ValueProducerSeedRead.Seeded)
        return (result as ValueProducerSeedRead.Seeded).seed
    }

    private fun read(fixture: NativePacketValueFixture, request: ValueProducerSeedRequest) = runBlocking {
        IntellijValueFlowCompilerAdapter()
            .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
    }

    private companion object {
        const val V3 = "src/main/kotlin/fixture/variants/v3/V3.kt"
    }
}
