package io.github.amichne.kast.shared.analysis

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import io.github.amichne.kast.indexstore.SymbolReferenceRow
import java.util.concurrent.CancellationException

class PsiReferenceScanner(
    private val environment: ReferenceIndexEnvironment,
) {
    fun scanFileReferences(filePath: String): List<SymbolReferenceRow> {
        val rows = mutableListOf<SymbolReferenceRow>()
        // Exclusive access required: the standalone backend's K2 FIR lazy declaration
        // resolver is not thread-safe for concurrent resolution within a single session.
        // The IntelliJ backend implements this as a plain read action.
        environment.withExclusiveAccess {
            val psiFile = environment.findPsiFile(filePath) ?: return@withExclusiveAccess
            val sourceFilePath = runCatching { psiFile.resolvedFilePath().value }.getOrElse { filePath }

            psiFile.accept(
                object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (environment.isCancelled()) {
                            stopWalking()
                            return
                        }
                        ProgressManager.checkCanceled()
                        element.references.forEach { reference ->
                            try {
                                val resolved = reference.resolve() ?: return@forEach
                                val (fqName, _) = resolved.targetFqNameAndPackage() ?: return@forEach
                                val targetPath = runCatching { resolved.resolvedFilePath().value }.getOrNull()
                                val targetOffset = resolved.textRange?.startOffset
                                val sourceOffset = reference.element.textRange.startOffset +
                                    reference.rangeInElement.startOffset
                                rows += SymbolReferenceRow(
                                    sourcePath = sourceFilePath,
                                    sourceOffset = sourceOffset,
                                    targetFqName = fqName.value,
                                    targetPath = targetPath,
                                    targetOffset = targetOffset,
                                )
                            } catch (error: ProcessCanceledException) {
                                throw error
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // Skip one bad reference while continuing to index the file.
                            }
                        }
                        super.visitElement(element)
                    }
                },
            )
        }
        return rows
    }
}
