package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import kotlinx.coroutines.runBlocking

/** Tests payload provenance and exact implicit invocation returns with compiler-resolved source declarations. */
class NativeDestructuringFlowTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "destructuring-flow-jdk"

    fun testGeneratedComponentsKeepSameTypedFieldsAndEntryBindingsDistinct() {
        val fixture = controls()
        val first =
            stored(
                fixture = fixture,
                function = "generatedComponents",
                constructor = "ComponentPacket",
                literal = "first-value",
            )
        val second =
            stored(
                fixture = fixture,
                function = "generatedComponents",
                constructor = "ComponentPacket",
                literal = "second-value",
            )
        val firstResult = next(fixture, first, ValueTransferKind.FIELD_READ)
        val secondResult = next(fixture, second, ValueTransferKind.FIELD_READ)
        assertEquals(marked(fixture, "generatedComponents", "first-binding").anchor, firstResult.range)
        assertEquals(marked(fixture, "generatedComponents", "second-binding").anchor, secondResult.range)
        assertFalse(firstResult.identity == secondResult.identity)
        assertTrue(firstResult.valuePath.segments.isEmpty())
        assertTrue(secondResult.valuePath.segments.isEmpty())
        val firstRead =
            next(fixture, next(fixture, firstResult, ValueTransferKind.LOCAL_BINDING), ValueTransferKind.LOCAL_READ)
        val secondRead =
            next(fixture, next(fixture, secondResult, ValueTransferKind.LOCAL_BINDING), ValueTransferKind.LOCAL_READ)
        assertFalse(firstRead.range == secondRead.range)
    }

    fun testExplicitGeneratedComponentUsesTheSameSelectedField() {
        val fixture = controls()
        val first =
            stored(
                fixture = fixture,
                function = "explicitGeneratedComponent",
                constructor = "ComponentPacket",
                literal = "first-value",
            )
        val second =
            stored(
                fixture = fixture,
                function = "explicitGeneratedComponent",
                constructor = "ComponentPacket",
                literal = "second-value",
            )
        val selected = next(fixture, first, ValueTransferKind.FIELD_READ)
        assertTrue(selected.valuePath.segments.isEmpty())
        next(fixture, selected, ValueTransferKind.LOCAL_BINDING)
        assertExhausted(step(fixture, second))
    }

    fun testGeneratedCallSelectorCannotBeReinterpretedAsItsReceiver() {
        val fixture = controls()
        val selector = resolve(fixture, marked(fixture, "explicitGeneratedComponent", "selector"))
        assertUnsupported(step(fixture, selector), ValueFlowUnsupportedCause.UNMODELED_CALL)
    }

    fun testAuthoredComponentFollowsItsBodyRatherThanItsOrdinal() {
        val fixture = controls()
        val first =
            stored(
                fixture = fixture,
                function = "oneAuthoredComponent",
                constructor = "AuthoredComponents",
                literal = "first-value",
            )
        val second =
            stored(
                fixture = fixture,
                function = "oneAuthoredComponent",
                constructor = "AuthoredComponents",
                literal = "second-value",
            )
        val ignoredFormal =
            next(fixture, next(fixture, first, ValueTransferKind.RECEIVER), ValueTransferKind.SOURCE_CALL_ENTRY)
        assertExhausted(step(fixture, ignoredFormal))
        val returned = authoredReturn(fixture, second)
        assertEquals(marked(fixture, "oneAuthoredComponent", "binding").anchor, returned.range)
        next(fixture, returned, ValueTransferKind.LOCAL_BINDING)
    }

    fun testConstantAuthoredComponentDoesNotPreserveEitherPayload() {
        val fixture = controls()
        for (literal in listOf("first-value", "second-value")) {
            val stored =
                stored(
                    fixture = fixture,
                    function = "constantAuthoredComponent",
                    constructor = "AuthoredComponents",
                    literal = literal,
                )
            val receiver = next(fixture, stored, ValueTransferKind.RECEIVER)
            val formal = next(fixture, receiver, ValueTransferKind.SOURCE_CALL_ENTRY)
            assertExhausted(step(fixture, formal))
        }
    }

    fun testExplicitAuthoredComponentAgreesWithItsImplicitCall() {
        val fixture = controls()
        val stored =
            stored(
                fixture = fixture,
                function = "explicitAuthoredComponent",
                constructor = "AuthoredComponents",
                literal = "second-value",
            )
        next(fixture, authoredReturn(fixture, stored), ValueTransferKind.LOCAL_BINDING)
    }

    fun testTwoImplicitCallsReturnOnlyToTheirOwnEntries() {
        val fixture = controls()
        val first =
            stored(
                fixture = fixture,
                function = "twoAuthoredComponents",
                constructor = "AuthoredComponents",
                literal = "value-a",
            )
        val second =
            stored(
                fixture = fixture,
                function = "twoAuthoredComponents",
                constructor = "AuthoredComponents",
                literal = "value-b",
            )
        val firstReturned = authoredReturn(fixture, first)
        val secondReturned = authoredReturn(fixture, second)
        assertEquals(marked(fixture, "twoAuthoredComponents", "first-binding").anchor, firstReturned.range)
        assertEquals(marked(fixture, "twoAuthoredComponents", "second-binding").anchor, secondReturned.range)
        assertFalse(firstReturned.identity == secondReturned.identity)
        assertTrue(firstReturned.callStack.frames.isEmpty())
        assertTrue(secondReturned.callStack.frames.isEmpty())
    }

    fun testUnderscoreSkipsAnAlwaysThrowingOperator() {
        val fixture = controls()
        val stored =
            stored(
                fixture = fixture,
                function = "skippedComponent",
                constructor = "SkippedComponent",
                literal = "kept-value",
            )
        val receiver = next(fixture, stored, ValueTransferKind.RECEIVER)
        val call = (receiver.role as ValueRole.Receiver).call
        val expected = fixture.callableDeclaration(PacketDeclaration(CONTROLS, "SkippedComponent", "component2"))
        assertEquals(expected.compilerIdentity, call.callable.compilerIdentity)
        var site = receiver
        for (kind in
            listOf(
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueTransferKind.FIELD_READ,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueTransferKind.LOCAL_BINDING,
            )) {
            site = next(fixture, site, kind)
        }
        assertEquals(ValueRole.LocalBinding, site.role)
    }

    fun testMutableDestructuringKeepsItsObligation() {
        val fixture = controls()
        val stored =
            stored(
                fixture = fixture,
                function = "mutableDestructuring",
                constructor = "ComponentPacket",
                literal = "first-value",
            )
        assertUnsupported(step(fixture, stored), ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW)
    }

    fun testMutableGeneratedPropertyKeepsItsObligation() {
        val fixture = controls()
        val source = resolve(fixture, marked(fixture, "mutableComponent", "mutable-source"))
        assertUnsupported(step(fixture, source), ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    }

    fun testOrderedAuthoredComponentsCannotInventALaterSuccessfulBinding() {
        val fixture = controls()
        val stored =
            stored(
                fixture = fixture,
                function = "orderedComponents",
                constructor = "OrderedComponents",
                literal = "value",
            )
        assertUnsupported(step(fixture, stored), ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    fun testReverseReturnCannotBypassUnsupportedComponentOrder() {
        val fixture = controls()
        val expression =
            fixture.markedExpression(PacketDeclaration(CONTROLS, "OrderedComponents", "component2"), "ordered-return")
        val returned = next(fixture, resolve(fixture, expression), ValueTransferKind.RETURN)
        assertUnsupported(step(fixture, returned), ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }

    private fun controls() = packetFixture(listOf(CONTROLS))

    private fun marked(fixture: NativePacketValueFixture, function: String, marker: String) =
        fixture.markedExpression(owner(function), marker)

    private fun owner(function: String) = PacketDeclaration(CONTROLS, "", function)

    private fun stored(
        fixture: NativePacketValueFixture,
        function: String,
        constructor: String,
        literal: String,
    ): ValueSite {
        val expression = fixture.argumentExpression(owner(function), constructor, "\"$literal\"")
        val argument = next(fixture, resolve(fixture, expression), ValueTransferKind.ARGUMENT)
        return next(fixture, argument, ValueTransferKind.CONSTRUCTOR_FIELD)
    }

    private fun authoredReturn(fixture: NativePacketValueFixture, stored: ValueSite): ValueSite {
        var site = stored
        for (kind in
            listOf(
                ValueTransferKind.RECEIVER,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueTransferKind.FIELD_READ,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
            )) {
            site = next(fixture, site, kind)
        }
        return site
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
        assertTrue("Native source was not resolved: $result", result is ValueSiteResolution.Found)
        return (result as ValueSiteResolution.Found).site
    }

    private fun next(fixture: NativePacketValueFixture, source: ValueSite, kind: ValueTransferKind): ValueSite {
        val read = step(fixture, source)
        assertEquals(
            "Missing $kind at ${source.range}: ${read.obligations}",
            listOf(kind),
            read.transfers.map { it.kind },
        )
        assertTrue("Unexpected obligations: ${read.obligations}", read.obligations.isEmpty())
        return read.transfers.single().target
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("Native flow was not observed: $result", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun assertExhausted(step: ValueFlowStep) {
        assertTrue("Unexpected transfers: ${step.transfers}", step.transfers.isEmpty())
        assertTrue("Unexpected obligations: ${step.obligations}", step.obligations.isEmpty())
    }

    private fun assertUnsupported(step: ValueFlowStep, cause: ValueFlowUnsupportedCause) {
        assertTrue("Unsupported flow invented transfers", step.transfers.isEmpty())
        assertEquals(listOf(cause), step.obligations.map { it.cause }.distinct())
    }

    private companion object {
        const val CONTROLS = "src/main/kotlin/fixture/controls/DestructuringControls.kt"
    }
}
