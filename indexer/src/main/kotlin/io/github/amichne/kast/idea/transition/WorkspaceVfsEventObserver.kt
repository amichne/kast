package io.github.amichne.kast.idea.transition

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Path

internal class WorkspaceVfsSignalClassifier(
    workspaceRoot: Path,
) {
    private val root = workspaceRoot.toAbsolutePath().normalize()

    fun classify(path: Path): WorkspaceSignal? {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) return null
        val relative = root.relativize(normalized)
        val segments = relative.map(Path::toString)
        if (segments.firstOrNull() == ".git") return WorkspaceSignal.GitWorktree
        val fileName = normalized.fileName?.toString().orEmpty()
        if (isBuildSemantic(relative, fileName)) return WorkspaceSignal.BuildSemantic
        if (fileName.substringAfterLast('.', "") in SOURCE_EXTENSIONS) {
            return WorkspaceSignal.Source
        }
        if (segments.any { it in GENERATED_DIRECTORIES }) return null
        if (fileName in SCOPE_FILES) return WorkspaceSignal.Scope
        if (fileName in CONFIGURATION_FILES) return WorkspaceSignal.Configuration
        return WorkspaceSignal.Source
    }

    private fun isBuildSemantic(relative: Path, fileName: String): Boolean =
        fileName in BUILD_FILES ||
            relative.startsWith("buildSrc") ||
            relative.startsWith("build-logic") ||
            relative.startsWith("gradle")

    private companion object {
        val GENERATED_DIRECTORIES = setOf("build", ".gradle", "out", ".idea", "node_modules", "target")
        val SOURCE_EXTENSIONS = setOf("kt", "kts", "java")
        val BUILD_FILES = setOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
        )
        val SCOPE_FILES = setOf(".kastignore")
        val CONFIGURATION_FILES = setOf("config.toml")
    }
}

internal class WorkspaceVfsEventObserver private constructor(
    private val disconnect: () -> Unit,
) : AutoCloseable {
    override fun close() = disconnect()

    companion object {
        fun subscribe(
            project: Project,
            workspaceRoot: Path,
            observed: (WorkspaceSignal) -> Unit,
        ): WorkspaceVfsEventObserver {
            val classifier = WorkspaceVfsSignalClassifier(workspaceRoot)
            val connection = project.messageBus.connect()
            connection.subscribe(
                VirtualFileManager.VFS_CHANGES_BG,
                object : BulkFileListenerBackgroundable {
                    override fun after(events: List<VFileEvent>) {
                        events.asSequence()
                            .mapNotNull { event -> runCatching { Path.of(event.path) }.getOrNull() }
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
