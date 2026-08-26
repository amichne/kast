package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure

/** KVP-017 root-filtered, batch-bounded VFS observer with no semantic authority. */
internal class RootFilteredProjectEpochVfsListener(
    private val root: ProjectReadEpochVfsRoot,
    private val counter: ProjectReadEpochMetadataCounter,
) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.size > PROJECT_READ_EPOCH_MAX_VFS_EVENTS_PER_BATCH) {
            counter.reject(ProjectReadEpochObservationFailure.VfsBatchLimitExceeded)
            return
        }
        val observed = ArrayList<ProjectReadEpochVfsEvent>(events.size)
        for (event in events) {
            observed += when (event) {
                is VFileMoveEvent -> ProjectReadEpochVfsEvent.Move(event.oldPath, event.newPath)
                is VFilePropertyChangeEvent -> if (event.isRename) {
                    ProjectReadEpochVfsEvent.Rename(event.oldPath, event.newPath)
                } else {
                    ProjectReadEpochVfsEvent.Change(event.path)
                }
                else -> ProjectReadEpochVfsEvent.Change(event.path)
            }
        }
        when (val result = observeProjectReadEpochVfsBatch(root, observed)) {
            ProjectReadEpochVfsBatchObservation.OutsideRoot -> Unit
            ProjectReadEpochVfsBatchObservation.TouchesRoot -> counter.advance()
            is ProjectReadEpochVfsBatchObservation.Rejected -> counter.reject(result.failure)
        }
    }
}
