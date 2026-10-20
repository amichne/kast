package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueModelApplication
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Required original positions and separate controls inspect emitted facts, without a receiver-to-result rule. */
class NativePacketLetCallbackTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-let-callback-jdk"

    fun testOriginalLetReceiverEntersItsExactImplicitItAndKeepsBothScopedPredicates() {
        val source = source(original = true)
        val facts = source.original()
        val application = entry(source, facts)
        val predicates = application.target.predicates.entries
        assertEquals(1, predicates.filterIsInstance<ValuePredicate.Subtype>().size)
        val nonNull = predicates.filterIsInstance<ValuePredicate.NonNull>().single()
        assertEquals(exact(THIS_START, THIS_END), nonNull.subject.occurrence.range)
        assertEquals(ValueRole.LocalRead, nonNull.subject.occurrence.role)
        assertEquals(facts.receiver.callStack, nonNull.subject.occurrence.callStack)
        val reads = source.read(application.target)
        assertEquals(
            listOf(exact(IT_START, IT_END)),
            reads.transfers.filter { it.kind == ValueTransferKind.LOCAL_READ }.map { it.target.range },
        )
        println("LET_CALLBACK_RESULT original_entry=EXACT C2.8_C2.9_full_route=NOT_ASSERTED")
    }

    fun testOriginalNormalSuccessCallbackResultReturnsOnlyToItsMatchedLetCall() {
        val source = source(original = true)
        val facts = source.original()
        returned(source, facts)
        println("LET_CALLBACK_RESULT original_normal_result=MATCHED_LET full_S2=NOT_ASSERTED")
    }

    fun testConstantCallbackEntersItsGenuineUnusedFormalWithoutPayloadToConstantResult() {
        val source = source(original = false)
        val facts = source.control("constant")
        assertTrue(
            "K2 must prove that the actual constant callback has no input references",
            facts.references.isEmpty(),
        )
        val formal = entry(source, facts).target
        val read = source.read(formal)
        assertTrue("An unused callback formal must emit no input-derived compiler edge", read.transfers.isEmpty())
        assertTrue("An unused callback formal must emit no input-derived modeled edge", read.applications.isEmpty())
        assertTrue(read.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, read.terminal)
    }

    fun testIndependentConstantLiteralNormalResultReturnsToItsOwnLetCall() {
        val source = source(original = false)
        val facts = source.control("constant")
        assertTrue(facts.references.isEmpty())
        returned(source, facts)
    }

    fun testTwoActualLetCallsKeepDifferentFormalsAndNeverCrossTheirReturns() {
        val source = source(original = false)
        val first = source.control("twoCalls", index = 0)
        val second = source.control("twoCalls", index = 1)
        assertEquals(first.binding.invocation.callable.valueIdentity, second.binding.invocation.callable.valueIdentity)
        assertFalse(first.binding.invocation.range == second.binding.invocation.range)
        assertFalse(first.formal.executionOwner == second.formal.executionOwner)
        val firstEntry = entry(source, first)
        val secondEntry = entry(source, second)
        assertFalse(firstEntry.target.role == secondEntry.target.role)
        assertFalse(firstEntry.target.callStack == secondEntry.target.callStack)
        val firstResult = returned(source, first)
        val secondResult = returned(source, second)
        assertFalse(firstResult.target.range == second.binding.invocation.range)
        assertFalse(secondResult.target.range == first.binding.invocation.range)
    }

    fun testActualExtensionOnlyLetCannotAdmitTheSameCallAsDispatchInput() {
        val source = source(original = false)
        val facts = source.control("identity")
        val call = facts.binding.invocation
        assertEquals(ValueReceiverKind.EXTENSION, (facts.receiver.role as ValueRole.Receiver).kind)
        val wrong =
            ValueSite.fromCompiler(
                    enclosing = facts.receiver.enclosing,
                    range = facts.receiver.range,
                    role = ValueRole.Receiver(call, ValueReceiverKind.DISPATCH),
                    executionOwner = facts.receiver.executionOwner,
                    valuePath = facts.receiver.valuePath,
                    predicates = facts.receiver.predicates,
                    callStack = facts.receiver.callStack,
                )
                .nativeRefined()
        when (val result = source.readRaw(wrong)) {
            is ValueFlowRead.Rejected -> assertEquals(ValueFlowRejection.STALE_SITE, result.cause)
            is ValueFlowRead.Observed -> assertNoCallbackArrival(result.step)
            is ValueFlowRead.Suspended -> fail("An invalid receiver slot must not suspend a source scan")
            is ValueFlowRead.ContractRejected -> fail("An invalid receiver slot must not break the core contract")
        }
    }

    private fun entry(source: NativePacketLetCallbackSource, facts: LetCallbackFacts): ValueModelApplication {
        val step = source.read(facts.receiver)
        val entries = step.applications.filter { it.target.role is ValueRole.Formal }
        assertEquals(
            "Let needs receiver-to-actual-formal model evidence. Causes=${step.obligations.map { it.cause }}. " +
                "The callback result is NOT_REACHED",
            1,
            entries.size,
        )
        val application = entries.single()
        assertEquals(facts.receiver, application.source)
        assertEquals(facts.binding, application.binding)
        assertEquals("scope.let.invoke-block-value", application.reference.rule.value)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Parameter(facts.formal)), application.target.role)
        assertEquals(facts.formal.executionOwner, application.target.executionOwner)
        assertEquals(facts.formal.range, application.target.range)
        assertEquals(facts.receiver.valuePath, application.target.valuePath)
        assertEquals(facts.receiver.predicates, application.target.predicates)
        val frame = application.target.callStack.frames.last()
        assertEquals(facts.binding.invocation, frame.invocation)
        assertEquals(facts.formal.executionOwner, frame.callee)
        assertEquals(facts.receiver.callStack.frames + frame, application.target.callStack.frames)
        assertTrue(
            "The compiled library declaration must not become the callback body",
            step.transfers.none { transfer ->
                transfer.kind == ValueTransferKind.SOURCE_CALL_ENTRY
            },
        )
        assertTrue(
            step.applications.none { application ->
                application.target.role == ValueRole.ExpressionResult &&
                    application.target.range == facts.binding.invocation.range
            }
        )
        return application
    }

    private fun returned(source: NativePacketLetCallbackSource, facts: LetCallbackFacts): ValueModelApplication {
        val normal = source.normalReturn(facts)
        val step = source.read(normal)
        val returns = step.applications.filter { application -> application.target.role == ValueRole.ExpressionResult }
        assertEquals(
            "A genuine normal callback Return needs the same Let result. Causes=${step.obligations.map { it.cause }}",
            1,
            returns.size,
        )
        val application = returns.single()
        assertEquals(normal, application.source)
        assertEquals(facts.binding, application.binding)
        assertEquals(facts.binding.invocation.range, application.target.range)
        assertEquals(facts.receiver.enclosing, application.target.enclosing)
        assertEquals(facts.receiver.executionOwner, application.target.executionOwner)
        assertEquals(normal.valuePath, application.target.valuePath)
        assertEquals(normal.predicates, application.target.predicates)
        assertEquals(facts.receiver.callStack, application.target.callStack)
        assertEquals(normal.callStack.frames.dropLast(1), application.target.callStack.frames)
        assertTrue(
            "A reviewed return must not impersonate a compiler transfer",
            step.transfers.none { transfer ->
                transfer.kind == ValueTransferKind.SOURCE_CALL_RETURN &&
                    transfer.target.range == facts.binding.invocation.range
            },
        )
        return application
    }

    private fun assertNoCallbackArrival(step: ValueFlowStep) {
        assertTrue("A wrong receiver slot acquired a modeled callback arrival", step.applications.isEmpty())
        assertTrue(
            "A wrong receiver slot acquired a compiler callback arrival",
            step.transfers.none { transfer ->
                transfer.target.role is ValueRole.Formal || transfer.target.role == ValueRole.ExpressionResult
            },
        )
    }

    private fun source(original: Boolean): NativePacketLetCallbackSource {
        val paths =
            if (original) NativePacketCompositeSuccessSource.PATHS else listOf(NativePacketLetCallbackSource.CONTROL)
        val fixture = packetFixture(paths)
        return NativePacketLetCallbackSource(project, getOrCreateProjectBaseDir(), fixture)
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val THIS_START = 1622
        const val THIS_END = 1626
        const val IT_START = 1650
        const val IT_END = 1652
    }
}
