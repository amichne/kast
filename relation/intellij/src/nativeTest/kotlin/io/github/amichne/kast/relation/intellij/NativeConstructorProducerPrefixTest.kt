package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueCallTarget
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

/** Constructor admission keeps its exact local prefix through the source path to the opaque call. */
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
        steps.addAll(continueFrom(fixture, compiler, site))
        val send =
            fixture.selection(
                PacketDeclaration(V0, "BackendClient", "fetch"),
                "send",
                PacketDeclaration(V0, "Transport", "send"),
            )
        assertOpaqueFrontier(steps = steps, fixture = fixture, fetch = fetch, send = send)
        assertUnsupportedSites(steps = steps, request = request, fetch = fetch, send = send)
    }

    private suspend fun continueFrom(
        fixture: NativePacketValueFixture,
        compiler: IntellijValueFlowCompilerAdapter,
        source: ValueSite,
    ): List<ValueFlowStep> {
        val pending = ArrayDeque<ValueSite>()
        pending.add(source)
        val seen = mutableSetOf<ValueSite>()
        val steps = mutableListOf<ValueFlowStep>()
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!seen.add(site)) continue
            assertTrue("The source continuation exceeded its finite fixture capacity", seen.size <= MAX_SITES)
            val step = read(fixture, compiler, site)
            steps.add(step)
            step.transfers.forEach { pending.add(it.target) }
        }
        assertEquals(ValueTransferKind.SOURCE_CALL_ENTRY, steps.first().transfers.single().kind)
        return steps
    }

    private fun assertOpaqueFrontier(
        steps: List<ValueFlowStep>,
        fixture: NativePacketValueFixture,
        fetch: ValueProducerSeedRequest,
        send: ValueProducerSeedRequest,
    ) {
        val expected =
            fixture.argumentExpression(PacketDeclaration(V0, "BackendClient", "fetch"), "send", "wireRequest")
        val reached = steps.singleOrNull { step ->
            step.source.range == expected.anchor &&
                step.source.enclosing.compilerIdentity == expected.enclosing.compilerIdentity &&
                step.source.role is ValueRole.Argument
        }
        assertNotNull("The source continuation did not reach the exact send argument", reached)
        val frontier = checkNotNull(reached)
        val argument = frontier.source.role as ValueRole.Argument
        assertEquals(0, argument.position.value)
        assertEquals(send.anchor, argument.call.range)
        assertEquals(send.expectedCallable.compilerIdentity, argument.call.callable.compilerIdentity)
        assertEquals(send.expectedCallable.file, argument.call.callable.file)
        assertEquals(send.expectedCallable.range, argument.call.callable.range)
        assertEquals(send.enclosing.compilerIdentity, argument.call.enclosing.compilerIdentity)
        val frame = frontier.source.callStack.frames.single()
        assertEquals(fetch.anchor, frame.invocation.range)
        assertEquals(fetch.enclosing.compilerIdentity, frame.invocation.enclosing.compilerIdentity)
        assertEquals(fetch.expectedCallable.compilerIdentity, frame.callee.enclosing.compilerIdentity)
        assertEquals(ValueCallTarget.Direct, frame.target)
        assertTrue("The opaque call invented a transfer", frontier.transfers.isEmpty())
        assertEquals(listOf(ValueFlowUnsupportedCause.UNMODELED_CALL), frontier.obligations.map { it.cause })
        assertEquals(frontier.source, frontier.obligations.single().site)
        assertEquals(frontier.obligations, steps.flatMap { it.obligations })
    }

    private fun assertUnsupportedSites(
        steps: List<ValueFlowStep>,
        request: ValueProducerSeedRequest,
        fetch: ValueProducerSeedRequest,
        send: ValueProducerSeedRequest,
    ) {
        val emitted = steps.flatMap { step ->
            listOf(step.source) + step.transfers.flatMap { listOf(it.source, it.target) }
        }
        assertTrue("The constructor value invented a return", emitted.none { it.role == ValueRole.Return })
        assertTrue(
            "The source continuation escaped its compiler-confirmed owners",
            emitted.all { site ->
                site.enclosing.compilerIdentity in
                    listOf(request.enclosing.compilerIdentity, fetch.expectedCallable.compilerIdentity)
            },
        )
        assertTrue(
            "The source call invented its result from the request",
            emitted.none { it.range == fetch.anchor && it.role == ValueRole.ExpressionResult },
        )
        assertTrue(
            "The opaque call invented its result from the request",
            emitted.none { it.range == send.anchor && it.role == ValueRole.ExpressionResult },
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
        const val MAX_SITES = 128
        const val V0 = "src/main/kotlin/fixture/variants/v0/V0.kt"
    }
}
