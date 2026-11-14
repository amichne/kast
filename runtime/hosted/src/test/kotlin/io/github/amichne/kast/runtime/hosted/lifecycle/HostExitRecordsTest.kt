package io.github.amichne.kast.runtime.hosted.lifecycle

import io.github.amichne.kast.distribution.contract.HostExitIndexState
import io.github.amichne.kast.distribution.contract.HostExitReason
import io.github.amichne.kast.distribution.contract.HostExitRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostExitRecordsTest {
    @TempDir lateinit var directory: Path

    private val observations = mutableListOf<HostExitRecordObservation>()

    private fun records(state: Path = directory, indexing: Boolean = false) =
        HostExitRecords(state, "host-7", { indexing }) { observations += it }

    private fun recorded(): HostExitRecord =
        Json.decodeFromString(HostExitRecord.serializer(), Files.readString(directory.resolve("host-exit.json")))

    @Test
    fun `a record replaces any earlier one and leaves no temporary file`() {
        Files.writeString(directory.resolve("host-exit.json"), "stale")
        records().record(HostExitReason.IDLE)
        assertEquals(HostExitRecord(HostExitReason.IDLE, HostExitIndexState.SMART, "host-7"), recorded())
        assertEquals(listOf(directory.resolve("host-exit.json")), directory.listDirectoryEntries())
        assertEquals(
            listOf(
                HostExitRecordObservation(
                    HostExitRecordAction.RECORD,
                    HostExitRecordOutcome.WRITTEN,
                    HostExitReason.IDLE,
                )
            ),
            observations,
        )
    }

    @Test
    fun `a stop recorded while a project is indexing says so`() {
        records(indexing = true).record(HostExitReason.SETTLED)
        assertEquals(HostExitRecord(HostExitReason.SETTLED, HostExitIndexState.DUMB, "host-7"), recorded())
    }

    @Test
    fun `retraction removes the record and reports an already absent one`() {
        val records = records()
        records.record(HostExitReason.SETTLED)
        records.retract()
        records.retract()
        assertFalse(Files.exists(directory.resolve("host-exit.json")))
        assertEquals(
            listOf(HostExitRecordOutcome.WRITTEN, HostExitRecordOutcome.REMOVED, HostExitRecordOutcome.ABSENT),
            observations.map { it.outcome },
        )
    }

    @Test
    fun `a write into missing state fails without leaving a record`() {
        val missing = directory.resolve("missing")
        records(missing).record(HostExitReason.IDLE)
        assertFalse(Files.exists(missing))
        assertEquals(
            listOf(
                HostExitRecordObservation(
                    HostExitRecordAction.RECORD,
                    HostExitRecordOutcome.FAILED,
                    HostExitReason.IDLE,
                )
            ),
            observations,
        )
    }
}
