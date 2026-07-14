package io.github.amichne.kast.shared.analysis

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.index.IndexedPackageUnprovenReason
import io.github.amichne.kast.indexstore.api.index.parseSourceFileIndex
import org.jetbrains.kotlin.psi.KtFile

class PsiSourceIndexScanner internal constructor(
    private val environment: ReferenceIndexEnvironment,
    private val moduleNameForFile: (PsiFile) -> String?,
    private val packageEvidenceForFile: (PsiFile) -> IndexedPackageEvidence,
) {
    constructor(
        environment: ReferenceIndexEnvironment,
        moduleNameForFile: (PsiFile) -> String? = { null },
    ) : this(environment, moduleNameForFile, ::structuredPackageEvidence)

    fun scanFile(filePath: String): FileIndexUpdate? = environment.withReadAccess {
        if (environment.isCancelled()) return@withReadAccess null
        ProgressManager.checkCanceled()
        val psiFile = environment.findPsiFile(filePath) ?: return@withReadAccess null
        val sourcePath = runCatching { psiFile.resolvedFilePath().value }.getOrElse { filePath }
        val parsed = parseSourceFileIndex(
            path = sourcePath,
            content = psiFile.text,
            moduleName = moduleNameForFile(psiFile),
        )
        val packageEvidence = try {
            packageEvidenceForFile(psiFile)
        } catch (cancellation: ProcessCanceledException) {
            throw cancellation
        } catch (_: Exception) {
            IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.SEMANTIC_ANALYSIS_FAILED)
        }
        parsed.copy(packageEvidence = packageEvidence)
    }

    private companion object {
        fun structuredPackageEvidence(psiFile: PsiFile): IndexedPackageEvidence =
            when (psiFile) {
                is KtFile -> {
                    val packageFqName = psiFile.packageFqName
                    if (packageFqName.isRoot) {
                        IndexedPackageEvidence.ProvenRoot
                    } else {
                        IndexedPackageEvidence.ProvenNamed(
                            IndexedPackageEvidence.CanonicalName.parse(packageFqName.asString()),
                        )
                    }
                }

                else -> IndexedPackageEvidence.Unproven(
                    IndexedPackageUnprovenReason.SEMANTIC_ANALYSIS_UNAVAILABLE,
                )
            }
    }
}
