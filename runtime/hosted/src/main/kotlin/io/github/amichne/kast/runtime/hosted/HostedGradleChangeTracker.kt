package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.plugins.gradle.service.project.GradleAutoImportAware

/** A changed Gradle input is a presemantic blocker until a native import proves a new model. */
internal class HostedGradleChangeTracker(project: Project, private val root: CanonicalWorkspaceRoot) : Disposable {
    private val revision = HostedGradleRevision()
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

    fun currentRevision(): Long = revision.current()

    fun needsModelReload(): Boolean = revision.pending()

    fun needsOpeningImport(): Boolean = revision.needsOpeningImport()

    fun modelImported(startedAt: Long) = revision.acknowledge(startedAt)

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
    private val current = AtomicLong(0)
    private val imported = AtomicLong(0)
    private val openingImportComplete = java.util.concurrent.atomic.AtomicBoolean(false)

    fun current(): Long = current.get()

    fun pending(): Boolean = current.get() > imported.get()

    fun needsOpeningImport(): Boolean = !openingImportComplete.get() || pending()

    fun changed() {
        current.updateAndGet { if (it == Long.MAX_VALUE) it else it + 1 }
    }

    fun acknowledge(startedAt: Long) {
        imported.updateAndGet { maxOf(it, startedAt) }
        openingImportComplete.set(true)
    }
}
