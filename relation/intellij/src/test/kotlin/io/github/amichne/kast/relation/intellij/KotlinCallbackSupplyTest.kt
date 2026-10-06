package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Native PSI supply classification only; the parser does not establish K2 mapping or runtime activation. */
class KotlinCallbackSupplyTest {
    @Test
    fun `parenthesized literal callee retains its exact invocation and is not stored`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val expression = "({ target() })()"
            val file = factory.createFile("fun outer() = $expression")
            assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
            val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
            val supply = classifyCallbackLambdaSupply(lambda) as IntellijCallbackLambdaSupply.Invocation
            assertEquals(expression, supply.call.text)
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY),
                callbackSupplyArgument(lambda),
            )
        }

    @Test
    fun `explicit literal invoke retains qualified call and other receiver methods remain unsupported`(
        @TempDir home: Path
    ) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            for (expression in listOf("({ target() }).invoke()", "(fun() { target() }).invoke()")) {
                val file = factory.createFile("fun outer() = $expression")
                assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
                val function =
                    PsiTreeUtil.findChildrenOfType(file, org.jetbrains.kotlin.psi.KtFunction::class.java).single {
                        it is org.jetbrains.kotlin.psi.KtFunctionLiteral ||
                            it is org.jetbrains.kotlin.psi.KtNamedFunction && it.name == null
                    }
                val supply = classifyCallbackFunctionSupply(function) as IntellijCallbackLambdaSupply.Invocation
                assertEquals(expression, supply.call.valueInvocationExpression().text)
            }
            val file = factory.createFile("fun outer() = ({ target() }).other()")
            val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
            assertSame(IntellijCallbackLambdaSupply.Unsupported, classifyCallbackLambdaSupply(lambda))
        }

    @Test
    fun `custom invoke overload shapes retain syntax without proving callback activation`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            for (expression in listOf("({ target() }).invoke(1)", "({ target() })(1)")) {
                val file =
                    factory.createFile(
                        "fun target(): Unit = Unit\n" +
                            "operator fun (() -> Unit).invoke(ignored: Int) {}\n" +
                            "fun outer(): Unit = $expression"
                    )
                assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
                val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
                val supply = classifyCallbackLambdaSupply(lambda) as IntellijCallbackLambdaSupply.Invocation
                assertEquals(expression, supply.call.valueInvocationExpression().text)
                assertEquals("1", supply.call.valueArguments.single().getArgumentExpression()!!.text)
            }
        }

    @Test
    fun `local direct property initializer proves storage after parentheses are unwrapped`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val file = factory.createFile("fun outer() { val callback = ({ target() }); callback() }")
            val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
            assertSame(IntellijCallbackLambdaSupply.Stored, classifyCallbackLambdaSupply(lambda))
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.STORED_CALLBACK),
                callbackSupplyArgument(lambda),
            )
        }

    @Test
    fun `expression body returning a literal retains its exact returned value`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val expression = "({ target() })"
            val file = factory.createFile("fun outer(): () -> Int = $expression")
            val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
            val supply = classifyCallbackLambdaSupply(lambda) as IntellijCallbackLambdaSupply.Returned
            assertEquals(expression, supply.occurrence.text)
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.RETURNED_CALLBACK),
                callbackSupplyArgument(lambda),
            )
        }

    @Test
    fun `explicit return retains its source occurrence without claiming storage`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val expression = "return ({ target() })"
            val file = factory.createFile("fun outer(): () -> Int { $expression }")
            val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
            val supply = classifyCallbackLambdaSupply(lambda) as IntellijCallbackLambdaSupply.Returned
            assertEquals(expression, supply.occurrence.text)
            assertEquals(
                Refinement.Rejected(CallbackInvocationFlowCause.RETURNED_CALLBACK),
                callbackSupplyArgument(lambda),
            )
        }

    @Test
    fun `unknown conditional value supply remains unsupported rather than inferred stored`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val file = factory.createFile("fun outer() { val callback = if (true) ({ target() }) else ({ other() }) }")
            val lambdas = PsiTreeUtil.findChildrenOfType(file, KtLambdaExpression::class.java)
            assertEquals(2, lambdas.size)
            lambdas.forEach { lambda ->
                assertSame(IntellijCallbackLambdaSupply.Unsupported, classifyCallbackLambdaSupply(lambda))
                assertEquals(
                    Refinement.Rejected(CallbackInvocationFlowCause.UNSUPPORTED_CALLBACK_SUPPLY),
                    callbackSupplyArgument(lambda),
                )
            }
        }

    @Test
    fun `direct argument preserves its actual parenthesized mapping expression`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val expression = "({ target() })"
            val file = factory.createFile("fun outer() = consume($expression)")
            val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
            val supply = (callbackSupplyArgument(lambda) as Refinement.Refined).value
            assertEquals(expression, supply.expression.text)
            assertSame(supply.expression, supply.argument.getArgumentExpression())
            assertEquals("consume($expression)", supplyingCallbackCall(supply.argument)!!.text)
        }

    @Test
    fun `labelled callback argument retains compiler mapping expression`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val file = factory.createFile("fun outer() = consume(label@ { target() })")
            val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
            val supply = classifyCallbackLambdaSupply(lambda)
            assertTrue(supply is IntellijCallbackLambdaSupply.Argument)
            assertEquals("label@ { target() }", (supply as IntellijCallbackLambdaSupply.Argument).expression.text)
        }

    @Test
    fun `default lambda is a known formal supply rather than unsupported`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            for (modifier in listOf("", "inline ")) {
                val file = factory.createFile("${modifier}fun outer(block: () -> Int = { target() }) = block()")
                val lambda = PsiTreeUtil.findChildOfType(file, KtLambdaExpression::class.java)!!
                assertNotEquals(IntellijCallbackLambdaSupply.Unsupported, classifyCallbackLambdaSupply(lambda))
            }
        }

    @Test
    fun `anonymous function is retained as a callable boundary`(@TempDir home: Path) =
        KotlinCallOwnershipTest().withParser(home) { factory ->
            val file = factory.createFile("fun outer() = consume(fun(): Int { return target() })")
            val call =
                PsiTreeUtil.findChildrenOfType(file, org.jetbrains.kotlin.psi.KtCallExpression::class.java).single {
                    it.calleeExpression?.text == "target"
                }
            assertTrue(call.nearestDeclaration() is ContainingDeclaration.Deferred)
        }
}
