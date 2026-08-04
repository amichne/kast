package io.github.amichne.kast.idea.transition

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.nio.file.Path

internal class WorkspaceVfsObservationScope(
    workspaceRoot: Path,
    buildSemanticRoot: Path = workspaceRoot,
    configurationFiles: Set<Path>,
    val compilerSourceRoots: () -> Set<Path> = { emptySet() },
    val classpathRoots: () -> Set<Path> = { emptySet() },
) {
    val workspaceRoot: Path = workspaceRoot.toAbsolutePath().normalize()
    val buildSemanticRoot: Path = buildSemanticRoot.toAbsolutePath().normalize()
    val configurationFiles: Set<Path> = configurationFiles
        .mapTo(linkedSetOf()) { path -> path.toAbsolutePath().normalize() }
}

internal class WorkspaceVfsSignalClassifier(
    scope: WorkspaceVfsObservationScope,
) {
    private val root = scope.workspaceRoot
    private val buildSemanticRoot = scope.buildSemanticRoot
    private val configurationFiles = scope.configurationFiles
    private val compilerSourceRoots = scope.compilerSourceRoots
    private val classpathRoots = scope.classpathRoots

    fun classify(path: Path): WorkspaceSignal? {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized in configurationFiles) return WorkspaceSignal.Configuration
        if (isIdeaCompilerConfiguration(normalized)) return WorkspaceSignal.SemanticEnvironment
        if (isWithinAny(normalized, classpathRoots)) return WorkspaceSignal.SemanticEnvironment
        val fileName = normalized.fileName?.toString().orEmpty()
        val extension = fileName.substringAfterLast('.', "")
        if (extension in SOURCE_EXTENSIONS && isWithinAny(normalized, compilerSourceRoots)) {
            return WorkspaceSignal.Source
        }
        if (normalized.startsWith(buildSemanticRoot)) {
            val buildRelative = buildSemanticRoot.relativize(normalized)
            if (BuildSemanticInputPolicy.includes(buildRelative)) return WorkspaceSignal.BuildSemantic
        }
        if (!normalized.startsWith(root)) return null
        val relative = root.relativize(normalized)
        val segments = relative.map(Path::toString)
        if (segments.firstOrNull() == ".git") return WorkspaceSignal.GitWorktree
        if (fileName in SCOPE_FILES) return WorkspaceSignal.Scope
        if (extension in SOURCE_EXTENSIONS) return WorkspaceSignal.Source
        if (segments.any { it in GENERATED_DIRECTORIES }) return null
        return WorkspaceSignal.Source.takeIf { extension.isEmpty() }
    }

    private fun isIdeaCompilerConfiguration(path: Path): Boolean =
        sequenceOf(root, buildSemanticRoot)
            .distinct()
            .filter(path::startsWith)
            .map { authority -> authority.relativize(path) }
            .any { relative ->
                relative.nameCount == 2 &&
                    relative.getName(0).toString() == ".idea" &&
                    relative.fileName.toString() in IDEA_COMPILER_CONFIGURATION_FILES
            }

    private fun isWithinAny(path: Path, roots: () -> Set<Path>): Boolean =
        runCatching {
            roots().any { authority ->
                val normalized = authority.toAbsolutePath().normalize()
                path == normalized || path.startsWith(normalized)
            }
        }.getOrDefault(false)

    private companion object {
        val GENERATED_DIRECTORIES = setOf("build", ".gradle", ".idea", ".kotlin", "node_modules", "out")
        val SOURCE_EXTENSIONS = setOf("java", "kt", "kts")
        val IDEA_COMPILER_CONFIGURATION_FILES = setOf("compiler.xml", "kotlinc.xml", "misc.xml")
        val SCOPE_FILES = setOf(".kastignore")
    }
}

internal fun VFileEvent.affectedPaths(): Sequence<Path> {
    val affected = when (this) {
        is VFileMoveEvent -> sequenceOf(oldPath, newPath)
        is VFilePropertyChangeEvent -> if (isRename) sequenceOf(oldPath, newPath) else sequenceOf(path)
        else -> sequenceOf(path)
    }
    return affected
        .mapNotNull { raw -> runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull() }
        .distinct()
}

internal class WorkspaceVfsEventObserver private constructor(
    private val disconnect: () -> Unit,
) : AutoCloseable {
    override fun close() = disconnect()

    companion object {
        fun subscribe(
            project: Project,
            scope: WorkspaceVfsObservationScope,
            observed: (WorkspaceSignal) -> Unit,
        ): WorkspaceVfsEventObserver {
            val classifier = WorkspaceVfsSignalClassifier(scope)
            val connection = project.messageBus.connect()
            connection.subscribe(
                VirtualFileManager.VFS_CHANGES_BG,
                object : BulkFileListenerBackgroundable {
                    override fun after(events: List<VFileEvent>) {
                        events.asSequence()
                            .flatMap(VFileEvent::affectedPaths)
                            .mapNotNull(classifier::classify)
                            .distinct()
                            .forEach(observed)
                    }
                },
            )
            return WorkspaceVfsEventObserver(connection::disconnect)
        }
    }
}
