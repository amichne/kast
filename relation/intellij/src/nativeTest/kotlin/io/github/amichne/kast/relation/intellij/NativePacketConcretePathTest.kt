package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowObligationEvidence
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import kotlinx.coroutines.runBlocking

/** Fixed native routes characterize the concrete variants without replacing the query's worklist. */
class NativePacketConcretePathTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-concrete-path-jdk"

    fun testV0OutgoingReachesOnlyTheOpaqueRequestArgument() = outgoing("v0", Shape.LOCAL)

    fun testV0IncomingReachesTheFeatureReturn() = incoming("v0", Shape.LOCAL)

    fun testV1OutgoingReachesOnlyTheOpaqueRequestArgument() = outgoing("v1", Shape.LOCAL)

    fun testV1IncomingReachesTheFeatureReturn() = incoming("v1", Shape.LOCAL, "BackendClientImpl")

    fun testV2OutgoingReachesOnlyTheOpaqueRequestArgument() = outgoing("v2", Shape.EXTENSION)

    fun testV2IncomingReachesTheFeatureReturn() = incoming("v2", Shape.EXTENSION)

    fun testV3OutgoingReachesOnlyTheOpaqueRequestArgument() = outgoing("v3", Shape.NESTED)

    fun testV3IncomingReachesTheFeatureReturn() = incoming("v3", Shape.NESTED)

    fun testV8OutgoingReachesOnlyTheOpaqueRequestArgument() = outgoing("v8", Shape.LOCAL)

    fun testV8IncomingReachesTheFeatureReturn() = incoming("v8", Shape.LOCAL)

    private fun outgoing(variant: String, shape: Shape) {
        val file = source(variant)
        val fixture = packetFixture(listOf(file))
        val seed =
            seed(
                fixture = fixture,
                owner = PacketDeclaration(file, "Feature", "handle"),
                call = "ClientRequest",
                target = PacketDeclaration(file, "ClientRequest", "<init>"),
            )
        val start = if (shape == Shape.NESTED) emptyList() else LOCAL
        val mapped =
            if (shape == Shape.EXTENSION)
                listOf(ValueTransferKind.RECEIVER, ValueTransferKind.SOURCE_CALL_ENTRY, ValueTransferKind.FIELD_READ)
            else listOf(ValueTransferKind.FIELD_READ)
        val returned =
            if (shape == Shape.EXTENSION) listOf(ValueTransferKind.RETURN, ValueTransferKind.SOURCE_CALL_RETURN)
            else emptyList()
        val kinds =
            start +
                listOf(ValueTransferKind.ARGUMENT, ValueTransferKind.SOURCE_CALL_ENTRY, ValueTransferKind.LOCAL_READ) +
                mapped +
                listOf(ValueTransferKind.ARGUMENT, ValueTransferKind.CONSTRUCTOR_FIELD) +
                returned +
                start +
                ValueTransferKind.ARGUMENT
        val destination = route(fixture, seed, kinds)
        val argument = destination.role as ValueRole.Argument
        assertEquals("send", argument.call.callable.name.value)
        assertEquals(0, argument.position.value)
        assertEquals(
            "request",
            fixture.parameterNames(PacketDeclaration(file, "Transport", "send"))[argument.position.value],
        )
        assertEquals("fixture.variants.$variant.WireRequest.destination", field(destination))
        val frontier = step(fixture, destination)
        assertEquals(emptyList<Any>(), frontier.transfers)
        assertEquals(listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL), frontier.obligations.map { it.cause })
    }

    private fun incoming(variant: String, shape: Shape, clientOwner: String = "BackendClient") {
        val file = source(variant)
        val fixture = packetFixture(listOf(file))
        val seed =
            seed(
                fixture = fixture,
                owner = PacketDeclaration(file, clientOwner, "fetch"),
                call = "send",
                target = PacketDeclaration(file, "Transport", "send"),
            )
        val local = if (shape == Shape.NESTED) emptyList() else LOCAL
        val mapping =
            if (shape == Shape.EXTENSION)
                listOf(
                    ValueTransferKind.RECEIVER,
                    ValueTransferKind.SOURCE_CALL_ENTRY,
                    ValueTransferKind.FIELD_READ,
                    ValueTransferKind.ARGUMENT,
                    ValueTransferKind.CONSTRUCTOR_FIELD,
                    ValueTransferKind.RETURN,
                    ValueTransferKind.SOURCE_CALL_RETURN,
                )
            else listOf(ValueTransferKind.FIELD_READ, ValueTransferKind.ARGUMENT, ValueTransferKind.CONSTRUCTOR_FIELD)
        val kinds =
            local +
                mapping +
                listOf(ValueTransferKind.RETURN, ValueTransferKind.SOURCE_CALL_RETURN) +
                local +
                listOf(
                    ValueTransferKind.FIELD_READ,
                    ValueTransferKind.ARGUMENT,
                    ValueTransferKind.CONSTRUCTOR_FIELD,
                    ValueTransferKind.RETURN,
                )
        val returned = route(fixture, seed, kinds)
        assertEquals(ValueRole.Return, returned.role)
        assertEquals("handle", returned.enclosing.name.value)
        assertEquals("fixture.variants.$variant.FeatureResponse.link", field(returned))
        assertEquals(emptyList<Any>(), returned.callStack.frames)
    }

    private fun route(fixture: NativePacketValueFixture, start: ValueSite, kinds: List<ValueTransferKind>): ValueSite {
        var site = start
        for (kind in kinds) {
            val read = step(fixture, site)
            assertEquals(
                "Expected $kind at ${site.range}. Frontier=${read.obligations.map { it.cause }}",
                listOf(kind),
                read.transfers.map { it.kind },
            )
            assertEquals("Source=${site.range}. ${frontier(read)}", emptyList<Any>(), read.obligations)
            site = read.transfers.single().target
        }
        return site
    }

    private fun frontier(read: ValueFlowStep): List<String> =
        read.obligations.map { obligation ->
            when (val evidence = obligation.evidence) {
                ValueFlowObligationEvidence.None -> obligation.cause.toString()
                is ValueFlowObligationEvidence.ImplementationDispatch -> {
                    val dispatch = evidence.dispatch
                    "${obligation.cause}:subject=${dispatch.batch.request.subject.qualifiedIdentity}:" +
                        "facts=${dispatch.batch.facts.size}:coverage=${dispatch.coverage}"
                }
                is ValueFlowObligationEvidence.CallbackFlow ->
                    "${obligation.cause}:callback=${evidence.flow.body.range}:" +
                        "invocations=${evidence.flow.invocations.size}:obligations=${evidence.flow.obligations}"
                is ValueFlowObligationEvidence.CallbackUnavailable -> "${obligation.cause}:callback=${evidence.cause}"
                is ValueFlowObligationEvidence.CallbackContractRejected ->
                    "${obligation.cause}:callbackContract=${evidence.cause}"
            }
        }

    private fun seed(
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        call: String,
        target: PacketDeclaration,
    ): ValueSite {
        val request = fixture.selection(owner, call, target)
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue(result is ValueProducerSeedRead.Seeded)
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
        assertTrue("${readState(fixture, site)}: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun readState(fixture: NativePacketValueFixture, site: ValueSite): String {
        val request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT)
        val domain =
            RelationRequest.start(
                subject = site.enclosing,
                meaning = RelationMeaning.References,
                budget = fixture.budget,
                boundary = request.boundary,
            )
        val base =
            ValueFlowStep.detachedByteCount(
                    source = site,
                    domain = domain,
                    transfers = emptyList(),
                    obligations = emptyList(),
                )
                .value
        val frames = site.callStack.frames.map { "${it.invocation.range}->${it.callee.enclosing.name.value}" }
        return "Concrete native read ${site.enclosing.name.value} ${site.range} ${site.role::class.simpleName}" +
            " path=${site.valuePath.segments} depth=${site.callStack.frames.size} frames=$frames" +
            " siteBytes=${site.retainedBytes} base=$base reserved=${reservedValueObligationBytes()}" +
            " grantedBytes=${fixture.budget.returnedBytes.value}"
    }

    private fun field(site: ValueSite): String {
        val segment = site.valuePath.segments.single() as ValuePathSegment.Field
        return (segment.field.declaration.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value
    }

    private fun source(variant: String) = "src/main/kotlin/fixture/variants/$variant/${variant.uppercase()}.kt"

    private enum class Shape {
        LOCAL,
        EXTENSION,
        NESTED,
    }

    private companion object {
        val LOCAL = listOf(ValueTransferKind.LOCAL_BINDING, ValueTransferKind.LOCAL_READ)
    }
}
