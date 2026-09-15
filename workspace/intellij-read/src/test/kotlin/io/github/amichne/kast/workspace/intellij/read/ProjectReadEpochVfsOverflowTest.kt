package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservation
import io.github.amichne.kast.workspace.contract.ProjectReadEpochObservationFailure
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectReadEpochVfsOverflowTest {
    private val limits = (ReadLimits.resolve(mapOf("KAST_READ_EPOCH_VFS_EVENTS" to "2")) as Refinement.Refined).value

    @Test
    fun `oversized batch moves old epoch and same source observes fresh epoch`() {
        val counter = ProjectReadEpochMetadataCounter()
        val listener = RootFilteredProjectEpochVfsListener(ProjectReadEpochVfsRoot.from(FIXTURE_ROOT), counter, limits)
        val source =
            LiveProjectReadEpochSource(
                    RecordingProjectReadEpochPlatform(),
                    ProjectReadEpochMetadataCounter(),
                    counter,
                    RecordingProjectReadEpochExecution(),
                    limits,
                )
                .source
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

    @Test
    fun `below and exact limit classify outside and relevant batches`() {
        for (count in 1..2) {
            val counter = ProjectReadEpochMetadataCounter()
            val listener =
                RootFilteredProjectEpochVfsListener(ProjectReadEpochVfsRoot.from(FIXTURE_ROOT), counter, limits)
            listener.after(List(count) { event("/workspace/other/a") })
            assertEquals(ProjectReadEpochSignalSample.Value(0), counter.sample())
            listener.after(List(count) { event("/workspace/kast/a") })
            assertEquals(ProjectReadEpochSignalSample.Value(1), counter.sample())
        }
    }

    @Test
    fun `oversized relevant foreign and mixed batches retain unknown relevance`() {
        val root = ProjectReadEpochVfsRoot.from(FIXTURE_ROOT)
        for (paths in
            listOf(
                listOf("/workspace/kast/a", "/workspace/kast/b", "/workspace/kast/c"),
                listOf("/workspace/other/a", "/workspace/other/b", "/workspace/other/c"),
                listOf("/workspace/other/a", "/workspace/other/b", "/workspace/kast/c"),
            )) {
            assertEquals(
                ProjectReadEpochVfsBatchObservation.RelevanceUnknown,
                observeProjectReadEpochVfsBatch(root, paths.map(ProjectReadEpochVfsEvent::Change), limits),
            )
            val counter = ProjectReadEpochMetadataCounter()
            RootFilteredProjectEpochVfsListener(root, counter, limits).after(paths.map(::event))
            assertEquals(ProjectReadEpochSignalSample.Value(1), counter.sample())
        }
    }

    @Test
    fun `oversized fast paths never read an event even for maximum list size`() {
        val root = ProjectReadEpochVfsRoot.from(FIXTURE_ROOT)
        val counter = ProjectReadEpochMetadataCounter()
        val native =
            object : AbstractList<VFileEvent>() {
                override val size: Int = Int.MAX_VALUE

                override fun get(index: Int): VFileEvent = error("Overflow must not traverse native events")
            }
        val projected =
            object : AbstractList<ProjectReadEpochVfsEvent>() {
                override val size: Int = Int.MAX_VALUE

                override fun get(index: Int): ProjectReadEpochVfsEvent =
                    error("Overflow must not traverse projected events")
            }
        RootFilteredProjectEpochVfsListener(root, counter, limits).after(native)
        assertEquals(ProjectReadEpochSignalSample.Value(1), counter.sample())
        assertEquals(
            ProjectReadEpochVfsBatchObservation.RelevanceUnknown,
            observeProjectReadEpochVfsBatch(root, projected, limits),
        )
    }

    @Test
    fun `moves and renames inspect both endpoints and malformed suffix rejects`() {
        val root = ProjectReadEpochVfsRoot.from(FIXTURE_ROOT)
        val inside = "/workspace/kast/a"
        val outside = "/workspace/other/a"
        for (event in
            listOf(
                ProjectReadEpochVfsEvent.Move(inside, outside),
                ProjectReadEpochVfsEvent.Move(outside, inside),
                ProjectReadEpochVfsEvent.Rename(inside, outside),
                ProjectReadEpochVfsEvent.Rename(outside, inside),
            )) {
            assertEquals(
                ProjectReadEpochVfsBatchObservation.TouchesRoot,
                observeProjectReadEpochVfsBatch(root, listOf(event), limits),
            )
            assertEquals(
                ProjectReadEpochVfsBatchObservation.Rejected(ProjectReadEpochObservationFailure.VfsPathMalformed),
                observeProjectReadEpochVfsBatch(
                    root,
                    listOf(event, ProjectReadEpochVfsEvent.Change("relative")),
                    limits,
                ),
            )
        }
    }

    @Test
    fun `overflow never revives terminal failures and disposal stays closed`() {
        val root = ProjectReadEpochVfsRoot.from(FIXTURE_ROOT)
        for (failure in
            listOf(
                ProjectReadEpochObservationFailure.VfsPathMalformed,
                ProjectReadEpochObservationFailure.VfsBatchLimitExceeded,
                ProjectReadEpochObservationFailure.SignalExhausted,
            )) {
            val counter = ProjectReadEpochMetadataCounter()
            counter.reject(failure)
            RootFilteredProjectEpochVfsListener(root, counter, limits).after(List(3) { event("/workspace/kast/a") })
            assertEquals(ProjectReadEpochSignalSample.Rejected(failure), counter.sample())
        }
        val counter = ProjectReadEpochMetadataCounter()
        val platform = RecordingProjectReadEpochPlatform()
        val source =
            LiveProjectReadEpochSource(
                    platform,
                    ProjectReadEpochMetadataCounter(),
                    counter,
                    RecordingProjectReadEpochExecution(),
                    limits,
                )
                .source
        RootFilteredProjectEpochVfsListener(root, counter, limits).after(List(3) { event("/workspace/kast/a") })
        platform.disposed = true
        assertEquals(
            ProjectReadEpochObservation.Rejected(ProjectReadEpochObservationFailure.ProjectDisposed),
            source.observe(),
        )
    }

    @Test
    fun `concurrent overflow advances do not lose invalidations`() {
        val counter = ProjectReadEpochMetadataCounter()
        val listener = RootFilteredProjectEpochVfsListener(ProjectReadEpochVfsRoot.from(FIXTURE_ROOT), counter, limits)
        val events = List(3) { event("/workspace/other/a") }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks =
                List(4) {
                    executor.submit {
                        start.await()
                        repeat(1_000) { listener.after(events) }
                    }
                }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(ProjectReadEpochSignalSample.Value(4_000), counter.sample())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `overflow at exhausted signal cannot wrap to earlier value`() {
        val counter = ProjectReadEpochMetadataCounter()
        // Fault injection reaches the otherwise impractical terminal boundary without adding a production reset API.
        val field = ProjectReadEpochMetadataCounter::class.java.getDeclaredField("state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val state = field.get(counter) as AtomicReference<ProjectReadEpochSignalSample>
        state.set(ProjectReadEpochSignalSample.Value(Long.MAX_VALUE))
        val listener = RootFilteredProjectEpochVfsListener(ProjectReadEpochVfsRoot.from(FIXTURE_ROOT), counter, limits)
        listener.after(List(3) { event("/workspace/kast/a") })
        assertEquals(
            ProjectReadEpochSignalSample.Rejected(ProjectReadEpochObservationFailure.SignalExhausted),
            counter.sample(),
        )
        listener.after(listOf(event("/workspace/kast/a")))
        assertEquals(
            ProjectReadEpochSignalSample.Rejected(ProjectReadEpochObservationFailure.SignalExhausted),
            counter.sample(),
        )
    }

    private fun event(path: String): VFileEvent =
        object : VFileEvent(null) {
            override fun computePath(): String = path

            override fun getFile(): VirtualFile? = null

            override fun getFileSystem(): VirtualFileSystem = error("No filesystem access permitted")

            override fun isValid(): Boolean = true

            override fun hashCode(): Int = System.identityHashCode(this)

            override fun equals(other: Any?): Boolean = this === other
        }
}
