package io.github.amichne.kast.idea

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Path

internal interface IdeaSemanticAdmissionOperations {
    fun refreshAndFind(filePath: Path): VirtualFile?

    fun collectDiagnostics(file: KtFile)

    companion object {
        fun idea(): IdeaSemanticAdmissionOperations = object : IdeaSemanticAdmissionOperations {
            override fun refreshAndFind(filePath: Path): VirtualFile? =
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(filePath)

            override fun collectDiagnostics(file: KtFile) {
                analyze(file) {
                    file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                }
            }
        }
    }
}
