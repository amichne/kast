package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.amichne.kast.runtime.hosted.workspace.WorkspaceRefreshEffectResult
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceReadinessDetail
import org.jetbrains.plugins.gradle.service.project.GradleAutoImportAware

/** A changed Gradle input is a presemantic blocker until a native import proves a new model. */
internal class HostedGradleChangeTracker(project: Project, private val root: CanonicalWorkspaceRoot) : Disposable {
    val revision = HostedGradleRevision()
    private val awareness = GradleAutoImportAware()
    private val connection = project.messageBus.connect()

    init {
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.size > MAXIMUM_VFS_EVENTS || events.any { affectsModel(it, project) }) revision.changed()
                }
            },
        )
    }

    private fun affectsModel(event: VFileEvent, project: Project): Boolean =
        event.paths().any { path ->
            if (!path.isWithin(root)) return@any false
            try {
                awareness.getAffectedExternalProjectPath(path, project)?.isWithin(root) == true
            } catch (cancellation: ProcessCanceledException) {
                throw cancellation
            } catch (_: RuntimeException) {
                true
            }
        }

    override fun dispose() = connection.disconnect()

    private companion object {
        const val MAXIMUM_VFS_EVENTS = 4_096
    }
}

private fun VFileEvent.paths(): List<String> =
    when (this) {
        is VFileMoveEvent -> listOf(oldPath, newPath)
        is VFilePropertyChangeEvent -> if (isRename) listOf(oldPath, newPath) else listOf(path)
        else -> listOf(path)
    }

internal fun String.isWithin(root: CanonicalWorkspaceRoot): Boolean = this == root.value || startsWith("${root.value}/")

/** An import can discharge only the change revision it observed before starting. */
internal class HostedGradleRevision {
    private var current = 0L
    private var imported = 0L
    private var latestOwnedImportCovered: Long? = null

    @Synchronized fun current(): Long = current

    @Synchronized fun pending(): Boolean = current > imported

    @Synchronized
    fun pendingEvidence(): WorkspaceReadinessDetail.PendingModelInputs? =
        if (current > imported) WorkspaceReadinessDetail.PendingModelInputs(current, imported, latestOwnedImportCovered)
        else null

    @Synchronized
    fun changed() {
        if (current != Long.MAX_VALUE) current++
    }

    @Synchronized
    fun beginOwnedImport(): ImportTicket {
        latestOwnedImportCovered = current
        return ImportTicket.capture(this)
    }

    @Synchronized
    private fun acknowledge(startedAt: Long) {
        imported = maxOf(imported, startedAt)
    }

    /** Only this owner can capture coverage; one terminal settlement may discharge only that captured revision. */
    class ImportTicket private constructor(private val owner: HostedGradleRevision, private val covered: Long) {
        private val settled = java.util.concurrent.atomic.AtomicBoolean(false)

        fun settled(result: WorkspaceRefreshEffectResult) {
            if (result == WorkspaceRefreshEffectResult.RETIRED) return
            if (!settled.compareAndSet(false, true)) return
            if (result == WorkspaceRefreshEffectResult.SUCCEEDED) owner.acknowledge(covered)
        }

        companion object {
            internal fun capture(owner: HostedGradleRevision): ImportTicket = ImportTicket(owner, owner.current())
        }
    }
}
