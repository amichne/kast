package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Two source calls share a callee while their argument provenance and return destinations remain distinct. */
class NativePacketMatchedReturnTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-matched-return-jdk"

    fun testTwoMapperCallsReturnOnlyToTheirExactInvocation() {
        val fixture = packetFixture(SOURCES)
        val first = mapperArgument(fixture, "first-destination", "first-token")
        val second = mapperArgument(fixture, "second-destination", "second-token")
        val firstCall = (first.role as ValueRole.Argument).call
        val secondCall = (second.role as ValueRole.Argument).call
        assertFalse(firstCall.identity == secondCall.identity)
        val firstReturned = returned(fixture, first)
        val secondReturned = returned(fixture, second)
        assertEquals(firstCall.range, firstReturned.range)
        assertEquals(secondCall.range, secondReturned.range)
        assertFalse(firstReturned.identity == secondReturned.identity)
        assertEquals(emptyList<Any>(), firstReturned.callStack.frames)
        assertEquals(emptyList<Any>(), secondReturned.callStack.frames)
    }

    fun testSameTypedFieldsStayDistinctAtTheResolvedFieldRead() {
        val fixture = packetFixture(SOURCES)
        val destination = constructorField(fixture, "first-destination")
        val token = constructorField(fixture, "first-token")
        assertFalse(destination.valuePath == token.valuePath)
        val selected = fieldStep(fixture, destination)
        assertEquals(listOf(ValueTransferKind.FIELD_READ), selected.transfers.map { it.kind })
        assertEquals(emptyList<Any>(), selected.transfers.single().target.valuePath.segments)
        val excluded = fieldStep(fixture, token)
        assertEquals(emptyList<Any>(), excluded.transfers)
        assertEquals(emptyList<Any>(), excluded.obligations)
    }

    fun testDiscardedInputCannotReachTheConstantMapperResult() {
        val fixture = packetFixture(SOURCES)
        val expression =
            fixture.argumentExpression(
                PacketDeclaration(CONTROLS, "", "twoMatchedMapperCalls"),
                "constantOnly",
                "ClientRequest(destination = \"discarded-destination\", sessionToken = \"discarded-token\")",
            )
        val argument = next(fixture, resolve(fixture, expression), ValueTransferKind.ARGUMENT)
        val formal = next(fixture, argument, ValueTransferKind.SOURCE_CALL_ENTRY)
        val read = step(fixture, formal)
        assertEquals(emptyList<Any>(), read.transfers)
        assertEquals(emptyList<Any>(), read.obligations)
        assertEquals(formal.identity, read.source.identity)
        assertEquals(1, formal.callStack.frames.size)
    }

    private fun constructorField(fixture: NativePacketValueFixture, literal: String): ValueSite {
        val expression =
            fixture.argumentExpression(
                PacketDeclaration(CONTROLS, "", "twoMatchedMapperCalls"),
                "ClientRequest",
                "\"$literal\"",
            )
        val site = resolve(fixture, expression)
        val argument = next(fixture, site, ValueTransferKind.ARGUMENT)
        return next(fixture, argument, ValueTransferKind.CONSTRUCTOR_FIELD)
    }

    private fun fieldStep(fixture: NativePacketValueFixture, constructor: ValueSite): ValueFlowStep {
        var site = constructor
        for (kind in
            listOf(ValueTransferKind.ARGUMENT, ValueTransferKind.SOURCE_CALL_ENTRY, ValueTransferKind.LOCAL_READ)) {
            site = next(fixture, site, kind)
        }
        return step(fixture, site)
    }

    private fun mapperArgument(fixture: NativePacketValueFixture, destination: String, token: String): ValueSite {
        val expression =
            fixture.argumentExpression(
                PacketDeclaration(CONTROLS, "", "twoMatchedMapperCalls"),
                "destinationOnly",
                "ClientRequest(destination = \"$destination\", sessionToken = \"$token\")",
            )
        return next(fixture, resolve(fixture, expression), ValueTransferKind.ARGUMENT)
    }

    private fun resolve(fixture: NativePacketValueFixture, expression: PacketExpression): ValueSite {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    domain = RelationRequest.start(expression.enclosing, RelationMeaning.References, fixture.budget),
                    model = fixture.model,
                )
        }
        assertTrue(result is ValueSiteResolution.Found)
        return (result as ValueSiteResolution.Found).site
    }

    private fun returned(fixture: NativePacketValueFixture, argument: ValueSite): ValueSite {
        var site = argument
        for (kind in
            listOf(
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueTransferKind.LOCAL_READ,
                ValueTransferKind.FIELD_READ,
                ValueTransferKind.ARGUMENT,
                ValueTransferKind.CONSTRUCTOR_FIELD,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
            )) {
            site = next(fixture, site, kind)
        }
        return site
    }

    private fun next(fixture: NativePacketValueFixture, site: ValueSite, kind: ValueTransferKind): ValueSite {
        val step = step(fixture, site)
        assertEquals(
            "Matched source transfer $kind is absent. " +
                "Source=${site.range}. Frontier=${step.obligations.map { it.cause }}",
            listOf(kind),
            step.transfers.map { it.kind },
        )
        return step.transfers.single().target
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
        assertTrue(result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/PlainFlowControls.kt"
        val SOURCES = listOf(CONTROLS)
    }
}
