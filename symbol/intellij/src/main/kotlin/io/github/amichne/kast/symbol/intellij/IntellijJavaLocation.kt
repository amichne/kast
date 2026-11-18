package io.github.amichne.kast.symbol.intellij

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImplicitClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiTypeParameter
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind

internal sealed interface IntellijJavaLocation {
    data class Found(val declaration: PsiNamedElement, val kind: CompilerSymbolKind) : IntellijJavaLocation

    data object OutsideDeclaration : IntellijJavaLocation

    data object Unsupported : IntellijJavaLocation
}

/** The closest Java member controls the result, including when Kast cannot select that member. */
internal fun PsiJavaFile.javaDeclarationAt(offset: Int): IntellijJavaLocation {
    if (!isJavaSource()) return IntellijJavaLocation.Unsupported
    val member =
        generateSequence(findElementAt(offset)) { it.parent }
            .takeWhile { it !is PsiFile }
            .onEach { ProgressManager.checkCanceled() }
            .filterIsInstance<PsiMember>()
            .firstOrNull() ?: return IntellijJavaLocation.OutsideDeclaration
    val declaration = member as? PsiNamedElement ?: return IntellijJavaLocation.Unsupported
    val kind = declaration.javaLocationKind() ?: return IntellijJavaLocation.Unsupported
    return IntellijJavaLocation.Found(declaration, kind)
}

/** Source shape admits a candidate. Exact selection still needs the Kotlin Analysis API identity. */
internal fun PsiNamedElement.javaLocationKind(): CompilerSymbolKind? =
    when (this) {
        is PsiClass -> if (isNamedJavaSourceClass()) CompilerSymbolKind.CLASSLIKE else null
        is PsiMethod ->
            if (!isConstructor && containingClass?.isNamedJavaSourceClass() == true) CompilerSymbolKind.FUNCTION
            else null
        else -> null
    }

internal fun PsiJavaFile.isJavaSource(): Boolean = virtualFile?.fileType == JavaFileType.INSTANCE

private fun PsiClass.isNamedJavaSourceClass(): Boolean =
    generateSequence(this) { it.parent as? PsiClass }
        .all { owner ->
            val parent = owner.parent
            owner !is PsiTypeParameter &&
                owner !is PsiImplicitClass &&
                owner.name != null &&
                !owner.isEnum &&
                !owner.isRecord &&
                !owner.isAnnotationType &&
                (parent is PsiClass || parent is PsiJavaFile)
        }
