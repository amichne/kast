package io.github.amichne.kast.idea.transition

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface WorkspaceVfsSignalAdmission {
    data class Required(val signal: WorkspaceSignal) : WorkspaceVfsSignalAdmission

    data object SubsumedByGlobalRefresh : WorkspaceVfsSignalAdmission
}

internal sealed interface WorkspaceVfsEventOrigin {
    data object Refresh : WorkspaceVfsEventOrigin

    data object ExternalOrProgrammatic : WorkspaceVfsEventOrigin

    companion object {
        /**
         * Boundary transition: `VFileEvent -> WorkspaceVfsEventOrigin`.
         *
         * Preserves IntelliJ's refresh-origin provenance as a closed domain
         * state. Raw [VFileEvent.isFromRefresh] extraction is permitted only
         * at this VFS observer boundary.
         */
        fun from(event: VFileEvent): WorkspaceVfsEventOrigin =
            if (event.isFromRefresh) Refresh else ExternalOrProgrammatic
    }
}

internal data class WorkspaceVfsSignalObservation(
    val signal: WorkspaceSignal,
    val origin: WorkspaceVfsEventOrigin,
)

private data class WorkspaceVfsPathObservation(
    val path: Path,
    val origin: WorkspaceVfsEventOrigin,
)

internal class CoordinatedVfsRefreshAuthority {
    private val activeGlobalRefreshes = AtomicInteger()

    /**
     * Proof transition: `WorkspaceVfsSignalObservation -> WorkspaceVfsSignalAdmission`.
     *
     * Establishes whether the signal requires a later transition or was
     * refresh-originated and delivered while the coordinated global refresh
     * was already making that VFS state current. The closed admission result
     * preserves non-refresh events as required work. Raw atomic refresh state
     * is interpreted only at this observer boundary.
     */
    fun admit(observation: WorkspaceVfsSignalObservation): WorkspaceVfsSignalAdmission =
        if (
            activeGlobalRefreshes.get() > 0 &&
            observation.origin == WorkspaceVfsEventOrigin.Refresh
        ) {
            WorkspaceVfsSignalAdmission.SubsumedByGlobalRefresh
        } else {
            WorkspaceVfsSignalAdmission.Required(observation.signal)
        }

    /**
     * Runs [effect] inside the coordinated global-VFS effect scope.
     *
     * This is an effect boundary, not a validation transition. It provides the
     * temporal authority consumed by [admit]; refresh-origin provenance remains
     * a separate requirement before any observation can be subsumed.
     */
    fun <T> runGlobalRefresh(effect: () -> T): T {
        activeGlobalRefreshes.incrementAndGet()
        return try {
            effect()
        } finally {
            check(activeGlobalRefreshes.decrementAndGet() >= 0) {
                "Coordinated global VFS refresh authority underflowed"
            }
        }
    }
}

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
            refreshAuthority: CoordinatedVfsRefreshAuthority = CoordinatedVfsRefreshAuthority(),
            observed: (WorkspaceSignal) -> Unit,
        ): WorkspaceVfsEventObserver {
            val classifier = WorkspaceVfsSignalClassifier(scope)
            val connection = project.messageBus.connect()
            connection.subscribe(
                VirtualFileManager.VFS_CHANGES_BG,
                object : BulkFileListenerBackgroundable {
                    override fun after(events: List<VFileEvent>) {
                        events.asSequence()
                            .flatMap { event ->
                                val origin = WorkspaceVfsEventOrigin.from(event)
                                event.affectedPaths().map { path -> WorkspaceVfsPathObservation(path, origin) }
                            }
                            .mapNotNull { observation ->
                                classifier.classify(observation.path)?.let { signal ->
                                    WorkspaceVfsSignalObservation(signal, observation.origin)
                                }
                            }
                            .map(refreshAuthority::admit)
                            .mapNotNull { admission ->
                                when (admission) {
                                    is WorkspaceVfsSignalAdmission.Required -> admission.signal
                                    WorkspaceVfsSignalAdmission.SubsumedByGlobalRefresh -> null
                                }
                            }
                            .distinct()
                            .forEach(observed)
                    }
                },
            )
            return WorkspaceVfsEventObserver(connection::disconnect)
        }
    }
}
