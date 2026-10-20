@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/** Exact semantic restoration precedes the safe-call guard and the reviewed let dependency. */
class NativePacketThisRestorationTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-this-restoration-jdk"

    fun testOriginalNullableThisRestoresItsSemanticExpressionAfterExactK2SafeNavigation() {
        packetFixture(listOf(OUTCOME))
        ReadAction.run<RuntimeException> {
            val function = function("requireNotNull")
            val expected = thisExpression(function, exact(REQUIRE_THIS_START, REQUIRE_THIS_END))
            val qualified = expected.parent as KtSafeQualifiedExpression
            val call = qualified.selectorExpression as KtCallExpression
            IntellijReadObservation.None.observedAnalyze(call) {
                val reference = expected.instanceReference.references.filterIsInstance<KtReference>().single()
                assertEquals(function.symbol.receiverParameter, reference.resolveToSymbol())
                val resolved = checkNotNull(call.resolveCall())
                assertNull(resolved.dispatchReceiver)
                val receiver = resolved.extensionReceiver as KaExplicitReceiverValue
                assertTrue("K2 must establish safe navigation on this exact receiver", receiver.isSafeNavigation)
                assertSame(expected.originalElement, receiver.expression.originalElement)
                val projected = resolved.signature.symbol.compilerProjection(this)
                assertTrue(projected is IntellijCompilerProjectionResult.Projected)
                assertEquals(
                    expectedLet(),
                    (projected as IntellijCompilerProjectionResult.Projected).projection.signature,
                )
            }
            println("THIS_RESTORATION_K2 checked=1622:1626 safe_navigation=TRUE extension=EXACT let=EXACT")
            assertRestoration(function, expected)
        }
    }

    fun testOriginalBindThisRestoresItsSemanticExpressionWithTheExactExtensionSymbol() {
        packetFixture(listOf(OUTCOME))
        ReadAction.run<RuntimeException> {
            val function = function("bind")
            val expected = thisExpression(function, exact(BIND_THIS_START, BIND_THIS_END))
            IntellijReadObservation.None.observedAnalyze(expected) {
                val reference = expected.instanceReference.references.filterIsInstance<KtReference>().single()
                val resolved = reference.resolveToSymbol()
                assertEquals(function.symbol.receiverParameter, resolved)
                assertFalse(function.symbol.containingDeclaration == resolved)
            }
            println("THIS_RESTORATION_K2 checked=1189:1193 extension=EXACT distinct_dispatch=TRUE")
            assertRestoration(function, expected)
        }
    }

    fun testOriginalOrdinaryNameReferenceKeepsItsOwnSemanticExpression() {
        packetFixture(listOf(OUTCOME))
        ReadAction.run<RuntimeException> {
            val function = function("requireNotNull")
            val expected =
                PsiTreeUtil.collectElementsOfType(function, KtNameReferenceExpression::class.java).single {
                    range(it) == exact(IT_START, IT_END)
                }
            val literal = checkNotNull(PsiTreeUtil.getParentOfType(expected, KtFunctionLiteral::class.java))
            IntellijReadObservation.None.observedAnalyze(expected) {
                val parameter = literal.symbol.valueParameters.single()
                assertTrue(parameter.isImplicitLambdaParameter)
                assertEquals(parameter, expected.references.filterIsInstance<KtReference>().single().resolveToSymbol())
            }
            assertRestoration(function, expected)
        }
    }

    private fun assertRestoration(function: KtNamedFunction, expected: org.jetbrains.kotlin.psi.KtExpression) {
        if (expected is KtThisExpression) assertEquals(range(expected), range(expected.instanceReference))
        val restored = function.exactValueElement(range(expected))
        println(
            "THIS_RESTORATION checked=${range(expected)} actual_class=${className(restored)} " +
                "actual_parent=${className(restored?.parent)} actual_range=${restored?.let(::range)} " +
                "expected_class=${className(expected)} guard_and_let_transport=NOT_ASSERTED"
        )
        assertSame(
            "The exact value occurrence must restore its independently selected semantic PSI",
            expected,
            restored,
        )
    }

    private fun thisExpression(function: KtNamedFunction, anchor: ExactDeclarationTextRange): KtThisExpression =
        PsiTreeUtil.collectElementsOfType(function, KtThisExpression::class.java).single { range(it) == anchor }

    private fun function(name: String): KtNamedFunction {
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath("src/fixture/outcome/Outcome.kt"))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        return PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { it.name == name }
    }

    private fun className(element: PsiElement?): String = element?.javaClass?.simpleName?.take(MAX_CLASS_NAME) ?: "NONE"

    private fun expectedLet() =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "kotlin.let",
                rawReceiverType = "T",
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf("(T)->R"),
                rawTypeParameterCount = LET_TYPE_PARAMETERS,
            )
            .nativeRefined()

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val REQUIRE_THIS_START = 1622
        const val REQUIRE_THIS_END = 1626
        const val BIND_THIS_START = 1189
        const val BIND_THIS_END = 1193
        const val IT_START = 1650
        const val IT_END = 1652
        const val MAX_CLASS_NAME = 80
        const val LET_TYPE_PARAMETERS = 2
    }
}
