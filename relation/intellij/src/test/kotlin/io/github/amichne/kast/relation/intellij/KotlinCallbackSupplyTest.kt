package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import java.nio.file.Path
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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
}
