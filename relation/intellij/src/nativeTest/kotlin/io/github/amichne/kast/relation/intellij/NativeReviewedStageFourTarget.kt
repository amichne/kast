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
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

internal fun assertReviewedStageFourTargets(file: KtFile) {
    val hosts = file.declarations.filterIsInstance<KtNamedFunction>().associateBy { it.name }
    for (target in stageFourCollectionTargets() + stageFourScopeTargets()) {
        val host = hosts.getValue(target.host)
        val call =
            PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java).single {
                it.calleeExpression?.text == target.callee
            }
        IntellijReadObservation.None.observedAnalyze(call) {
            assertStageFourCall(checkNotNull(call.resolveCall()).signature.symbol, target)
        }
    }
    for ((hostName, property) in listOf("captureEntryKey" to "key", "captureEntryValue" to "value")) {
        val reference =
            PsiTreeUtil.collectElementsOfType(hosts.getValue(hostName), KtNameReferenceExpression::class.java).single {
                it.getReferencedName() == property
            }
        IntellijReadObservation.None.observedAnalyze(reference) {
            val symbol =
                reference.references.filterIsInstance<KtReference>().single().resolveToSymbol()
                    as KaKotlinPropertySymbol
            assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
            val projected = symbol.compilerProjection(this) as IntellijCompilerProjectionResult.Projected
            val signature = projected.projection.signature as CanonicalCompilerSignature.Property
            assertEquals("kotlin.collections.Map.Entry.$property", signature.qualifiedIdentity.value)
            val owner = checkNotNull(symbol.psi?.containingFile?.virtualFile)
            println("STAGE_FOUR_PROPERTY canonical=${signature.canonicalEncoding().value}")
            println("STAGE_FOUR_PROPERTY origin=${symbol.origin} file=${owner.url}")
        }
    }
}

private fun KaSession.assertStageFourCall(symbol: KaFunctionSymbol, target: StageFourCallTarget) {
    assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
    val projected = symbol.compilerProjection(this) as IntellijCompilerProjectionResult.Projected
    val signature = projected.projection.signature as CanonicalCompilerSignature.Function
    assertEquals(target.identity, signature.qualifiedIdentity.value)
    assertEquals(target.parameters, symbol.valueParameters.size)
    assertEquals(target.receiver, symbol.receiverParameter != null)
    val binary = checkNotNull(symbol.psi?.containingFile?.virtualFile)
    assertTrue("The target must use the case-owned compiler artifact", binary.url.contains("kotlin-stdlib-2.3.0.jar!/"))
    println("STAGE_FOUR_TARGET host=${target.host} canonical=${signature.canonicalEncoding().value}")
    println("STAGE_FOUR_TARGET origin=${symbol.origin} file=${binary.url}")
}

private fun stageFourCollectionTargets(): List<StageFourCallTarget> =
    listOf(
        StageFourCallTarget(
            host = "captureMapValues",
            callee = "mapValues",
            identity = "kotlin.collections.mapValues",
            parameters = 1,
        ),
        StageFourCallTarget(
            host = "captureEntryComponent1",
            callee = "component1",
            identity = "kotlin.collections.component1",
            parameters = 0,
        ),
        StageFourCallTarget(
            host = "captureEntryComponent2",
            callee = "component2",
            identity = "kotlin.collections.component2",
            parameters = 0,
        ),
        StageFourCallTarget(
            host = "captureFilter",
            callee = "filter",
            identity = "kotlin.collections.filter",
            parameters = 1,
        ),
        StageFourCallTarget(
            host = "captureMapNotNull",
            callee = "mapNotNull",
            identity = "kotlin.collections.mapNotNull",
            parameters = 1,
        ),
        StageFourCallTarget(
            host = "captureGroupBy",
            callee = "groupBy",
            identity = "kotlin.collections.groupBy",
            parameters = 1,
        ),
        StageFourCallTarget(
            host = "captureGroupByValues",
            callee = "groupBy",
            identity = "kotlin.collections.groupBy",
            parameters = 2,
        ),
    )

private fun stageFourScopeTargets(): List<StageFourCallTarget> =
    listOf(
        StageFourCallTarget(
            host = "captureTopRun",
            callee = "run",
            identity = "kotlin.run",
            parameters = 1,
            receiver = false,
        ),
        StageFourCallTarget(host = "captureReceiverRun", callee = "run", identity = "kotlin.run", parameters = 1),
        StageFourCallTarget(
            host = "captureWith",
            callee = "with",
            identity = "kotlin.with",
            parameters = 2,
            receiver = false,
        ),
        StageFourCallTarget(host = "captureApply", callee = "apply", identity = "kotlin.apply", parameters = 1),
    )

private data class StageFourCallTarget(
    val host: String,
    val callee: String,
    val identity: String,
    val parameters: Int,
    val receiver: Boolean = true,
)
