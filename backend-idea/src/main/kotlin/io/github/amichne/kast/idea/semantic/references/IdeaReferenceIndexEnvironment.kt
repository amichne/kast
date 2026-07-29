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
) : ReferenceIndexEnvironment {
    constructor(
        project: Project,
        workspaceRoot: Path,
        cancelled: () -> Boolean,
    ) : this(project, WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot), cancelled)

    override fun findPsiFile(filePath: String): PsiFile? {
        val path = Path.of(filePath).toAbsolutePath().normalize()
        if (!workspaceIdentity.contains(path)) return null
        val virtualFile = findVirtualFile(path)
            ?: refreshVirtualFile(path)
            ?: return null
        return withReadAccess { PsiManager.getInstance(project).findFile(virtualFile) }
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
