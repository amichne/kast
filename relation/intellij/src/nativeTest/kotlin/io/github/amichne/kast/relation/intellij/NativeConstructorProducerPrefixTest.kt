package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Constructor admission exposes the supported local prefix and its exact unresolved call. */
class NativeConstructorProducerPrefixTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "constructor-producer-prefix-jdk"

    fun testConstructorValueReachesItsExactArgumentAndKeepsTheUnsupportedFrontier() = runBlocking {
        val fixture = packetFixture(listOf(V0))
        val feature = PacketDeclaration(V0, "Feature", "handle")
        val request = fixture.selection(feature, "ClientRequest", PacketDeclaration(V0, "ClientRequest", "<init>"))
        val fetch = fixture.selection(feature, "fetch", PacketDeclaration(V0, "BackendClient", "fetch"))
        val expectedArgument = fixture.argumentExpression(feature, "fetch", "clientRequest")
        val compiler = IntellijValueFlowCompilerAdapter()
        val admitted =
            compiler.seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        assertTrue("Constructor admission failed: $admitted", admitted is ValueProducerSeedRead.Seeded)
        val seed = (admitted as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.site.range)
        assertEquals(request.expectedCallable.compilerIdentity, seed.invocation.callable.compilerIdentity)
        val steps = mutableListOf<ValueFlowStep>()
        var site = seed.site
        for (kind in
            listOf(ValueTransferKind.LOCAL_BINDING, ValueTransferKind.LOCAL_READ, ValueTransferKind.ARGUMENT)) {
            val step = read(fixture, compiler, site)
            steps.add(step)
            assertTrue(
                "Supported prefix acquired an unresolved effect: ${step.obligations}",
                step.obligations.isEmpty(),
            )
            assertEquals("Each frozen prefix site has one compiler transfer", 1, step.transfers.size)
            val transfer = step.transfers.single()
            assertEquals(site, transfer.source)
            assertEquals(kind, transfer.kind)
            site = transfer.target
        }
        assertEquals(expectedArgument.anchor, site.range)
        assertEquals(expectedArgument.enclosing.compilerIdentity, site.enclosing.compilerIdentity)
        val argument = site.role
        assertTrue("Expected a compiler-resolved argument: $argument", argument is ValueRole.Argument)
        argument as ValueRole.Argument
        assertEquals(0, argument.position.value)
        assertEquals(fetch.anchor, argument.call.range)
        assertEquals(fetch.expectedCallable.compilerIdentity, argument.call.callable.compilerIdentity)
        assertEquals(fetch.expectedCallable.file, argument.call.callable.file)
        assertEquals(fetch.expectedCallable.range, argument.call.callable.range)
        val frontier = read(fixture, compiler, site)
        steps.add(frontier)
        assertTrue("Unsupported source call invented a transfer", frontier.transfers.isEmpty())
        assertEquals(listOf(ValueFlowUnsupportedCause.UNMODELED_CALL), frontier.obligations.map { it.cause })
        assertEquals(site, frontier.obligations.single().site)
        assertUnsupportedSites(steps, request, fetch)
    }

    private fun assertUnsupportedSites(
        steps: List<ValueFlowStep>,
        request: ValueProducerSeedRequest,
        fetch: ValueProducerSeedRequest,
    ) {
        val emitted = steps.flatMap { step ->
            listOf(step.source) + step.transfers.flatMap { listOf(it.source, it.target) }
        }
        assertTrue("The constructor value invented a return", emitted.none { it.role == ValueRole.Return })
        assertTrue(
            "The unsupported call leaked into its implementation",
            emitted.all { it.enclosing.compilerIdentity == request.enclosing.compilerIdentity },
        )
        assertTrue(
            "The unsupported call invented its result",
            emitted.none { it.range == fetch.anchor && it.role == ValueRole.ExpressionResult },
        )
    }

    private suspend fun read(
        fixture: NativePacketValueFixture,
        compiler: IntellijValueFlowCompilerAdapter,
        site: ValueSite,
    ): ValueFlowStep {
        val read =
            compiler.read(
                project = project,
                current = fixture.lease,
                request =
                    ValueFlowRequest(
                        site,
                        fixture.budget,
                        io.github.amichne.kast.relation.contract.RelationSearchBoundary.RETAINED_SUBJECT,
                    ),
                model = fixture.model,
            )
        assertTrue("Prefix read failed: $read", read is ValueFlowRead.Observed)
        return (read as ValueFlowRead.Observed).step
    }

    private companion object {
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
    }
}
