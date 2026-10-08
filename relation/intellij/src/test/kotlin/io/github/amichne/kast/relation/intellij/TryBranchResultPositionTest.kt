package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueTryBranchAlternative
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

/** Physical PSI proves result position, never compiler type or exception feasibility. */
class TryBranchResultPositionTest {
    @Test
    fun `finally and abrupt result rejection report finite outcomes before compiler admission`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val cases =
                listOf(
                    "fun outer() = try { produced() } finally { cleanup() }" to
                        ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED,
                    "fun outer(): String { return try { return produced() } catch(e: Exception) { throw e } }" to
                        ValueFlowUnsupportedCause.ABRUPT_COMPLETION,
                )
            for ((text, expected) in cases) {
                val owner = factory.createFile(text).declarations.filterIsInstance<KtNamedFunction>().single()
                val branch =
                    PsiTreeUtil.findChildOfType(owner, KtTryExpression::class.java)!!.tryBlock.statements.single()
                val position = assertInstanceOf<TryBranchResultPosition.Result>(tryBranchResultPosition(branch))
                val counters = mutableListOf<IntellijReadCounter>()
                val observation =
                    object : IntellijReadObservation {
                        override fun count(
                            counter: IntellijReadCounter,
                            contributor: IntellijReadContributor,
                            amount: Int,
                        ) {
                            assertEquals(1, amount)
                            counters += counter
                        }

                        override fun phase(value: IntellijReadPhase) =
                            error("No compiler phase for rejected structural result")

                        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
                            error("Rejection is finite data")
                    }
                assertEquals(Refinement.Rejected(expected), NativeTryBranchResult.admit(branch, position, observation))
                assertEquals(
                    listOf(
                        IntellijReadCounter.VALUE_FLOW_BRANCH_RESULT_CANDIDATES,
                        if (expected == ValueFlowUnsupportedCause.FINALLY_UNSUPPORTED)
                            IntellijReadCounter.VALUE_FLOW_FINALLY_REJECTIONS
                        else IntellijReadCounter.VALUE_FLOW_ABRUPT_REJECTIONS,
                    ),
                    counters,
                )
            }
        }

    @Test
    fun `explicit returns cannot bypass an outer finally boundary`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val owner =
                factory
                    .createFile(
                        "fun outer(): String { try { try { return produced() } " +
                            "catch(e: Exception) { return fallback() } } finally { cleanup() }; return outside() }"
                    )
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val returns =
                PsiTreeUtil.findChildrenOfType(owner, KtReturnExpression::class.java).associateBy {
                    it.returnedExpression!!.text
                }
            assertTrue(returnCrossesFinally(returns.getValue("produced()"), owner))
            assertTrue(returnCrossesFinally(returns.getValue("fallback()"), owner))
            assertFalse(returnCrossesFinally(returns.getValue("outside()"), owner))
        }

    @Test
    fun `nested try results compose through independently located branches`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val owner =
                factory
                    .createFile(
                        "fun outer() = try { try { produced() } catch(e: Exception) { fallback() } } " +
                            "catch(e: Exception) { other() }"
                    )
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val produced =
                PsiTreeUtil.findChildrenOfType(owner, KtCallExpression::class.java).single { it.text == "produced()" }
            val first = assertInstanceOf<TryBranchResultPosition.Result>(tryBranchResultPosition(produced))
            val second = assertInstanceOf<TryBranchResultPosition.Result>(tryBranchResultPosition(first.enclosing))
            assertSame(second.enclosing.tryBlock, first.enclosing.parent)
            assertEquals(ValueTryBranchAlternative.TryBody, first.alternative)
            assertEquals(ValueTryBranchAlternative.TryBody, second.alternative)
        }

    @Test
    fun `only final try and catch expressions provide their own branch result`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val owner =
                factory
                    .createFile("fun outer() = try { ignored(); produced() } catch(e: Exception) { fallback() }")
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val enclosing = PsiTreeUtil.findChildOfType(owner, KtTryExpression::class.java)!!
            val calls = PsiTreeUtil.findChildrenOfType(owner, KtCallExpression::class.java).associateBy { it.text }
            assertEquals(TryBranchResultPosition.Discarded, tryBranchResultPosition(calls.getValue("ignored()")))
            val produced =
                assertInstanceOf<TryBranchResultPosition.Result>(tryBranchResultPosition(calls.getValue("produced()")))
            assertSame(enclosing, produced.enclosing)
            assertSame(enclosing.tryBlock, produced.branch)
            assertEquals(ValueTryBranchAlternative.TryBody, produced.alternative)
            val fallback =
                assertInstanceOf<TryBranchResultPosition.Result>(tryBranchResultPosition(calls.getValue("fallback()")))
            assertSame(enclosing.catchClauses.single().catchBody, fallback.branch)
            assertEquals(0, assertInstanceOf<ValueTryBranchAlternative.CatchBody>(fallback.alternative).index.value)
        }
}
