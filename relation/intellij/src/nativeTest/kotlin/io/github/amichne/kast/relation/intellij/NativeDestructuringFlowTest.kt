package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueLocalVersion
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.runBlocking

/** Tests payload provenance and exact implicit invocation returns with compiler-resolved source declarations. */
class NativeDestructuringFlowTest : NativeDestructuringFixtureTest() {
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
        val result = next(fixture, stored, ValueTransferKind.FIELD_READ)
        val initial = next(fixture, result, ValueTransferKind.LOCAL_BINDING)
        assertTrue(initial.localVersion is ValueLocalVersion.Defined)
        assertEquals(result.range, initial.range)
        assertExhausted(step(fixture, initial))
    }

    fun testUnchangedMutableEntriesReachOnlyTheirExactSameTypedUses() {
        val fixture = controls()
        val function = "mutableEntryUnchanged"
        val first = mutableBinding(fixture, function, "first-value")
        val second = mutableBinding(fixture, function, "second-value")
        assertEquals(marked(fixture, function, "binding").anchor, first.range)
        assertEquals(marked(fixture, function, "other-binding").anchor, second.range)
        assertFalse(first.identity == second.identity)
        assertMutableReads(fixture, first, listOf(marked(fixture, function, "use")))
        assertMutableReads(fixture, second, listOf(marked(fixture, function, "other-use")))
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = marked(fixture, function, "binding").enclosing,
                    anchor = first.range,
                    role = ValueSiteRoleClaim.LocalBinding,
                    budget = fixture.budget,
                )
                .nativeRefined()
        val restored = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .revalidateSite(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue("Initial mutable entry did not restore: $restored", restored is ValueModelSiteRead.Revalidated)
        val site = (restored as ValueModelSiteRead.Revalidated).position.site
        assertEquals(first.localVersion, site.localVersion)
        assertEquals(ValueRole.LocalBinding, site.role)
        assertEquals(first.range, site.range)
        assertTrue(site.valuePath.segments.isEmpty())
        assertMutableReads(fixture, site, listOf(marked(fixture, function, "use")))
    }

    fun testMutableEntryReplacementCannotImportOldPayloadOrChangeItsSibling() {
        val fixture = controls()
        val function = "mutableEntryReassigned"
        val original = mutableBinding(fixture, function, "first-value")
        val sibling = mutableBinding(fixture, function, "second-value")
        assertMutableReads(fixture, original, listOf(marked(fixture, function, "before")))
        assertMutableReads(
            fixture,
            sibling,
            listOf(marked(fixture, function, "other-before"), marked(fixture, function, "other-after")),
        )
        val replacement =
            next(fixture, resolve(fixture, marked(fixture, function, "replacement")), ValueTransferKind.LOCAL_BINDING)
        assertTrue(replacement.localVersion is ValueLocalVersion.Defined)
        assertFalse(original.identity == replacement.identity)
        val assignment =
            ExactDeclarationTextRange.parse(
                    marked(fixture, function, "replacement-binding").anchor.startInclusive,
                    marked(fixture, function, "replacement").anchor.endExclusive,
                )
                .nativeRefined()
        assertEquals(assignment, replacement.range)
        assertEquals(
            (original.localVersion as ValueLocalVersion.Defined).declaration,
            (replacement.localVersion as ValueLocalVersion.Defined).declaration,
        )
        assertMutableReads(fixture, replacement, listOf(marked(fixture, function, "after")))
    }

    fun testMutableEntrySelfAssignmentReadsOldVersionAndDefinesNewVersion() {
        val fixture = controls()
        val function = "mutableEntrySelfAssigned"
        val initial = mutableBinding(fixture, function, "first-value")
        val rhs = marked(fixture, function, "rhs")
        assertMutableReads(fixture, initial, listOf(marked(fixture, function, "before"), rhs))
        val oldRead = step(fixture, initial).transfers.single { it.target.range == rhs.anchor }.target
        val replacement = next(fixture, oldRead, ValueTransferKind.LOCAL_BINDING)
        assertTrue(replacement.localVersion is ValueLocalVersion.Defined)
        assertFalse(initial.identity == replacement.identity)
        val assignment =
            ExactDeclarationTextRange.parse(
                    marked(fixture, function, "write").anchor.startInclusive,
                    rhs.anchor.endExclusive,
                )
                .nativeRefined()
        assertEquals(assignment, replacement.range)
        assertMutableReads(fixture, replacement, listOf(marked(fixture, function, "after")))
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

    private fun mutableBinding(fixture: NativePacketValueFixture, function: String, literal: String): ValueSite {
        val input = stored(fixture = fixture, function = function, constructor = "ComponentPacket", literal = literal)
        val result = next(fixture, input, ValueTransferKind.FIELD_READ)
        val binding = next(fixture, result, ValueTransferKind.LOCAL_BINDING)
        assertTrue(binding.localVersion is ValueLocalVersion.Defined)
        assertEquals(result.valuePath, binding.valuePath)
        assertEquals(result.callStack, binding.callStack)
        return binding
    }

    private fun assertMutableReads(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        expected: List<PacketExpression>,
    ) {
        val read = step(fixture, source)
        assertTrue("Unexpected mutable entry obligations: ${read.obligations}", read.obligations.isEmpty())
        assertEquals(expected.size, read.transfers.size)
        assertTrue(read.transfers.all { it.kind == ValueTransferKind.LOCAL_READ })
        assertEquals(expected.map { it.anchor }.toSet(), read.transfers.map { it.target.range }.toSet())
        assertTrue(read.transfers.all { it.target.enclosing == source.enclosing })
        assertTrue(read.transfers.all { it.target.valuePath.segments.isEmpty() })
    }
}
