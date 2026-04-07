package io.github.amichne.kast.standalone

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.api.TextEdit
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPackageDirective

internal data class ImportAnalysisResult(
    val usedImports: List<KtImportDirective>,
    val unusedImports: List<KtImportDirective>,
    val missingImports: List<String>,
)

@OptIn(KaIdeApi::class)
internal object ImportAnalysis {
    fun analyzeImports(file: KtFile): ImportAnalysisResult {
        val importDirectives = file.importDirectives
        if (importDirectives.isEmpty()) {
            return ImportAnalysisResult(
                usedImports = emptyList(),
                unusedImports = emptyList(),
                missingImports = emptyList(),
            )
        }

        val resolvedImportableFqNames = resolvedImportableFqNames(file)
        val usedImports = importDirectives.filter { directive ->
            directive.isUsedImport(resolvedImportableFqNames)
        }
        return ImportAnalysisResult(
            usedImports = usedImports,
            unusedImports = importDirectives - usedImports.toSet(),
            missingImports = emptyList(),
        )
    }

    fun optimizeImportEdits(file: KtFile): List<TextEdit> = analyzeImports(file)
        .unusedImports
        .map(::removeImportEdit)
        .sortedWith(compareBy({ it.filePath }, { it.startOffset }))

    fun removeImportEdit(importDirective: KtImportDirective): TextEdit {
        val file = importDirective.containingKtFile
        val endOffset = offsetAfterTrailingLineBreak(file.text, importDirective.textRange.endOffset)
        return TextEdit(
            filePath = file.virtualFile.path,
            startOffset = importDirective.textRange.startOffset,
            endOffset = endOffset,
            newText = "",
        )
    }

    fun insertImportEdit(file: KtFile, fqName: String): TextEdit? {
        if (fqName.isBlank()) {
            return null
        }
        if (file.importDirectives.any { directive -> directive.coversImport(fqName) }) {
            return null
        }

        val insertionOffset = when {
            file.importDirectives.isNotEmpty() -> offsetAfterTrailingLineBreak(
                file.text,
                file.importDirectives.last().textRange.endOffset,
            )

            file.packageDirective != null -> offsetAfterTrailingLineBreak(
                file.text,
                checkNotNull(file.packageDirective).textRange.endOffset,
            )

            else -> 0
        }

        val importText = buildString {
            append("import ")
            append(fqName)
            append('\n')
            if (insertionOffset == 0) {
                append('\n')
            }
        }

        return TextEdit(
            filePath = file.virtualFile.path,
            startOffset = insertionOffset,
            endOffset = insertionOffset,
            newText = importText,
        )
    }

    private fun KtImportDirective.isUsedImport(resolvedImportableFqNames: Set<String>): Boolean {
        val importedFqName = importedFqName?.asString() ?: return true
        return if (isAllUnder) {
            resolvedImportableFqNames.any { fqName -> fqName.startsWith("$importedFqName.") }
        } else {
            importedFqName in resolvedImportableFqNames
        }
    }

    private fun KtImportDirective.coversImport(fqName: String): Boolean {
        val importedFqName = importedFqName?.asString() ?: return false
        return if (isAllUnder) {
            fqName.startsWith("$importedFqName.")
        } else {
            importedFqName == fqName
        }
    }

    private fun resolvedImportableFqNames(file: KtFile): Set<String> {
        val referenceExpressions = mutableListOf<KtNameReferenceExpression>()
        file.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    when (element) {
                        is KtImportDirective,
                        is KtPackageDirective,
                        -> return

                        is KtNameReferenceExpression -> referenceExpressions += element
                    }
                    super.visitElement(element)
                }
            },
        )

        return analyze(file) {
            referenceExpressions.mapNotNull { expression ->
                expression.references
                    .filterIsInstance<KtReference>()
                    .firstOrNull()
                    ?.resolveToSymbol()
                    ?.importableFqName
                    ?.asString()
            }.toSet()
        }
    }

    private fun offsetAfterTrailingLineBreak(text: String, offset: Int): Int = when {
        offset >= text.length -> offset
        text.startsWith("\r\n", offset) -> offset + 2
        text[offset] == '\n' || text[offset] == '\r' -> offset + 1
        else -> offset
    }
}
