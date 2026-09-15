package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectReadEpochVfsOverflowTest {
    private val limits =
        (ReadLimits.resolve(mapOf("KAST_READ_EPOCH_VFS_EVENTS" to "2")) as Refinement.Refined).value

    @Test
    fun `oversized batch moves old epoch and same source observes fresh epoch`() {
        val counter = ProjectReadEpochMetadataCounter()
        val listener = RootFilteredProjectEpochVfsListener(ProjectReadEpochVfsRoot.from(FIXTURE_ROOT), counter, limits)
        val source = LiveProjectReadEpochSource(
            RecordingProjectReadEpochPlatform(), ProjectReadEpochMetadataCounter(), counter,
            RecordingProjectReadEpochExecution(), limits,
        ).source
        val initial = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        listener.after(listOf(event("/workspace/other/a")))
        assertEquals(ProjectReadEpochSignalSample.Value(0), counter.sample())
        listener.after(listOf(event("/workspace/other/a"), event("/workspace/other/b"), event("/workspace/kast/c")))
        assertEquals(ProjectReadEpochSignalSample.Value(1), counter.sample())
        val fresh = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        assertEquals(ProjectReadEpochRelation.MOVED, initial.relationTo(fresh))
        listener.after(listOf(event("/workspace/kast/c")))
        val later = (source.observe() as ProjectReadEpochObservation.Observed).epoch
        assertEquals(ProjectReadEpochRelation.MOVED, initial.relationTo(later))
        assertEquals(ProjectReadEpochRelation.MOVED, fresh.relationTo(later))
    }

    private fun event(path: String): VFileEvent = object : VFileEvent(null) {
        override fun computePath(): String = path
        override fun getFile(): VirtualFile? = null
        override fun getFileSystem(): VirtualFileSystem = error("No filesystem access permitted")
        override fun isValid(): Boolean = true
        override fun hashCode(): Int = System.identityHashCode(this)
        override fun equals(other: Any?): Boolean = this === other
    }
}
