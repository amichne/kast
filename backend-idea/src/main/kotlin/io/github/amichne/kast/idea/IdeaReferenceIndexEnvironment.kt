package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.shared.analysis.ReferenceIndexEnvironment
import java.nio.file.Path
import java.util.concurrent.Callable

internal class IdeaReferenceIndexEnvironment(
    private val project: Project,
    private val workspaceRoot: Path,
    private val cancelled: () -> Boolean,
) : ReferenceIndexEnvironment {
    override fun allFilePaths(): Collection<String> = withReadAccess {
        val kotlinFileType = FileTypeManager.getInstance().findFileTypeByName("Kotlin")
            ?: return@withReadAccess emptyList()
        FileTypeIndex
            .getFiles(kotlinFileType, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter { file -> file.isValid && file.fileType == kotlinFileType }
            .map { file -> Path.of(file.path).toAbsolutePath().normalize() }
            .filter { path -> path.startsWith(workspaceRoot) }
            .filter(SourceIndexFilePolicy::isEligible)
            .map(Path::toString)
            .sorted()
            .toList()
    }

    override fun findPsiFile(filePath: String): PsiFile? = withReadAccess {
        val path = Path.of(filePath).toAbsolutePath().normalize()
        if (!path.startsWith(workspaceRoot)) return@withReadAccess null
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return@withReadAccess null
        PsiManager.getInstance(project).findFile(virtualFile)
    }

    override fun <T> withReadAccess(action: () -> T): T =
        ApplicationManager.getApplication().runReadAction<T>(action)

    override fun <T> withExclusiveAccess(action: () -> T): T =
        ReadAction
            .nonBlocking(Callable { action() })
            .expireWhen { cancelled() }
            .executeSynchronously()

    override fun isCancelled(): Boolean = cancelled()
}
