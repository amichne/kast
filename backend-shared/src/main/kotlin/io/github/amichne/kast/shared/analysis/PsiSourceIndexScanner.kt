package io.github.amichne.kast.shared.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import io.github.amichne.kast.indexstore.FileIndexUpdate
import io.github.amichne.kast.indexstore.parseSourceFileIndex

class PsiSourceIndexScanner(
    private val environment: ReferenceIndexEnvironment,
    private val moduleNameForFile: (PsiFile) -> String? = { null },
) {
    fun scanFile(filePath: String): FileIndexUpdate? = environment.withReadAccess {
        if (environment.isCancelled()) return@withReadAccess null
        ProgressManager.checkCanceled()
        val psiFile = environment.findPsiFile(filePath) ?: return@withReadAccess null
        val sourcePath = runCatching { psiFile.resolvedFilePath().value }.getOrElse { filePath }
        parseSourceFileIndex(
            path = sourcePath,
            content = psiFile.text,
            moduleName = moduleNameForFile(psiFile),
        )
    }
}
