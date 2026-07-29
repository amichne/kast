package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.shared.analysis.ReferenceIndexEnvironment
import java.nio.file.Path
import java.util.concurrent.Callable

internal class IdeaReferenceIndexEnvironment(
    private val project: Project,
    private val workspaceIdentity: WorkspaceIdentity,
    private val cancelled: () -> Boolean,
    private val findVirtualFile: (Path) -> VirtualFile? = {
        LocalFileSystem.getInstance().findFileByNioFile(it)
    },
    private val refreshVirtualFile: (Path) -> VirtualFile? = {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
    },
    private val psiFileForVirtualFile: (VirtualFile) -> PsiFile? = {
        PsiManager.getInstance(project).findFile(it)
    },
) : ReferenceIndexEnvironment {
    constructor(
        project: Project,
        workspaceRoot: Path,
        cancelled: () -> Boolean,
    ) : this(project, WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot), cancelled)

    override fun findPsiFile(filePath: String): PsiFile? =
        withPsiFileReadAccess(filePath) { psiFile -> psiFile }

    override fun <T> withPsiFileReadAccess(
        filePath: String,
        action: (PsiFile) -> T,
    ): T? = withPsiFileAccess(filePath, action)

    override fun <T> withPsiFileExclusiveAccess(
        filePath: String,
        action: (PsiFile) -> T,
    ): T? = withPsiFileAccess(filePath, action)

    private fun <T> withPsiFileAccess(
        filePath: String,
        action: (PsiFile) -> T,
    ): T? {
        val path = Path.of(filePath).toAbsolutePath().normalize()
        if (!workspaceIdentity.contains(path)) return null
        val virtualFile = findVirtualFile(path)
            ?: refreshVirtualFile(path)
            ?: return null
        return withExclusiveAccess {
            psiFileForVirtualFile(virtualFile)
                ?.takeIf { psiFile -> psiFile.isValid }
                ?.let(action)
        }
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
