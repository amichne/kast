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
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaSingleCall
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

internal fun assertReviewedTupleTargets(file: KtFile) {
    val hosts = file.declarations.filterIsInstance<KtNamedFunction>()
    val infixHost = hosts.single { it.name == "pairKeyCannotBecomeValue" }
    val infix =
        PsiTreeUtil.collectElementsOfType(infixHost, KtBinaryExpression::class.java).single {
            it.operationReference.text == "to"
        }
    IntellijReadObservation.None.observedAnalyze(infix) {
        val selected = checkNotNull(infix.resolveCall() as? KaSingleCall<*, *>)
        assertTupleTarget(selected.signature.symbol as KaFunctionSymbol, "kotlin.to")
    }
    val constructorHost = hosts.single { it.name == "constructorKeyCannotBecomeValue" }
    val constructor =
        PsiTreeUtil.collectElementsOfType(constructorHost, KtCallExpression::class.java).single {
            it.calleeExpression?.text == "Pair"
        }
    IntellijReadObservation.None.observedAnalyze(constructor) {
        val selected = checkNotNull(constructor.resolveCall())
        assertTupleTarget(selected.signature.symbol, "kotlin.Pair.<init>")
    }
    for (name in listOf("first", "second")) {
        val reference =
            PsiTreeUtil.collectElementsOfType(file, KtNameReferenceExpression::class.java).first {
                it.getReferencedName() == name
            }
        IntellijReadObservation.None.observedAnalyze(reference) {
            val symbol =
                reference.references.filterIsInstance<KtReference>().single().resolveToSymbol()
                    as KaKotlinPropertySymbol
            assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
            val projected = symbol.compilerProjection(this) as IntellijCompilerProjectionResult.Projected
            val signature = projected.projection.signature as CanonicalCompilerSignature.Property
            assertEquals("kotlin.Pair.$name", signature.qualifiedIdentity.value)
            println("TUPLE_PROPERTY canonical=${signature.canonicalEncoding().value}")
            println("TUPLE_PROPERTY file=${symbol.psi?.containingFile?.virtualFile?.url}")
        }
    }
}

private fun KaSession.assertTupleTarget(symbol: KaFunctionSymbol, identity: String) {
    assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
    val projection = symbol.compilerProjection(this)
    assertTrue(
        "Compiler projection prerequisite: $projection",
        projection is IntellijCompilerProjectionResult.Projected,
    )
    val projected = (projection as IntellijCompilerProjectionResult.Projected).projection
    val signature = projected.signature as CanonicalCompilerSignature.Function
    assertEquals(identity, signature.qualifiedIdentity.value)
    val binary = checkNotNull(symbol.psi?.containingFile?.virtualFile)
    assertTrue(
        "The selected library must be the case-owned consumer artifact",
        binary.url.contains("kotlin-stdlib-2.3.0.jar!/"),
    )
    println("TUPLE_TARGET identity=$identity canonical=${signature.canonicalEncoding().value}")
    println("TUPLE_TARGET origin=${symbol.origin} file=${binary.url}")
    println("TUPLE_TARGET receiver=${symbol.receiverParameter?.returnType}")
    println("TUPLE_TARGET parameters=${symbol.valueParameters.map { it.returnType.toString() }}")
}
