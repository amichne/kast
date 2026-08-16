package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.ChangeIntent
import io.github.amichne.kast.change.contract.KotlinIdentifier
import io.github.amichne.kast.change.contract.RenameSymbolOccurrence
import io.github.amichne.kast.change.contract.RenameSymbolOccurrenceRole
import io.github.amichne.kast.change.contract.RenameSymbolOccurrenceSet
import io.github.amichne.kast.change.contract.RenameSymbolPlanningFailure
import io.github.amichne.kast.change.contract.RenameSymbolPlanRequest
import io.github.amichne.kast.change.contract.RenameSymbolPlanResult
import io.github.amichne.kast.change.contract.SourceTextMutation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class RenameSymbolPlanTest {
    private val fixture = AddDeclarationPlanFixture()

    @Test
    fun `compiler-grounded occurrences produce one exact deterministic rename plan`() {
        val base = fixture.request()
        val occurrence = RenameSymbolOccurrence.admit(
            base.target.file,
            base.target.range,
            KotlinIdentifier.parse("service").refined(),
            RenameSymbolOccurrenceRole.DECLARATION,
        ).refined()
        val request = RenameSymbolPlanRequest(
            base.target,
            KotlinIdentifier.parse("renamedService").refined(),
            RenameSymbolOccurrenceSet.admit(base.target, listOf(occurrence)).refined(),
            base.evidence,
        )

        val first = PureRenameSymbolPlanningService().plan(request).planned()
        val second = PureRenameSymbolPlanningService().plan(request).planned()

        assertEquals(first.planId, second.planId)
        assertInstanceOf(ChangeIntent.RenameSymbol::class.java, first.intent)
        val write = first.writes.entries.single()
        assertEquals(base.target.file, write.source)
        val replacement = assertInstanceOf(
            SourceTextMutation.Replace::class.java,
            write.mutations.single(),
        )
        assertEquals(base.target.range, replacement.range)
        assertEquals("renamedService", replacement.replacement.value)
    }

    @Test
    fun `caller supplied references must equal complete compiler relation evidence`() {
        val base = fixture.request()
        val name = KotlinIdentifier.parse("service").refined()
        val declaration = RenameSymbolOccurrence.admit(
            base.target.file,
            base.target.range,
            name,
            RenameSymbolOccurrenceRole.DECLARATION,
        ).refined()
        val inventedReference = RenameSymbolOccurrence.admit(
            base.target.file,
            io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
                .parse(20, 27)
                .refined(),
            name,
            RenameSymbolOccurrenceRole.REFERENCE,
        ).refined()
        val result = PureRenameSymbolPlanningService().plan(
            RenameSymbolPlanRequest(
                base.target,
                KotlinIdentifier.parse("renamedService").refined(),
                RenameSymbolOccurrenceSet.admit(
                    base.target,
                    listOf(declaration, inventedReference),
                ).refined(),
                base.evidence,
            ),
        )

        val rejected = assertInstanceOf(RenameSymbolPlanResult.Rejected::class.java, result)
        assertEquals(
            RenameSymbolPlanningFailure.OCCURRENCE_EVIDENCE_MISMATCH,
            rejected.failure,
        )
    }

    private fun RenameSymbolPlanResult.planned() = when (this) {
        is RenameSymbolPlanResult.Planned -> plan
        is RenameSymbolPlanResult.Rejected -> error(failure.toString())
    }
}
