package io.github.amichne.kast.standalone

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.NotFoundException
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.TextEdit
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.lexer.KtTokens

/**
 * Walks the PSI element hierarchy up from [offset] until it finds a resolvable reference
 * or a named element, then returns it.
 */
internal fun resolveTarget(file: com.intellij.psi.PsiFile, offset: Int): PsiElement {
    val leaf = file.findElementAt(offset)
        ?: throw NotFoundException(
            message = "No PSI element was found at the requested offset",
            details = mapOf("offset" to offset.toString()),
        )

    generateSequence(leaf as PsiElement?) { it.parent }.forEach { element ->
        element.references.firstNotNullOfOrNull { it.resolve() }?.let { return it }

        if (element is PsiNamedElement && !element.name.isNullOrBlank()) {
            return element
        }
    }

    throw NotFoundException("No resolvable symbol was found at the requested offset")
}

internal fun PsiElement.toSymbolModel(containingDeclaration: String?): Symbol = Symbol(
    fqName = fqName(),
    kind = kind(),
    location = toKastLocation(nameRange()),
    type = typeDescription(),
    containingDeclaration = containingDeclaration,
)

private fun PsiElement.nameRange(): TextRange = when (this) {
    is KtNamedDeclaration -> nameIdentifier?.textRange ?: textRange
    is PsiNameIdentifierOwner -> nameIdentifier?.textRange ?: textRange
    else -> textRange
}

internal fun PsiElement.declarationEdit(newName: String): TextEdit {
    val range = nameRange()
    return TextEdit(
        filePath = containingFile.virtualFile.path,
        startOffset = range.startOffset,
        endOffset = range.endOffset,
        newText = newName,
    )
}

private fun PsiElement.fqName(): String = when (this) {
    is KtNamedDeclaration -> fqName?.asString() ?: name ?: "<anonymous>"
    is PsiClass -> qualifiedName ?: name ?: "<anonymous>"
    is PsiMethod -> "${containingClass?.qualifiedName ?: "<local>"}#$name"
    is PsiField -> "${containingClass?.qualifiedName ?: "<local>"}.$name"
    is PsiNamedElement -> name ?: "<anonymous>"
    else -> text
}

private fun PsiElement.kind(): SymbolKind = when (this) {
    is KtClass -> if (isInterface()) SymbolKind.INTERFACE else SymbolKind.CLASS
    is KtObjectDeclaration -> SymbolKind.OBJECT
    is KtNamedFunction -> SymbolKind.FUNCTION
    is KtProperty -> SymbolKind.PROPERTY
    is KtParameter -> SymbolKind.PARAMETER
    is PsiClass -> if (isInterface) SymbolKind.INTERFACE else SymbolKind.CLASS
    is PsiMethod -> SymbolKind.FUNCTION
    is PsiField -> SymbolKind.PROPERTY
    else -> SymbolKind.UNKNOWN
}

private fun PsiElement.typeDescription(): String? = when (this) {
    is KtNamedFunction -> typeReference?.text
    is KtProperty -> typeReference?.text
    is KtParameter -> typeReference?.text
    is PsiMethod -> returnType?.presentableText
    is PsiField -> type.presentableText
    else -> null
}

/**
 * Converts a PSI element and text range to a [Location] using raw file text.
 */
internal fun PsiElement.toKastLocation(range: TextRange = nameRange()): Location {
    val file = containingFile
    val content = file.viewProvider.contents
    val startOffset = range.startOffset.coerceIn(0, content.length)
    val endOffset = range.endOffset.coerceIn(startOffset, content.length)
    val lineStart = content.lastIndexOf('\n', startOffset - 1).let { if (it == -1) 0 else it + 1 }
    val lineEnd = content.indexOf('\n', startOffset).let { if (it == -1) content.length else it }

    return Location(
        filePath = file.virtualFile?.path
            ?: file.viewProvider.virtualFile.path,
        startOffset = startOffset,
        endOffset = endOffset,
        startLine = content.subSequence(0, startOffset).count { it == '\n' } + 1,
        startColumn = startOffset - lineStart + 1,
        preview = content.substring(lineStart, lineEnd).trimEnd(),
    )
}

internal fun PsiElement.parentsWithSelf(): Sequence<PsiElement> = generateSequence(this) { it.parent }

internal fun PsiElement.callHierarchyDeclaration(): PsiElement? = parentsWithSelf().firstOrNull { element ->
    when (element) {
        is KtNamedFunction,
        is KtProperty,
        is KtClass,
        is KtObjectDeclaration,
        is PsiMethod,
        is PsiField,
        is PsiClass,
        -> true

        else -> false
    }
}

internal fun PsiElement.referenceSearchIdentifier(): String? = when (this) {
    is KtNamedFunction -> name.takeUnless { hasModifier(KtTokens.OPERATOR_KEYWORD) }
    is KtNamedDeclaration -> name
    else -> (this as? PsiNamedElement)?.name
}
    ?.takeIf { identifier -> identifier.isNotBlank() }
