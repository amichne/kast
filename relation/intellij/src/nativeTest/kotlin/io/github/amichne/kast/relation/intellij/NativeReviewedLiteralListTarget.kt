@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

internal fun assertReviewedLiteralListTarget(file: KtFile) {
    val host = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "distinctKeyAssociation" }
    val call =
        PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java).single {
            it.calleeExpression?.text == "listOf"
        }
    IntellijReadObservation.None.observedAnalyze(call) {
        val selected = checkNotNull(call.resolveCall())
        val symbol = selected.signature.symbol as KaNamedFunctionSymbol
        assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
        assertTrue("The selected list overload must accept vararg elements", symbol.valueParameters.single().isVararg)
        val projected = symbol.compilerProjection(this) as IntellijCompilerProjectionResult.Projected
        val signature = projected.projection.signature as CanonicalCompilerSignature.Function
        assertEquals("kotlin.collections.listOf", signature.qualifiedIdentity.value)
        val values =
            call.valueArguments.map {
                (checkNotNull(it.getArgumentExpression()).evaluate() as KaConstantValue.StringValue).value
            }
        assertEquals(listOf("left", "right"), values)
        val binary = checkNotNull(symbol.psi?.containingFile?.virtualFile)
        assertTrue(binary.url.contains("kotlin-stdlib-2.3.0.jar!/"))
        println("LITERAL_LIST_TARGET canonical=${signature.canonicalEncoding().value}")
        println("LITERAL_LIST_TARGET origin=${symbol.origin} file=${binary.url}")
        println("LITERAL_LIST_TARGET parameterTypes=${symbol.valueParameters.map { it.returnType.toString() }}")
        println("LITERAL_LIST_TARGET constants=$values")
    }
}
