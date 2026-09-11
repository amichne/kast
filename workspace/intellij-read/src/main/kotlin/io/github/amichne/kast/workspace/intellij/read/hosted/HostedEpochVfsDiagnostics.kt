package io.github.amichne.kast.workspace.intellij.read.hosted

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
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
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject

/** Observational subscription shares endpoint retirement, but never changes epoch or read admission. */
internal class HostedEpochVfsDiagnostics(
    private val project: Project,
    private val owner: Disposable,
    private val host: IdeReadHostLifetime,
) {
    private sealed interface Binding {
        data object Unbound : Binding

        data class Bound(val root: CanonicalWorkspaceRoot) : Binding
    }

    private var binding: Binding = Binding.Unbound

    @Synchronized
    fun bind(admitted: AdmittedIdeProject, limits: ReadLimits) {
        when (val current = binding) {
            Binding.Unbound -> {
                val root = admitted.canonicalRoot
                project.messageBus
                    .connect(owner)
                    .subscribe(VirtualFileManager.VFS_CHANGES, HostedVfsDiagnosticListener(root, host, limits))
                binding = Binding.Bound(root)
            }
            is Binding.Bound ->
                if (current.root != admitted.canonicalRoot) {
                    publishHostedVfsEvidence(
                        host,
                        HostedVfsBatchEvidence.Rejected(HostedVfsObservationFailure.PATH_UNPROVEN),
                        ::publish,
                    )
                }
        }
    }
}

internal class HostedVfsDiagnosticListener(
    private val root: CanonicalWorkspaceRoot,
    private val host: IdeReadHostLifetime,
    private val limits: ReadLimits,
) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val evidence =
            if (events.size > limits[ReadLimitParameter.EPOCH_VFS_EVENTS].value) {
                HostedVfsBatchEvidence.Rejected(HostedVfsObservationFailure.BATCH_LIMIT)
            } else observeHostedVfsBatch(root, events.map(::boundary), limits)
        publishHostedVfsEvidence(host, evidence, ::publish)
    }
}

private fun boundary(event: VFileEvent): HostedVfsEventBoundary {
    val origin = if (event.isFromRefresh) HostedVfsEventOrigin.REFRESH else HostedVfsEventOrigin.IDE
    val paths =
        when (event) {
            is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
            is VFilePropertyChangeEvent ->
                if (event.isRename) listOf(event.oldPath, event.newPath) else listOf(event.path)
            else -> listOf(event.path)
        }
    val kind =
        when (event) {
            is VFileCreateEvent -> HostedVfsEventKind.CREATE
            is VFileDeleteEvent -> HostedVfsEventKind.DELETE
            is VFileCopyEvent -> HostedVfsEventKind.COPY
            is VFileMoveEvent -> HostedVfsEventKind.MOVE
            is VFilePropertyChangeEvent ->
                if (event.isRename) HostedVfsEventKind.RENAME else HostedVfsEventKind.PROPERTY
            is VFileContentChangeEvent -> HostedVfsEventKind.CONTENT
            else -> HostedVfsEventKind.OTHER
        }
    return HostedVfsEventBoundary(kind, origin, paths)
}

private fun publish(document: String) {
    Logger.getInstance(HostedEpochVfsDiagnostics::class.java).info(document)
}
