@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import junit.framework.TestCase.assertEquals
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.contracts.description.KaContractCallsInPlaceContractEffectDeclaration
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.psi.KtCallExpression

/** The stable R0 repeat input declares UNKNOWN independently of Kast's adapter result. */
internal fun assertRepeatCompilerEffect(file: PsiFile) {
    val call = PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java).first()
    analyze(call) {
        val symbol = checkNotNull(call.resolveCall()).signature.symbol as KaNamedFunctionSymbol
        assertEquals("kotlin.repeat", symbol.callableId?.asSingleFqName()?.asString())
        val effects = symbol.contractEffects.filterIsInstance<KaContractCallsInPlaceContractEffectDeclaration>()
        assertEquals(listOf("UNKNOWN"), effects.map { it.invocationKind.name })
    }
}
