package io.github.amichne.kast.protocol.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedScopeFamilyTest {
    @Test
    fun `scope proof rejects changed body receiver type and wrong with argument`() {
        val run = ImpactReviewedScopeFamilyFixture(ScopeFamily.RECEIVER_RUN)
        assertTrue(run.input.admitsEvidence())
        val changed =
            run.input.copy(
                binding =
                    run.input.binding.copy(
                        relation =
                            ImpactReviewedValueRelationDocument.CallbackReceiverInput(
                                run.receiverProof.copy(compilerType = run.base.text("kotlin/Int"))
                            )
                    )
            )
        assertFalse(changed.admitsEvidence())
        val with = ImpactReviewedScopeFamilyFixture(ScopeFamily.WITH)
        val argument = with.actual.role as ImpactValueRoleDocument.Argument
        assertFalse(
            with.input
                .copy(source = with.actual.copy(role = argument.copy(index = with.base.offset(1))))
                .admitsEvidence()
        )
    }

    @Test
    fun `scope input rejects dispatch substitution and top level receiver claims`() {
        val f = ImpactReviewedScopeFamilyFixture(ScopeFamily.RECEIVER_RUN)
        val wrong =
            f.input.copy(
                target =
                    f.input.target.copy(
                        role =
                            ImpactValueRoleDocument.Formal(
                                ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.DISPATCH)
                            )
                    )
            )
        assertFalse(wrong.admitsEvidence())
        assertFalse(ImpactReviewedScopeFamilyFixture(ScopeFamily.TOP_RUN).input.admitsEvidence())
        assertFalse(ImpactReviewedScopeFamilyFixture(ScopeFamily.APPLY).returned.admitsEvidence())
    }

    @Test
    fun `top level run admits an absent selected receiver and actual declared return`() {
        val f = ImpactReviewedScopeFamilyFixture(ScopeFamily.TOP_RUN)
        assertEquals(ImpactDeclaredCallbackReceiverDocument.Absent, f.binding.selected.receiver)
        assertTrue(f.returned.admitsEvidence())
    }

    @Test
    fun `receiver scopes keep actual formal owners and nested component paths`() {
        for (family in listOf(ScopeFamily.RECEIVER_RUN, ScopeFamily.WITH, ScopeFamily.APPLY)) {
            val f = ImpactReviewedScopeFamilyFixture(family)
            assertTrue(f.input.admitsEvidence())
            assertEquals(f.input.source.valuePath, f.input.target.valuePath)
            assertEquals(ImpactExecutionOwnerDocument.DeclaredCallback(f.declared), f.input.target.executionOwner)
        }
    }
}
