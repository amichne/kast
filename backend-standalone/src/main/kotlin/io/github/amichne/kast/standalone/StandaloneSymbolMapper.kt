package io.github.amichne.kast.standalone

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import io.github.amichne.kast.api.Location
import io.github.amichne.kast.api.Symbol
import io.github.amichne.kast.api.SymbolKind
import io.github.amichne.kast.api.TextEdit
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

internal fun PsiElement.toSymbol(): Symbol {
    val fqName = fqNameString()
    return Symbol(
        fqName = fqName,
        kind = symbolKind(),
        location = toKastLocation(nameRange()),
        type = typeDescription(),
        containingDeclaration = fqName.substringBeforeLast('.', "").ifBlank { null },
    )
}

internal fun PsiElement.nameRange(): TextRange = when (this) {
    is KtNamedDeclaration -> nameIdentifier?.textRange ?: textRange
    else -> textRange
}

internal fun PsiElement.toKastLocation(range: TextRange = nameRange()): Location {
    val content = containingFile.text
    val startOffset = range.startOffset
    val lineStart = content.lastIndexOf('\n', startOffset - 1).let { index ->
        if (index == -1) {
            0
        } else {
            index + 1
        }
    }
    val lineEnd = content.indexOf('\n', startOffset).let { index ->
        if (index == -1) {
            content.length
        } else {
            index
        }
    }

    return Location(
        filePath = containingFile.virtualFilePath,
        startOffset = startOffset,
        endOffset = range.endOffset,
        startLine = content.take(startOffset).count { it == '\n' } + 1,
        startColumn = startOffset - lineStart + 1,
        preview = content.substring(lineStart, lineEnd).trimEnd(),
    )
}

internal fun PsiReference.toKastLocation(): Location {
    val elementStartOffset = element.textRange.startOffset
    val absoluteRange = TextRange(
        elementStartOffset + rangeInElement.startOffset,
        elementStartOffset + rangeInElement.endOffset,
    )
    return element.toKastLocation(absoluteRange)
}

internal fun PsiReference.toTextEdit(newName: String): TextEdit {
    val elementStartOffset = element.textRange.startOffset
    return TextEdit(
        filePath = element.containingFile.virtualFilePath,
        startOffset = elementStartOffset + rangeInElement.startOffset,
        endOffset = elementStartOffset + rangeInElement.endOffset,
        newText = newName,
    )
}

internal fun PsiElement.declarationEdit(newName: String): TextEdit {
    val range = when (this) {
        is KtNamedDeclaration -> nameIdentifier?.textRange ?: textRange
        is PsiNameIdentifierOwner -> nameIdentifier?.textRange ?: textRange
        else -> textRange
    }

    return TextEdit(
        filePath = containingFile.virtualFilePath,
        startOffset = range.startOffset,
        endOffset = range.endOffset,
        newText = newName,
    )
}

private fun PsiElement.fqNameString(): String = when (this) {
    is KtNamedDeclaration -> fqName?.asString() ?: name ?: "<anonymous>"
    else -> text
}

private fun PsiElement.symbolKind(): SymbolKind = when (this) {
    is KtClass -> if (isInterface()) SymbolKind.INTERFACE else SymbolKind.CLASS
    is KtObjectDeclaration -> SymbolKind.OBJECT
    is KtNamedFunction -> SymbolKind.FUNCTION
    is KtProperty -> SymbolKind.PROPERTY
    is KtParameter -> SymbolKind.PARAMETER
    else -> SymbolKind.UNKNOWN
}

private fun PsiElement.typeDescription(): String? = when (this) {
    is KtNamedFunction -> typeReference?.text
    is KtProperty -> typeReference?.text
    is KtParameter -> typeReference?.text
    else -> null
}

private val PsiElement.virtualFilePath: String
    get() = containingFile.virtualFile?.path ?: containingFile.viewProvider.virtualFile.path
