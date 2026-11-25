package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.protocol.contract.HostIndexProgress
import io.github.amichne.kast.protocol.contract.HostModelActivity
import io.github.amichne.kast.protocol.contract.HostPreparationProgress
import io.github.amichne.kast.protocol.contract.HostProgressRecord
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import io.github.amichne.kast.protocol.contract.WorkspacePreparationBudget
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadline
import io.github.amichne.kast.protocol.contract.WorkspacePreparationDeadlineOwner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostProgressRecordsTest {
    @TempDir lateinit var directory: Path

    private val outcomes = mutableListOf<HostProgressRecordOutcome>()
    private var clock = 100L

    private fun records(state: Path = directory) = HostProgressRecords(state, "host-7", { clock++ }, outcomes::add)

    private val target
        get() = directory.resolve("host-progress.json")

    private fun recorded() = Json.decodeFromString(HostProgressRecord.serializer(), Files.readString(target))

    private fun activity(
        open: Boolean = true,
        resolving: Boolean = false,
        scanning: Boolean = false,
        dumb: Boolean = false,
        queuedFiles: Int = 0,
    ) = HostNativeActivity(open, resolving, scanning, dumb, queuedFiles)

    private fun prepared(
        outcome: NativePreparationOutcome,
        stage: IdeLifecycleStage,
        failure: IdeLifecycleFailure? = null,
    ) =
        NativePreparationObservation(
            "request",
            outcome,
            WorkspacePreparationDeadline.start(WorkspacePreparationBudget.limit, 0)
                .timing(stage, WorkspacePreparationDeadlineOwner.NATIVE_PREPARATION, 0),
            failure,
        )

    @Test
    fun `a Host without a project records that no preparation was requested`() {
        records().record(activity(open = false))
        assertEquals(
            HostProgressRecord(
                "host-7",
                HostPreparationProgress.None,
                HostModelActivity.IDLE,
                HostIndexProgress.NoProject,
                100,
            ),
            recorded(),
        )
        assertEquals(listOf(target), directory.listDirectoryEntries())
    }

    @Test
    fun `unchanged facts write nothing`() {
        val records = records()
        records.record(activity())
        Files.delete(target)
        records.record(activity())
        assertFalse(Files.exists(target))
        records.record(activity(resolving = true))
        assertEquals(HostModelActivity.RESOLVING, recorded().model)
    }

    @Test
    fun `indexing keeps the queue that the scan filled`() {
        val records = records()
        records.record(activity(scanning = true, queuedFiles = 40))
        records.record(activity(scanning = true, queuedFiles = 106_835))
        assertEquals(HostIndexProgress.Scanning(106_835), recorded().index)
        // The indexer empties the queue when it starts, so the estimate falls while the work remains.
        records.record(activity(dumb = true, queuedFiles = 0))
        assertEquals(HostIndexProgress.Indexing(106_835), recorded().index)
        records.record(activity())
        assertEquals(HostIndexProgress.Smart, recorded().index)
        records.record(activity(dumb = true))
        assertEquals(HostIndexProgress.Indexing(0), recorded().index)
    }

    @Test
    fun `a preparation keeps its start across stages and ends with its exact failure`() {
        val records = records()
        records.prepared(prepared(NativePreparationOutcome.STARTED, IdeLifecycleStage.OPENING))
        records.prepared(prepared(NativePreparationOutcome.STAGE_CHANGED, IdeLifecycleStage.IMPORTING))
        records.record(activity())
        assertEquals(HostPreparationProgress.Running(IdeLifecycleStage.IMPORTING, 100), recorded().preparation)
        records.prepared(
            prepared(
                NativePreparationOutcome.BLOCKED,
                IdeLifecycleStage.IMPORTING,
                IdeLifecycleFailure.IMPORT_FAILED,
            )
        )
        records.record(activity())
        assertEquals(
            HostPreparationProgress.Blocked(IdeLifecycleStage.IMPORTING, IdeLifecycleFailure.IMPORT_FAILED),
            recorded().preparation,
        )
        records.prepared(prepared(NativePreparationOutcome.COMPLETE, IdeLifecycleStage.ADMISSION))
        records.record(activity())
        assertEquals(HostPreparationProgress.Ready, recorded().preparation)
    }

    @Test
    fun `a failing write is reported once and tried again`() {
        val state = directory.resolve("state")
        val records = records(state)
        records.record(activity())
        records.record(activity())
        assertEquals(listOf(HostProgressRecordOutcome.FAILED), outcomes)
        Files.createDirectory(state)
        records.record(activity())
        assertEquals(listOf(HostProgressRecordOutcome.FAILED, HostProgressRecordOutcome.WRITTEN), outcomes)
    }
}
