package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue

/** Captured immutable reads inside proved scope callbacks and the nested safe-call `let` composition (F2). */
class NativeImpactCaptureGapTest : NativeImpactTransferFixture() {
    override val fixtureSdkName = "impact-capture-gap-jdk"

    // F2: captured immutable reads inside a proved scope callback, then a nested safe-call let.

    fun testDirectCapturedFieldControl() {
        val fixture = fixture(CAPTURE)
        val owner = PacketDeclaration(CAPTURE, "", "direct")
        val run = trace(fixture, owner)
        val read = fixture.markedExpression(owner, "read")
        assertTrue(run.summary(), run.sites.any { it.range == read.anchor && it.role == ValueRole.ExpressionResult })
        assertSupported("direct", run)
    }

    fun testCapturedReadEntersTheProvedApplyCallback() {
        val fixture = fixture(CAPTURE)
        val owner = PacketDeclaration(CAPTURE, "", "outerApply")
        val run = trace(fixture, owner)
        val read = fixture.markedExpression(owner, "read")
        val captured = run.sites.filter { read.anchor.covers(it.range) && it.role == ValueRole.LocalRead }
        assertEquals("The captured read did not enter apply: ${run.summary()}", 1, captured.size)
        val context = captured.single().executionOwner.context
        assertTrue("Captured read lost its callback owner: $context", context !is ValueExecutionContext.Declaration)
        assertTrue(
            "The captured field did not reach its callback read: ${run.summary()}",
            run.sites.any { it.range == read.anchor && it.role == ValueRole.ExpressionResult },
        )
        assertReceiverExcluded(fixture, owner, run)
        assertSupported("outerApply", run)
    }

    fun testLetBindsItsReceiverToTheSelectedFormal() {
        val fixture = fixture(CAPTURE)
        val owner = PacketDeclaration(CAPTURE, "", "letOnly")
        val run = trace(fixture, owner)
        assertLetRoute(fixture, owner, run)
        assertOnlyLetResultsRemain(
            function = "letOnly",
            fixture = fixture,
            owner = owner,
            run = run,
            reads = listOf("let-read"),
        )
    }

    fun testNestedLetInsideApplyKeepsBothCallbackOwners() {
        val fixture = fixture(CAPTURE)
        val owner = PacketDeclaration(CAPTURE, "", "nested")
        val run = trace(fixture, owner)
        val component = fixture.markedExpression(owner, "component-read")
        val reporting = fixture.markedExpression(owner, "reporting-read")
        assertTrue(
            "The component field did not reach its callback read: ${run.summary()}",
            run.sites.any { it.range == component.anchor && it.role == ValueRole.ExpressionResult },
        )
        assertLetRoute(fixture, owner, run)
        assertTrue(
            "The reporting formal did not receive its own field: ${run.summary()}",
            run.sites.any { it.range == reporting.anchor && it.role == ValueRole.LocalRead },
        )
        assertReceiverExcluded(fixture, owner, run)
        assertOnlyLetResultsRemain(
            function = "nested",
            fixture = fixture,
            owner = owner,
            run = run,
            reads = listOf("let-read", "reporting-read"),
        )
    }

    fun testCallbackConstructionAloneSuppliesNoExecutionWitness() {
        val fixture = fixture(CAPTURE, CAPTURE_CONTROLS)
        for ((function, marker) in
            listOf(
                "stored" to "stored-read",
                "ignored" to "ignored-read",
                "scheduled" to "scheduled-read",
                "shadowedApply" to "excluded-read",
            )) {
            val owner = PacketDeclaration(CAPTURE_CONTROLS, "", function)
            val run = trace(fixture, owner, producerPath = CAPTURE)
            val read = fixture.markedExpression(owner, marker)
            assertTrue(
                "$function: an unproved callback read acquired the selected value: ${run.summary()}",
                run.sites.none { read.anchor.covers(it.range) },
            )
        }
        for (function in listOf("stored", "scheduled")) {
            val owner = PacketDeclaration(CAPTURE_CONTROLS, "", function)
            val run = trace(fixture, owner, producerPath = CAPTURE)
            val read = fixture.markedExpression(owner, "${function}-read")
            assertTrue(
                "$function: the unproved execution must remain a visible obligation: ${run.summary()}",
                run.obligations.any { it.within(read.anchor) },
            )
        }
    }

    fun testAdapterArgumentDoesNotProveTheAdapterEffect() {
        val fixture = fixture(CAPTURE, CAPTURE_CONTROLS)
        val owner = PacketDeclaration(CAPTURE_CONTROLS, "", "adapterBoundary")
        val run = trace(fixture, owner, producerPath = CAPTURE)
        val argument = fixture.markedExpression(owner, "adapter-argument")
        assertTrue(
            "The proved field did not reach the adapter argument: ${run.summary()}",
            run.sites.any { it.range == argument.anchor && it.role is ValueRole.Argument },
        )
        assertTrue(
            "The opaque adapter effect was treated as proved: ${run.summary()}",
            run.obligations.isNotEmpty() || run.rejected.isNotEmpty(),
        )
    }

    private fun assertLetRoute(fixture: NativePacketValueFixture, owner: PacketDeclaration, run: TransferRun) {
        val receiver = fixture.markedExpression(owner, "let-receiver")
        val read = fixture.markedExpression(owner, "let-read")
        assertTrue(
            "The nested field did not reach the let receiver: ${run.summary()}",
            run.sites.any { it.range == receiver.anchor && it.role == ValueRole.ExpressionResult },
        )
        val formals = run.sites.filter { it.range == read.anchor && it.role == ValueRole.LocalRead }
        assertEquals("The let formal did not receive the selected field: ${run.summary()}", 1, formals.size)
        assertTrue(formals.single().valuePath.segments.isEmpty())
        val formal = formals.single()
        val callback = formal.executionOwner.context as ValueExecutionContext.ReviewedCallback
        val receiverSite = run.sites.single { it.range == receiver.anchor && it.role == ValueRole.ExpressionResult }
        assertEquals(receiverSite.executionOwner, callback.binding.parentExecutionOwner)
        assertEquals(
            listOf(receiver.anchor.startInclusive + "captured.conversion".length, receiver.anchor.endExclusive),
            formal.conditions.facts.map { fact ->
                (fact as ValueCondition.ReceiverNonNull).origin.range.endExclusive
            },
        )
    }

    private fun assertReceiverExcluded(fixture: NativePacketValueFixture, owner: PacketDeclaration, run: TransferRun) {
        val result = fixture.markedExpression(owner, "receiver-result")
        assertFalse(
            "A captured read inside apply became the receiver result: ${run.summary()}",
            run.sites.any { it.range == result.anchor },
        )
    }

    /**
     * Receiver-to-formal binding is proved; the `let` callback result's correspondence to the call result is a distinct
     * obligation that stays visible. Each remaining obligation is that return, inside its marked callback.
     */
    private fun assertOnlyLetResultsRemain(
        function: String,
        fixture: NativePacketValueFixture,
        owner: PacketDeclaration,
        run: TransferRun,
        reads: List<String>,
    ) {
        assertTrue("$function: native reads were rejected: ${run.summary()}", run.rejected.isEmpty())
        val callbacks = reads.map { fixture.markedExpression(owner, it).anchor }
        // A Unit-coerced final callback discards its result, so not every callback contributes a return obligation.
        assertTrue("$function: the let result obligation disappeared: ${run.summary()}", run.obligations.isNotEmpty())
        assertTrue(
            "$function: an obligation other than a let result remained: ${run.summary()}",
            run.obligations.all { obligation ->
                obligation.cause == ValueFlowUnsupportedCause.UNSUPPORTED_RETURN &&
                    obligation.site.role == ValueRole.Return &&
                    obligation.site.executionOwner.context is ValueExecutionContext.ReviewedCallback &&
                    callbacks.any { obligation.site.range.covers(it) }
            },
        )
    }
}
