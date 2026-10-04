package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

/** Actual PSI restoration and outcome instrumentation; these tests establish no K2 callable or argument binding. */
class ValueSiteRestorationTest {
    @Test
    fun `argument restoration retains its expression and reports successful shape refinement`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val owner =
                factory
                    .createFile("fun outer() { persist(\"account\", first) }")
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val call = PsiTreeUtil.findChildOfType(owner, KtCallExpression::class.java)!!
            val expected = call.valueArguments[1].getArgumentExpression()!!
            val observation = Observation()
            val result = owner.restoreValueSiteElement(range(expected), argumentClaim(call), observation)
            assertSame(expected, result.value())
            observation.assertOutcome(IntellijReadCounter.VALUE_SITE_SHAPES_RESTORED)
        }

    @Test
    fun `local binding restoration retains the exact property declaration`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val owner =
                factory
                    .createFile("fun outer() { val first = 123 }")
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val expected = PsiTreeUtil.findChildOfType(owner, KtProperty::class.java)!!
            val observation = Observation()
            val result = owner.restoreValueSiteElement(range(expected), ValueSiteRoleClaim.LocalBinding, observation)
            assertSame(expected, result.value())
            observation.assertOutcome(IntellijReadCounter.VALUE_SITE_SHAPES_RESTORED)
        }

    @Test
    fun `argument claim cannot reinterpret a property and reports shape rejection`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val owner =
                factory
                    .createFile("fun outer() { val first = 123; persist(\"account\", first) }")
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val property = PsiTreeUtil.findChildOfType(owner, KtProperty::class.java)!!
            val call = PsiTreeUtil.findChildOfType(owner, KtCallExpression::class.java)!!
            val observation = Observation()
            assertEquals(
                Refinement.Rejected(NativeValueSiteRestorationFailure.ROLE_SHAPE_UNSUPPORTED),
                owner.restoreValueSiteElement(range(property), argumentClaim(call), observation),
            )
            observation.assertOutcome(IntellijReadCounter.VALUE_SITE_SHAPES_REJECTED)
        }

    @Test
    fun `partial identifier anchor cannot become an expression and reports unavailable anchor`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val text = "fun outer() { persist(\"account\", first) }"
            val owner = factory.createFile(text).declarations.filterIsInstance<KtNamedFunction>().single()
            val start = text.indexOf("first")
            val partial = ExactDeclarationTextRange.parse(start, start + 3).value()
            val observation = Observation()
            assertEquals(
                Refinement.Rejected(NativeValueSiteRestorationFailure.ANCHOR_UNAVAILABLE),
                owner.restoreValueSiteElement(partial, ValueSiteRoleClaim.LocalRead, observation),
            )
            observation.assertOutcome(IntellijReadCounter.VALUE_SITE_ANCHORS_UNAVAILABLE)
        }

    private fun argumentClaim(call: KtCallExpression): ValueSiteRoleClaim.Argument {
        val selector =
            (RelationReadTest().request(RelationMeaning.References).subject as RelationEndpoint.Subject).selector
        return ValueSiteRoleClaim.Argument(range(call), selector, ValueArgumentPosition.parse(1).value())
    }

    private fun range(element: PsiElement) =
        ExactDeclarationTextRange.parse(element.textRange.startOffset, element.textRange.endOffset).value()

    private class Observation : IntellijReadObservation {
        private val counters = mutableListOf<IntellijReadCounter>()
        private val phases = mutableListOf<IntellijReadPhase>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            assertEquals(IntellijReadContributor.NONE, contributor)
            assertEquals(1, amount)
            counters += counter
        }

        override fun phase(value: IntellijReadPhase) {
            phases += value
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            error("Restoration must report bounded counters rather than terminate the read")
        }

        fun assertOutcome(outcome: IntellijReadCounter) {
            assertEquals(listOf(IntellijReadPhase.VALUE_SITE_RESTORATION), phases)
            assertEquals(listOf(IntellijReadCounter.VALUE_SITE_RESTORATIONS, outcome), counters)
        }
    }
}

private fun <V, F> Refinement<V, F>.value(): V = assertInstanceOf<Refinement.Refined<V>>(this).value
