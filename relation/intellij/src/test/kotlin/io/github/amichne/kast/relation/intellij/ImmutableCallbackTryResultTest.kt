package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.ValueTryBranchAlternative
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

/** Physical PSI proves candidates and guarded returns, never K2 value or execution proof. */
class ImmutableCallbackTryResultTest {
    @Test
    fun `try suppliers preserve every final normal alternative and catch ordinal`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val function =
                factory
                    .createFile(
                        "fun factory() = try { ignored(); ::alpha } catch(e: IllegalStateException) { ::beta } " +
                            "catch(e: Exception) { ::gamma }"
                    )
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val expression = PsiTreeUtil.findChildOfType(function, KtTryExpression::class.java)!!
            val candidates =
                assertInstanceOf<Refinement.Refined<List<ImmutableCallbackTryResult>>>(
                        immutableCallbackTryResults(expression) { Refinement.Refined(Unit) }
                    )
                    .value
            assertEquals(listOf("::alpha", "::beta", "::gamma"), candidates.map { it.expression.text })
            assertEquals(ValueTryBranchAlternative.TryBody, candidates[0].position.alternative)
            assertEquals(
                listOf(0, 1),
                candidates.drop(1).map {
                    (it.position.alternative as ValueTryBranchAlternative.CatchBody).index.value
                },
            )
            candidates.forEach { assertSame(expression, it.position.enclosing) }
        }

    @Test
    fun `finally and empty branches never manufacture callback alternatives`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            for ((text, expected) in
                listOf(
                    "fun factory() = try { ::alpha } finally { cleanup() }" to
                        CallbackInvocationFlowCause.FINALLY_UNSUPPORTED,
                    "fun factory() = try { ::alpha } catch(e: Exception) {}" to
                        CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY,
                )) {
                val function = factory.createFile(text).declarations.filterIsInstance<KtNamedFunction>().single()
                val expression = PsiTreeUtil.findChildOfType(function, KtTryExpression::class.java)!!
                assertEquals(
                    Refinement.Rejected(expected),
                    immutableCallbackTryResults(expression) { Refinement.Refined(Unit) },
                )
            }
        }

    @Test
    fun `factory returned expression rejects finally override while retaining ordinary returns`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val function =
                factory
                    .createFile(
                        """
                        fun factory(): () -> String {
                            try { return ::alpha } finally { return ::beta }
                            return ::gamma
                        }
                        """
                            .trimIndent()
                    )
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val returns =
                PsiTreeUtil.findChildrenOfType(function, KtReturnExpression::class.java).associateBy {
                    it.returnedExpression!!.text
                }
            for (name in listOf("::alpha", "::beta")) assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.FINALLY_UNSUPPORTED),
                factoryReturnedExpression(returns.getValue(name), function),
            )
            val admitted =
                assertInstanceOf<Refinement.Refined<*>>(
                        factoryReturnedExpression(returns.getValue("::gamma"), function)
                    )
                    .value
            assertSame(returns.getValue("::gamma").returnedExpression, admitted)
        }

    @Test
    fun `try callback candidates cannot outrun the supplied work grant`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val function =
                factory
                    .createFile("fun factory() = try { ::alpha } catch(e: Exception) { ::beta }")
                    .declarations
                    .filterIsInstance<KtNamedFunction>()
                    .single()
            val expression = PsiTreeUtil.findChildOfType(function, KtTryExpression::class.java)!!
            var calls = 0
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
                immutableCallbackTryResults(expression) {
                    calls++
                    if (calls == 1) Refinement.Refined(Unit)
                    else Refinement.Rejected(CallbackInvocationFlowCause.WORK_LIMIT_REACHED)
                },
            )
            assertEquals(2, calls)
        }
}
