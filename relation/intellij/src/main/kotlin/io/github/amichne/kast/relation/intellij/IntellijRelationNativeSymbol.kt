@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.javaInterop.callableSymbol
import org.jetbrains.kotlin.analysis.api.javaInterop.namedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter

/** Nullable Java interop results are consumed inside the K2 session and never become evidence. */
internal fun KaSession.nativeSymbol(declaration: PsiNamedElement): KaSymbol? =
    when (declaration) {
        is KtParameter ->
            declaration.symbol.let { symbol ->
                (symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty ?: symbol
            }
        is KtNamedDeclaration -> declaration.symbol
        is PsiClass -> declaration.namedClassSymbol
        is PsiMember -> declaration.callableSymbol
        else -> null
    }
