package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RepresentationStringDerivationOracleTest {
    @Test
    fun `string derivation weakens known representation and keeps the exact compiler transfer history`() {
        val case = StringDerivationRepresentationCase()
        val before = case.input(case.origin.known)
        assertInstanceOf(RepresentationCurrent.Known::class.java, before.branches.single().current)
        val transfer = case.transfer(before.site)
        val result = before.transfer(transfer).flowRefined()
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.UNMODELED_TRANSFORMATION),
            result.branches.single().current,
        )
        assertEquals(
            before.branches.single().history + RepresentationHistory.CompilerTransfer(transfer),
            result.branches.single().history,
        )
        assertSame(transfer.target, result.site)
        assertEquals(before.site.predicates, result.site.predicates)
        assertEquals(before.site.callStack, result.site.callStack)
        assertEquals(before.site.executionOwner, result.site.executionOwner)
    }

    @Test
    fun `string derivation preserves a preexisting unknown reason`() {
        val case = StringDerivationRepresentationCase()
        val before = case.input(case.origin.unknownInput())
        assertEquals(
            RepresentationCurrent.Unknown(RepresentationUnknownReason.INPUT_STATE_NOT_ESTABLISHED),
            before.branches.single().current,
        )
        val transfer = case.transfer(before.site)
        val result = before.transfer(transfer).flowRefined()
        assertEquals(before.branches.single().current, result.branches.single().current)
        assertEquals(
            before.branches.single().history + RepresentationHistory.CompilerTransfer(transfer),
            result.branches.single().history,
        )
    }

    @Test
    fun `interpolation stays compiler history and never becomes a reviewed representation application`() {
        val case = StringDerivationRepresentationCase()
        val before = case.input(case.origin.known)
        val transfer = case.transfer(before.site)
        val result = before.transfer(transfer).flowRefined()
        val history = result.branches.single().history
        assertEquals(before.branches.single().history, history.dropLast(1))
        assertEquals(RepresentationHistory.CompilerTransfer(transfer), history.last())
        assertEquals(
            before.branches.single().history.filterIsInstance<RepresentationHistory.Model>(),
            history.filterIsInstance<RepresentationHistory.Model>(),
        )
    }

    @Test
    fun `another scalar occurrence cannot borrow the origin representation for derivation`() {
        val case = StringDerivationRepresentationCase()
        val before = case.input(case.origin.known)
        val other = case.site(flowRange(47, 51), ValueRole.LocalRead)
        assertEquals(
            Refinement.Rejected(RepresentationPropagationFailure.SITE_MISMATCH),
            before.transfer(case.transfer(other)),
        )
    }
}
