@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtThisExpression

/** Direct compiler identities distinguish a source receiver alias from a reviewed library callback dependency. */
internal class NativePacketNullableAliasSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    fun capture() =
        ReadAction.run<RuntimeException> {
            val required = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "", "requireNotNull"))
            val function = functions(OUTCOME).single { it.name == "requireNotNull" }
            val selected = calls(psi(FEATURE)).single { it.calleeExpression?.text == "requireNotNull" }
            val qualified = selected.parent as KtDotQualifiedExpression
            val actual = qualified.receiverExpression as KtDotQualifiedExpression
            val sequential = checkNotNull(PsiTreeUtil.getParentOfType(selected, KtFunctionLiteral::class.java))
            assertEquals(exact(REQUIRE_CALL_START, REQUIRE_CALL_END), range(qualified))
            assertEquals(exact(FIRST_START, FIRST_END), range(actual))
            assertEquals(exact(SEQUENTIAL_START, SEQUENTIAL_END), range(sequential))
            val safe = PsiTreeUtil.collectElementsOfType(function, KtSafeQualifiedExpression::class.java).single()
            val thisValue = safe.receiverExpression as KtThisExpression
            val let = safe.selectorExpression as KtCallExpression
            val literal = checkNotNull(let.lambdaArguments.single().getLambdaExpression()).functionLiteral
            val value =
                PsiTreeUtil.collectElementsOfType(literal, KtNameReferenceExpression::class.java).single { reference ->
                    reference.getReferencedName() == "it"
                }
            assertEquals(exact(THIS_START, THIS_END), range(thisValue))
            assertEquals(exact(CALLBACK_START, CALLBACK_END), range(literal))
            assertEquals(exact(IT_START, IT_END), range(value))
            IntellijReadObservation.None.observedAnalyze(selected) {
                caller(call = selected, actual = actual, expected = required)
                body(function = function, thisValue = thisValue, call = let, literal = literal, value = value)
            }
            println("NULLABLE_ALIAS_CAPTURE status=COMPILER_IDENTITIES source_alias_transfer,let_model,NonNull=NOT_RUN")
        }

    private fun KaSession.caller(
        call: KtCallExpression,
        actual: KtDotQualifiedExpression,
        expected: SymbolSelector,
    ) {
        val resolved = checkNotNull(call.resolveCall())
        assertEquals(KaSymbolOrigin.SOURCE, resolved.signature.symbol.origin)
        val projected = resolved.signature.symbol.compilerProjection(this)
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
        assertEquals(expected.compilerIdentity, declaration.identity)
        assertEquals(expected.signature, declaration.signature)
        assertNull(resolved.dispatchReceiver)
        val receiver = resolved.extensionReceiver as KaExplicitReceiverValue
        assertEquals(actual.originalElement, receiver.expression.originalElement)
        assertEquals(exact(FIRST_START, FIRST_END), range(receiver.expression))
        val supplied = checkNotNull(call.lambdaArguments.single().getLambdaExpression())
        assertEquals(exact(ERROR_CALLBACK_START, ERROR_CALLBACK_END), range(supplied))
        assertEquals(
            resolved.signature.symbol.valueParameters.single(),
            resolved.valueArgumentMapping[supplied]?.symbol,
        )
        println("NULLABLE_CALLER callable=SOURCE.requireNotNull call=939:1020 extension=939:967 dispatch=NONE")
    }

    private fun KaSession.body(
        function: KtNamedFunction,
        thisValue: KtThisExpression,
        call: KtCallExpression,
        literal: KtFunctionLiteral,
        value: KtNameReferenceExpression,
    ) {
        val receiver = checkNotNull(function.symbol.receiverParameter)
        val reference = thisValue.instanceReference.references.filterIsInstance<KtReference>().single()
        assertEquals(receiver, reference.resolveToSymbol())
        val resolved = checkNotNull(call.resolveCall())
        assertNull(resolved.dispatchReceiver)
        val actual = resolved.extensionReceiver as KaExplicitReceiverValue
        assertEquals(thisValue.originalElement, actual.expression.originalElement)
        val parameter = literal.symbol.valueParameters.single()
        assertTrue(parameter.isImplicitLambdaParameter)
        assertEquals(parameter, value.references.filterIsInstance<KtReference>().single().resolveToSymbol())
        val supplied = checkNotNull(call.lambdaArguments.single().getLambdaExpression())
        assertEquals(
            resolved.signature.symbol.valueParameters.single(),
            resolved.valueArgumentMapping[supplied]?.symbol,
        )
        assertLet(resolved.signature.symbol.compilerProjection(this))
        assertEquals(KaSymbolOrigin.LIBRARY, resolved.signature.symbol.origin)
        successInput(literal, value)
        println(
            "NULLABLE_BODY this=1622:1626 receiverSymbol=EXACT_REQUIRE_EXTENSION let=1622:1655 " +
                "callback=1632:1655 it=1650:1652 implicitFormal=EXACT " +
                "receiverTypeDiagnostic=${receiver.returnType.toString().take(MAX_TYPE)} " +
                "formalTypeDiagnostic=${parameter.returnType.toString().take(MAX_TYPE)}"
        )
    }

    private fun KaSession.successInput(literal: KtFunctionLiteral, value: KtNameReferenceExpression) {
        val call = calls(literal).single()
        val resolved = checkNotNull(call.resolveCall())
        val expected = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "Companion", "success"))
        val projected = resolved.signature.symbol.compilerProjection(this)
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        assertEquals(
            expected.compilerIdentity,
            (projected as IntellijCompilerProjectionResult.Projected).projection.identity,
        )
        assertEquals(resolved.signature.symbol.valueParameters.single(), resolved.valueArgumentMapping[value]?.symbol)
        println("NULLABLE_CALLBACK_VALUE source=EXACT_IMPLICIT_IT target=SOURCE.Outcome.success.value index=0")
    }

    private fun assertLet(projected: IntellijCompilerProjectionResult) {
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        val signature =
            (projected as IntellijCompilerProjectionResult.Projected).projection.signature
                as CanonicalCompilerSignature.Function
        assertEquals("kotlin.let", signature.qualifiedIdentity.value)
        assertEquals(1, signature.valueParameters.size)
        assertEquals(LET_TYPE_PARAMETERS, signature.typeParameterCount.value)
    }

    private fun calls(element: com.intellij.psi.PsiElement) =
        PsiTreeUtil.collectElementsOfType(element, KtCallExpression::class.java)

    private fun functions(path: String) = PsiTreeUtil.collectElementsOfType(psi(path), KtNamedFunction::class.java)

    private fun psi(path: String): KtFile {
        val file = checkNotNull(base.findFileByRelativePath("src/" + path.removePrefix("src/main/kotlin/")))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val OUTCOME = NativePacketCompositeSuccessSource.OUTCOME
        const val REQUIRE_CALL_START = 939
        const val REQUIRE_CALL_END = 1020
        const val FIRST_START = 939
        const val FIRST_END = 967
        const val ERROR_CALLBACK_START = 983
        const val ERROR_CALLBACK_END = 1020
        const val SEQUENTIAL_START = 677
        const val SEQUENTIAL_END = 1063
        const val THIS_START = 1622
        const val THIS_END = 1626
        const val CALLBACK_START = 1632
        const val CALLBACK_END = 1655
        const val IT_START = 1650
        const val IT_END = 1652
        const val LET_TYPE_PARAMETERS = 2
        const val MAX_TYPE = 160
    }
}
