package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.ReadLimitParameter
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure

/** project-read epoch root-filtered, batch-bounded VFS observer with no semantic authority. */
internal class RootFilteredProjectEpochVfsListener(
    private val root: ProjectReadEpochVfsRoot,
    private val counter: ProjectReadEpochMetadataCounter,
    private val limits: ReadLimits = ReadLimits.Default,
) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.size > limits[ReadLimitParameter.EPOCH_VFS_EVENTS].value) {
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
        when (val result = observeProjectReadEpochVfsBatch(root, observed, limits)) {
            ProjectReadEpochVfsBatchObservation.OutsideRoot -> Unit
            ProjectReadEpochVfsBatchObservation.TouchesRoot -> counter.advance()
            is ProjectReadEpochVfsBatchObservation.Rejected -> counter.reject(result.failure)
        }
    }
}
