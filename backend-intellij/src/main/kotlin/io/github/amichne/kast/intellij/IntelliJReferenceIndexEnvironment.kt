package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.shared.analysis.ReferenceIndexEnvironment
import org.jetbrains.kotlin.idea.KotlinFileType
import java.nio.file.Path
import java.util.concurrent.Callable

internal class IntelliJReferenceIndexEnvironment(
    private val project: Project,
    private val workspaceRoot: Path,
    private val cancelled: () -> Boolean,
) : ReferenceIndexEnvironment {
    override fun allFilePaths(): Collection<String> = withReadAccess {
        FileTypeIndex
            .getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter { file -> file.isValid && FileTypeRegistry.getInstance().isFileOfType(file, KotlinFileType.INSTANCE) }
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
