package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import java.util.IdentityHashMap

/** Live imported-model ownership facts for VFS relevance; no traversal, refresh, or content read. */
internal fun interface ProjectReadEpochOwnershipPort {
    fun ownership(file: VirtualFile?): ProjectReadEpochVfsOwnership

    /** Fixture default: no ownership evidence, so every in-root endpoint stays conservatively relevant. */
    data object Unavailable : ProjectReadEpochOwnershipPort {
        override fun ownership(file: VirtualFile?) = ProjectReadEpochVfsOwnership.UNKNOWN
    }
}

/**
 * Project-read epoch VFS observer with no semantic authority.
 *
 * `before` records prior ownership for deleted, moved, and renamed files while they still exist. `after` classifies
 * every endpoint, so a missing prior record stays unknown. No detached negative membership survives the batch.
 */
internal class RootFilteredProjectEpochVfsListener(
    private val root: ProjectReadEpochVfsRoot,
    private val counter: ProjectReadEpochMetadataCounter,
    private val limits: ReadLimits = ReadLimits.Default,
    private val ownership: ProjectReadEpochOwnershipPort = ProjectReadEpochOwnershipPort.Unavailable,
) : BulkFileListener {
    private var priors: Map<VFileEvent, ProjectReadEpochVfsOwnership> = emptyMap()

    override fun before(events: List<VFileEvent>) {
        val observed = IdentityHashMap<VFileEvent, ProjectReadEpochVfsOwnership>()
        if (events.size > limits[ReadLimitParameter.EPOCH_VFS_EVENTS].value) {
            synchronized(this) { priors = emptyMap() }
            return
        }
        for (event in events) {
            val prior = event.priorPath()
            if (prior != null && root.admits(prior, limits)) observed[event] = ownership.ownership(event.file)
        }
        synchronized(this) { priors = observed }
    }

    private fun VFileEvent.priorPath(): String? =
        when (this) {
            is VFileDeleteEvent -> path
            is VFileMoveEvent -> oldPath
            is VFilePropertyChangeEvent -> if (isRename) oldPath else null
            else -> null
        }

    override fun after(events: List<VFileEvent>) {
        val recorded = synchronized(this) { priors.also { priors = emptyMap() } }
        if (events.size > limits[ReadLimitParameter.EPOCH_VFS_EVENTS].value) {
            // Relevance is unknown: invalidate without projecting or traversing any event.
            counter.advance()
            return
        }
        val observed = ArrayList<ProjectReadEpochVfsEvent>(events.size)
        for (event in events) observed += project(event, recorded)
        when (val result = observeProjectReadEpochVfsBatch(root, observed, limits)) {
            ProjectReadEpochVfsBatchObservation.Irrelevant -> Unit
            ProjectReadEpochVfsBatchObservation.Relevant,
            ProjectReadEpochVfsBatchObservation.RelevanceUnknown -> {
                counter.advance()
            }
            is ProjectReadEpochVfsBatchObservation.Rejected -> {
                counter.reject(result.failure)
            }
        }
    }

    private fun project(
        event: VFileEvent,
        priors: Map<VFileEvent, ProjectReadEpochVfsOwnership>,
    ): ProjectReadEpochVfsEvent {
        val prior = priors[event] ?: ProjectReadEpochVfsOwnership.UNKNOWN
        val endpoints =
            when (event) {
                is VFileDeleteEvent -> listOf(ProjectReadEpochVfsEndpoint(event.path, prior))
                is VFileMoveEvent ->
                    listOf(
                        ProjectReadEpochVfsEndpoint(event.oldPath, prior),
                        endpoint(event.newPath, event.file),
                    )
                is VFilePropertyChangeEvent ->
                    if (event.isRename) {
                        listOf(
                            ProjectReadEpochVfsEndpoint(event.oldPath, prior),
                            endpoint(event.newPath, event.file),
                        )
                    } else {
                        listOf(endpoint(event.path, event.file))
                    }
                is VFileCopyEvent -> listOf(endpoint(event.path, event.findCreatedFile()))
                is VFileCreateEvent,
                is VFileContentChangeEvent -> listOf(endpoint(event.path, event.file))
                else -> listOf(ProjectReadEpochVfsEndpoint(event.path, ProjectReadEpochVfsOwnership.UNKNOWN))
            }
        return ProjectReadEpochVfsEvent(endpoints)
    }

    private fun endpoint(path: String, file: VirtualFile?): ProjectReadEpochVfsEndpoint =
        ProjectReadEpochVfsEndpoint(
            path,
            if (root.admits(path, limits)) ownership.ownership(file) else ProjectReadEpochVfsOwnership.UNKNOWN,
        )
}

/** IDEA 262 project file index ownership; the module is the exclusive `ProjectFileIndex` authority. */
internal class LiveProjectReadEpochOwnership(private val project: Project) : ProjectReadEpochOwnershipPort {
    override fun ownership(file: VirtualFile?): ProjectReadEpochVfsOwnership =
        observe(ProjectReadEpochVfsOwnership.UNKNOWN) {
            if (project.isDisposed) {
                ProjectReadEpochVfsOwnership.UNKNOWN
            } else if (file == null || !file.isValid || file.isDirectory) {
                ProjectReadEpochVfsOwnership.UNKNOWN
            } else {
                val index = ProjectFileIndex.getInstance(project)
                if (index.isInSourceContent(file) || index.isInLibrary(file)) {
                    ProjectReadEpochVfsOwnership.ADMITTED
                } else {
                    ProjectReadEpochVfsOwnership.UNOWNED
                }
            }
        }

    private inline fun <Value> observe(unavailable: Value, operation: () -> Value): Value =
        try {
            operation()
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (_: RuntimeException) {
            unavailable
        }
}
