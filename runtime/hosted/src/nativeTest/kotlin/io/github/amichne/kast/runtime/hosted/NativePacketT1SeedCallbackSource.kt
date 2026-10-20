@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.resolution.KaSimpleFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolModality
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** Syntax and K2 facts stay independent of the value-return implementation. */
internal class NativePacketT1SeedCallbackSource(private val source: NativePacketCompositeQuerySource) {
    fun prove(request: ValueFlowRequest): Unit =
        ReadAction.compute<Unit, RuntimeException> {
            val body = source.sequentialBody()
            val caller = checkNotNull(PsiTreeUtil.getParentOfType(body, KtNamedFunction::class.java))
            val call = checkNotNull(PsiTreeUtil.getParentOfType(body, KtCallExpression::class.java))
            assertEquals(BODY_START, body.textRange.startOffset)
            assertEquals(BODY_END, body.textRange.endOffset)
            val invocation = call.parent as KtQualifiedExpression
            assertTrue(invocation.selectorExpression === call)
            assertEquals(SELECTOR_START, call.textRange.startOffset)
            assertEquals(BODY_END, call.textRange.endOffset)
            assertEquals(SUPPLIER_START, invocation.textRange.startOffset)
            assertEquals(BODY_END, invocation.textRange.endOffset)
            assertEquals("getOffer", caller.name)
            assertEquals(request.source.enclosing.range.startInclusive, caller.textRange.startOffset)
            assertEquals(request.source.enclosing.range.endExclusive, caller.textRange.endOffset)
            val result = checkNotNull(body.bodyExpression).statements.last() as KtCallExpression
            assertEquals(request.source.range.startInclusive, result.textRange.startOffset)
            assertEquals(request.source.range.endExclusive, result.textRange.endOffset)
            IntellijReadObservation.None.observedAnalyze(call) { proveSupplier(call, body, result) }
        }

    fun proveBoundSupplier(binding: CallbackArgumentBinding): Unit =
        ReadAction.compute<Unit, RuntimeException> {
            val body = source.sequentialBody()
            val call = checkNotNull(PsiTreeUtil.getParentOfType(body, KtCallExpression::class.java))
            IntellijReadObservation.None.observedAnalyze(call) {
                val symbol = checkNotNull(call.resolveCall()).signature.symbol as KaNamedFunctionSymbol
                val declaration = symbol.psi as KtNamedFunction
                val callable = binding.invocation.callable
                val formal = checkNotNull(symbol.valueParameters.single().psi)
                assertEquals(0, binding.position.value)
                assertEquals(binding.parameter.range.startInclusive, formal.textRange.startOffset)
                assertEquals(binding.parameter.range.endExclusive, formal.textRange.endOffset)
                assertEquals(callable.range.startInclusive, declaration.textRange.startOffset)
                assertEquals(callable.range.endExclusive, declaration.textRange.endOffset)
                assertEquals(
                    (callable.qualifiedIdentity as ExactDeclarationQualifiedIdentity.Available).value,
                    checkNotNull(symbol.callableId).asSingleFqName().asString(),
                )
            }
        }

    private fun org.jetbrains.kotlin.analysis.api.KaSession.proveSupplier(
        call: KtCallExpression,
        body: KtFunctionLiteral,
        result: KtCallExpression,
    ) {
        val actual = checkNotNull(call.resolveCall())
        val symbol = actual.signature.symbol as KaNamedFunctionSymbol
        assertEquals(KaSymbolOrigin.SOURCE, symbol.origin)
        assertEquals(KaSymbolModality.FINAL, symbol.modality)
        assertEquals("sequential", symbol.name.asString())
        val lambda = checkNotNull(call.lambdaArguments.single().getLambdaExpression())
        val formal = symbol.valueParameters.single()
        assertEquals("block", formal.name.asString())
        assertEquals(formal, actual.valueArgumentMapping[lambda]?.symbol)
        assertTrue(result.isUsedAsResultOfLambda)
        assertTrue(body.symbol.valueParameters.isEmpty())
        assertTrue(body.symbol.receiverParameter != null)
        proveBuiltin(symbol.psi as KtNamedFunction)
    }

    private fun org.jetbrains.kotlin.analysis.api.KaSession.proveBuiltin(supplier: KtNamedFunction) {
        val invoked =
            PsiTreeUtil.collectElementsOfType(supplier, KtCallExpression::class.java).single {
                it.calleeExpression?.text == "block"
            }
        val actual = checkNotNull(invoked.resolveCall())
        assertTrue(actual is KaSimpleFunctionCall && actual.isImplicitInvoke)
        val symbol = actual.signature.symbol as KaNamedFunctionSymbol
        assertEquals("kotlin.Function1.invoke", checkNotNull(symbol.callableId).asSingleFqName().asString())
        assertEquals(1, symbol.valueParameters.size)
        val full = invoked.parent
        assertEquals(INVOKED_START, full.textRange.startOffset)
        assertEquals(INVOKED_END, full.textRange.endOffset)
    }

    private companion object {
        const val BODY_START = 677
        const val BODY_END = 1063
        const val SUPPLIER_START = 658
        const val SELECTOR_START = 666
        const val INVOKED_START = 872
        const val INVOKED_END = 897
    }
}
