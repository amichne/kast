package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueProducerSeedRequest
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.runBlocking

/** Explicit original-source routes expose their first missing transfer without another query scheduler. */
class NativePacketCompositePathTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-composite-path-jdk"

    fun testOriginalS1OutgoingReachesTheOpaqueApiBody() {
        val fixture = packetFixture(COMPOSITE)
        val expected =
            fixture.argumentExpression(
                PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"),
                "readOffers",
                "request.toWireRequest()",
            )
        val seed =
            seed(
                fixture,
                fixture.selection(
                    PacketDeclaration(FEATURE, "OffersProcessorImpl", "getOffer"),
                    "ClientRequest",
                    PacketDeclaration(CLIENT, "ClientRequest", "<init>"),
                ),
                "S1",
            )
        assertTrue("S1 must keep the genuine sequential body", seed.executionOwner.body is ValueExecutionBody.Anonymous)
        val argument = route(fixture = fixture, start = seed, kinds = LOCAL + ValueTransferKind.ARGUMENT, row = "C1.1")
        val requested =
            fixture.argumentExpression(
                PacketDeclaration(FEATURE, "OffersProcessorImpl", "getOffer"),
                "readOffers",
                "clientRequest",
            )
        assertSite(requested, argument)
        assertEquals(seed.executionOwner, argument.executionOwner)
        val formal =
            next(fixture = fixture, source = argument, kind = ValueTransferKind.SOURCE_CALL_ENTRY, row = "C1.2")
        assertSite(fixture.formalParameter(PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers"), 0), formal)
        val body = outgoingBody(fixture, formal)
        assertSite(expected, body)
        val role = body.role as ValueRole.Argument
        val api = fixture.callableDeclaration(PacketDeclaration(WIRE, "OffersApi", "readOffers"))
        assertEquals(api.compilerIdentity, role.call.callable.compilerIdentity)
        assertEquals(1, role.position.value)
        assertEquals(
            "body",
            fixture.parameterNames(PacketDeclaration(WIRE, "OffersApi", "readOffers"))[role.position.value],
        )
        assertEquals("fixture.wire.WireRequest.destination", field(body))
        val frontier = step(fixture, body, "C1.4 opaque argument")
        assertEquals(emptyList<Any>(), frontier.transfers)
        assertEquals(listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL), frontier.obligations.map { it.cause })
        println("COMPOSITE_RESULT rows=C1.1,C1.2,C1.3,C1.4 remaining=C1.5,C2.1-C2.10 status=NOT_RUN")
    }

    fun testOriginalS2ReceiverEntersTheSourceMapBeforePayloadCallbacks() {
        val fixture = packetFixture(COMPOSITE)
        val owner = PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers")
        val expected = fixture.selection(owner, "map", PacketDeclaration(OUTCOME, "Outcome", "map"))
        val seed = seed(fixture, fixture.selection(owner, "execute", PacketDeclaration(WIRE, "Call", "execute")), "S2")
        val receiver =
            next(fixture = fixture, source = seed, kind = ValueTransferKind.RECEIVER, row = "C2.1 receiver arrival")
        val call = (receiver.role as ValueRole.Receiver).call
        assertEquals(expected.anchor, call.range)
        assertEquals(expected.expectedCallable.compilerIdentity, call.callable.compilerIdentity)
        assertEquals(seed.range, receiver.range)
        val entered = step(fixture, receiver, "C2.1 source map receiver entry")
        val matching =
            entered.transfers.filter { transfer ->
                transfer.kind == ValueTransferKind.SOURCE_CALL_ENTRY &&
                    transfer.target.enclosing.compilerIdentity == expected.expectedCallable.compilerIdentity &&
                    transfer.target.range == expected.expectedCallable.range
            }
        assertEquals(
            "C2.1 needs source map receiver entry before Success.value can reach its supplied anonymous body. " +
                "Expected=${expected.expectedCallable.range}. Frontier=${entered.obligations.map { it.cause }}. " +
                "C2.1 callback payload and C2.2-C2.10 remain NOT_REACHED",
            1,
            matching.size,
        )
        assertEquals(
            ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.DISPATCH)),
            matching.single().target.role,
        )
        assertEquals(call, matching.single().target.callStack.frames.last().invocation)
        println(
            "COMPOSITE_RESULT row=C2.1 stage=map-receiver-entry remaining=callback-payload,C2.2-C2.10 status=NOT_RUN"
        )
    }

    private fun outgoingBody(fixture: NativePacketValueFixture, formal: ValueSite): ValueSite {
        val owner = PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers")
        val call = fixture.selection(owner, "toWireRequest", PacketDeclaration(CLIENT, "", "toWireRequest"))
        val requestRange =
            ExactDeclarationTextRange.parse(call.anchor.startInclusive, call.anchor.startInclusive + "request".length)
                .nativeRefined()
        val reads = step(fixture, formal, "C1.3 request receiver reference")
        val selected =
            reads.transfers.filter { it.kind == ValueTransferKind.LOCAL_READ && it.target.range == requestRange }
        assertEquals(
            "C1.3 needs the original request receiver at $requestRange. " +
                "Frontier=${reads.obligations.map { it.cause }}. C1.4 remains NOT_REACHED",
            1,
            selected.size,
        )
        val returned =
            route(
                fixture = fixture,
                start = selected.single().target,
                kinds =
                    listOf(
                        ValueTransferKind.RECEIVER,
                        ValueTransferKind.SOURCE_CALL_ENTRY,
                        ValueTransferKind.FIELD_READ,
                        ValueTransferKind.ARGUMENT,
                        ValueTransferKind.CONSTRUCTOR_FIELD,
                        ValueTransferKind.RETURN,
                        ValueTransferKind.SOURCE_CALL_RETURN,
                    ),
                row = "C1.3 destination projection and construction",
            )
        assertEquals(call.anchor, returned.range)
        return next(
            fixture = fixture,
            source = returned,
            kind = ValueTransferKind.ARGUMENT,
            row = "C1.4 opaque body arrival",
        )
    }

    private fun seed(fixture: NativePacketValueFixture, request: ValueProducerSeedRequest, label: String): ValueSite {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(
            "$label original seed failed: $result. Selected=${request.anchor}. " +
                "First transfer=NOT_REACHED. First frontier=NOT_REACHED",
            result is ValueProducerSeedRead.Seeded,
        )
        val seed = (result as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.invocation.range)
        assertEquals(
            request.expectedCallable.compilerIdentity,
            (seed.origin as ValueProducerOrigin.Declared).invocation.callable.compilerIdentity,
        )
        assertEquals(request.enclosing.compilerIdentity, seed.site.enclosing.compilerIdentity)
        println(
            "COMPOSITE_SEED label=$label selected=${request.anchor} canonical=${seed.invocation.range}" +
                " execution=${seed.site.executionOwner.identity}"
        )
        return seed.site
    }

    private fun route(
        fixture: NativePacketValueFixture,
        start: ValueSite,
        kinds: List<ValueTransferKind>,
        row: String,
    ): ValueSite {
        var site = start
        for (kind in kinds) site = next(fixture = fixture, source = site, kind = kind, row = row)
        return site
    }

    private fun next(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        kind: ValueTransferKind,
        row: String,
    ): ValueSite {
        val read = step(fixture, source, row)
        assertEquals(
            "$row needs $kind at ${source.range}. Frontier=${read.obligations.map { it.cause }}. " +
                "Later composite obligations remain NOT_REACHED",
            listOf(kind),
            read.transfers.map { it.kind },
        )
        assertEquals(emptyList<Any>(), read.obligations)
        return read.transfers.single().target
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite, row: String): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue(
            "$row one-hop native read failed before its transfer assertion: $result",
            result is ValueFlowRead.Observed,
        )
        val read = (result as ValueFlowRead.Observed).step
        println(
            "COMPOSITE_STAGE row=$row source=${source.range} role=${source.role::class.simpleName} " +
                "transfers=${read.transfers.map { it.kind to it.target.range }} " +
                "frontier=${read.obligations.map { it.cause }}"
        )
        return read
    }

    private fun assertSite(expected: PacketExpression, actual: ValueSite) {
        assertEquals(expected.enclosing.compilerIdentity, actual.enclosing.compilerIdentity)
        assertEquals(expected.enclosing.file, actual.enclosing.file)
        assertEquals(expected.anchor, actual.range)
    }

    private fun field(site: ValueSite): String {
        val segment = site.valuePath.segments.single() as ValuePathSegment.Field
        return (segment.field.declaration.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value
    }

    private companion object {
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val CLIENT = "src/main/kotlin/fixture/client/OffersClient.kt"
        const val WIRE = "src/main/kotlin/fixture/wire/OffersApi.kt"
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        val COMPOSITE = listOf(OUTCOME, WIRE, CLIENT, FEATURE)
        val LOCAL = listOf(ValueTransferKind.LOCAL_BINDING, ValueTransferKind.LOCAL_READ)
    }
}
