package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.ChangeIntent
import io.github.amichne.kast.change.contract.ExistingDeclarationSourceText
import io.github.amichne.kast.change.contract.ReplacementDeclarationSourceText
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanRequest
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanResult
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanningFailure
import io.github.amichne.kast.change.contract.ReplaceDeclarationTarget
import io.github.amichne.kast.change.contract.ReplaceDeclarationTargetFailure
import io.github.amichne.kast.change.contract.SourceTextMutation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ReplaceDeclarationPlanTest {
    private val currentDeclaration = "fun service(): Int = 0"
    private val fixture = AddDeclarationPlanFixture(
        declarationEndExclusive = 10 + currentDeclaration.length,
    )

    @Test
    fun `exact compiler-grounded declaration produces one deterministic replacement plan`() {
        val base = fixture.request()
        val target = ReplaceDeclarationTarget.admit(
            base.target,
            ExistingDeclarationSourceText.parse(currentDeclaration).refined(),
        ).refined()
        val request = ReplaceDeclarationPlanRequest(
            target,
            ReplacementDeclarationSourceText.parse("fun service(): Int = 1").refined(),
            base.evidence,
        )

        val first = PureReplaceDeclarationPlanningService().plan(request).planned()
        val second = PureReplaceDeclarationPlanningService().plan(request).planned()

        assertEquals(first.planId, second.planId)
        assertInstanceOf(ChangeIntent.ReplaceDeclaration::class.java, first.intent)
        val write = first.writes.entries.single()
        assertEquals(base.target.file, write.source)
        val replacement = assertInstanceOf(
            SourceTextMutation.ReplaceDeclaration::class.java,
            write.mutations.single(),
        )
        assertEquals(base.target.range, replacement.range)
        assertEquals(currentDeclaration, replacement.expected.value)
        assertEquals("fun service(): Int = 1", replacement.replacement.value)
    }

    @Test
    fun `declaration preimage must cover the exact compiler-grounded range`() {
        val base = fixture.request()

        val result = ReplaceDeclarationTarget.admit(
            base.target,
            ExistingDeclarationSourceText.parse("service").refined(),
        )

        val rejected = assertInstanceOf(io.github.amichne.kast.kernel.Refinement.Rejected::class.java, result)
        assertEquals(ReplaceDeclarationTargetFailure.RANGE_LENGTH_MISMATCH, rejected.failure)
    }

    @Test
    fun `unchanged declaration is a closed planning rejection`() {
        val base = fixture.request()
        val target = ReplaceDeclarationTarget.admit(
            base.target,
            ExistingDeclarationSourceText.parse(currentDeclaration).refined(),
        ).refined()

        val result = PureReplaceDeclarationPlanningService().plan(
            ReplaceDeclarationPlanRequest(
                target,
                ReplacementDeclarationSourceText.parse(currentDeclaration).refined(),
                base.evidence,
            ),
        )

        val rejected = assertInstanceOf(ReplaceDeclarationPlanResult.Rejected::class.java, result)
        assertEquals(ReplaceDeclarationPlanningFailure.REPLACEMENT_UNCHANGED, rejected.failure)
    }

    private fun ReplaceDeclarationPlanResult.planned() = when (this) {
        is ReplaceDeclarationPlanResult.Planned -> plan
        is ReplaceDeclarationPlanResult.Rejected -> error(failure.toString())
    }
}
