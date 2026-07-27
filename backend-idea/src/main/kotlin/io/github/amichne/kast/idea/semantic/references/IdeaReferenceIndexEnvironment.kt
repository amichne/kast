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
import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.shared.analysis.ReferenceIndexEnvironment
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable

internal class IdeaReferenceIndexEnvironment(
    private val project: Project,
    private val workspaceIdentity: WorkspaceIdentity,
    private val cancelled: () -> Boolean,
) : ReferenceIndexEnvironment {
    constructor(
        project: Project,
        workspaceRoot: Path,
        cancelled: () -> Boolean,
    ) : this(project, WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot), cancelled)

    override fun allFilePaths(): Collection<String> {
        val projectScopePaths = projectScopeKotlinFilePaths()
        val filesystemPaths = discoverWorkspaceKotlinFilePaths(workspaceIdentity.workspaceRootPath, cancelled)
        return (projectScopePaths.asSequence() + filesystemPaths.asSequence())
            .distinct()
            .sorted()
            .toList()
    }

    private fun projectScopeKotlinFilePaths(): Collection<String> = withReadAccess {
        val kotlinFileType = FileTypeManager.getInstance().findFileTypeByName("Kotlin")
            ?: return@withReadAccess emptyList()
        FileTypeIndex
            .getFiles(kotlinFileType, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter { file -> file.isValid && file.fileType == kotlinFileType }
            .map { file -> Path.of(file.path).toAbsolutePath().normalize() }
            .filter(workspaceIdentity::contains)
            .filter(SourceIndexFilePolicy::isEligible)
            .map(Path::toString)
            .sorted()
            .toList()
    }

    override fun findPsiFile(filePath: String): PsiFile? {
        val path = Path.of(filePath).toAbsolutePath().normalize()
        if (!workspaceIdentity.contains(path)) return null
        val fileSystem = LocalFileSystem.getInstance()
        val virtualFile = fileSystem.findFileByNioFile(path)
            ?: fileSystem.refreshAndFindFileByNioFile(path)
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

internal fun discoverWorkspaceKotlinFilePaths(
    workspaceRoot: Path,
    isCancelled: () -> Boolean,
): List<String> {
    val root = workspaceRoot.toAbsolutePath().normalize()
    if (root.parent == null) return emptyList()
    if (!Files.isDirectory(root)) return emptyList()
    val paths = mutableListOf<String>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (isCancelled()) return FileVisitResult.TERMINATE
                if (dir != root && dir.fileName?.toString() in EXCLUDED_WORKSPACE_SCAN_DIRECTORIES) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                if (isCancelled()) return FileVisitResult.TERMINATE
                val normalized = file.toAbsolutePath().normalize()
                if (attrs.isRegularFile && SourceIndexFilePolicy.isEligible(normalized)) {
                    paths += normalized.toString()
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                exc: java.io.IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    return paths.sorted()
}

private val EXCLUDED_WORKSPACE_SCAN_DIRECTORIES = setOf(
    ".git",
    ".gradle",
    ".idea",
    ".kast",
    "build",
    "out",
    "target",
    "node_modules",
)
