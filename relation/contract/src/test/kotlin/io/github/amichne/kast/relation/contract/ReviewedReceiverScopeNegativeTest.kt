package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedReceiverScopeNegativeTest {
    @Test
    fun `apply discards callback result and never promotes field contents to receiver identity`() {
        val receiver = Any()
        val ignored = Any()
        assertFalse(receiver.apply { ignored } === ignored)
        val f = ReviewedReceiverScopeFixture(ReviewedEagerAdmissionCase.APPLY)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.callbackResult(f.returned(), f.binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.receiverResult(f.actual(), f.binding, f.declared),
        )
    }

    @Test
    fun `dispatch receiver and with callback argument cannot impersonate the callback receiver`() {
        val run = ReviewedReceiverScopeFixture()
        val with = ReviewedReceiverScopeFixture(ReviewedEagerAdmissionCase.WITH)
        val dispatch = run.actual(role = ValueRole.Receiver(run.invocation, ValueReceiverKind.DISPATCH))
        val block = with.actual(role = ValueRole.Argument(with.invocation, with.base.position(1)))
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedValueTransfer.callbackReceiverInput(dispatch, run.receiverProof()),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedValueTransfer.callbackReceiverInput(block, with.receiverProof()),
        )
    }

    @Test
    fun `receiver fact rejects absent receiver wrong type kind and anonymous owner`() {
        val f = ReviewedReceiverScopeFixture()
        val top = ReviewedReceiverScopeFixture(ReviewedEagerAdmissionCase.RUN_TOP_LEVEL)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedCallbackReceiver.fromCompiler(
                top.binding,
                top.declared,
                top.body,
                ValueReceiverKind.EXTENSION,
                top.receiverType,
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedCallbackReceiver.fromCompiler(
                f.binding,
                f.declared,
                f.body,
                ValueReceiverKind.DISPATCH,
                f.receiverType,
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
            ReviewedCallbackReceiver.fromCompiler(
                f.binding,
                f.declared,
                f.body,
                ValueReceiverKind.EXTENSION,
                CanonicalCompilerType.fromCompiler("fixture/Other").flowRefined(),
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedCallbackReceiver.fromCompiler(
                f.binding,
                f.declared,
                f.base.body,
                ValueReceiverKind.EXTENSION,
                f.receiverType,
            ),
        )
    }

    @Test
    fun `constant scope return cannot bypass the actual callback body`() {
        val receiver = Any()
        val constant = Any()
        assertFalse(receiver.run { constant } === receiver)
        assertFalse(with(receiver) { constant } === receiver)
        val f = ReviewedReceiverScopeFixture()
        val entered = ReviewedValueTransfer.callbackReceiverInput(f.actual(), f.receiverProof()).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(entered.target, f.binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.callbackResult(f.returned(ValueRole.ExpressionResult), f.binding),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.receiverResult(f.actual(ValueAccessPath.Whole), f.binding, f.declared),
        )
    }

    @Test
    fun `abrupt apply has no unconditional receiver result and cannot synthesize invocation evidence`() {
        val thrown = IllegalStateException("expected")
        val oracle = runCatching { Any().apply { throw thrown } }
        assertEquals(thrown, oracle.exceptionOrNull())
        val f = ReviewedReceiverScopeFixture(ReviewedEagerAdmissionCase.APPLY)
        val source = f.actual(ValueAccessPath.Whole)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueCondition.ReviewedCallbackNormalReturn.fromReviewed(source, f.binding),
        )
        val qualified = ReviewedValueTransfer.receiverResult(source, f.binding, f.declared).flowRefined()
        assertTrue(qualified.target.conditions.facts.single() is ValueCondition.ReviewedCallbackNormalReturn)
        assertEquals(false, source.conditions.admits(source, qualified.target, ValueTransferKind.WRAPPER_RETURN))
    }

    @Test
    fun `unrelated invocation evidence and plain execution cannot authorize a scope return`() {
        val run = ReviewedReceiverScopeFixture()
        val apply = ReviewedReceiverScopeFixture(ReviewedEagerAdmissionCase.APPLY)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedCallbackReceiver.fromCompiler(
                run.binding,
                apply.declared,
                run.body,
                ValueReceiverKind.EXTENSION,
                run.receiverType,
            ),
        )
        val plain = run.base.source(owner = ValueExecutionOwner.declaration(run.base.enclosing))
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.callbackResult(plain, run.binding),
        )
    }

    @Test
    fun `receiver callback rejects extra value and context parameters`() {
        val cases =
            listOf(
                ReviewedReceiverScopeFixture(valueParameters = listOf("kotlin/String")),
                ReviewedReceiverScopeFixture(contextReceivers = listOf("kotlin/String")),
            )
        for (f in cases) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION),
                ReviewedCallbackReceiver.fromCompiler(
                    f.binding,
                    f.declared,
                    f.body,
                    ValueReceiverKind.EXTENSION,
                    f.receiverType,
                ),
            )
        }
    }
}
