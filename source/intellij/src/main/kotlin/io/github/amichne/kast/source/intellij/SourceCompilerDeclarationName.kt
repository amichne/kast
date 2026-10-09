package io.github.amichne.kast.source.intellij

internal fun com.intellij.psi.PsiNamedElement.compilerDeclarationName(): String =
    name
        ?: if (this is org.jetbrains.kotlin.psi.KtObjectDeclaration && isObjectLiteral())
            io.github.amichne.kast.symbol.contract.ANONYMOUS_OBJECT_DECLARATION_NAME
        else ""

internal fun org.jetbrains.kotlin.psi.KtNamedDeclaration.matchesExactSource(
    selector: io.github.amichne.kast.symbol.contract.SymbolSelector
): Boolean =
    compilerDeclarationName() == selector.name.value &&
        textRange.startOffset == selector.range.startInclusive &&
        textRange.endOffset == selector.range.endExclusive

internal fun org.jetbrains.kotlin.psi.KtNamedDeclaration.matchesSourceCandidateLocation(
    expectedName: String,
    offset: Int,
): Boolean = compilerDeclarationName() == expectedName && textRange.startOffset == offset
