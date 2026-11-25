package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.protocol.contract.HostIndexProgress
import io.github.amichne.kast.protocol.contract.HostModelActivity
import io.github.amichne.kast.protocol.contract.HostPreparationProgress
import io.github.amichne.kast.protocol.contract.HostProgressRecord
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.IdeLifecycleStage
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceProgressStatusTest {
    @TempDir lateinit var temporary: Path

    private val state
        get() = temporary.toRealPath()

    private val live =
        Observation.verified(
            LoadedApplicationHost(
                ApplicationRuntimeIdentity("/ide", "/profile", 42, 100, "host", ApplicationHostBuild("1.2.3", "", "")),
                ApplicationHostCompatibility("", "", "1.2.3", ApplicationHostedContract("", "", "", "", emptyList())),
            )
        )

    private fun record(
        preparation: HostPreparationProgress = HostPreparationProgress.Ready,
        model: HostModelActivity = HostModelActivity.IDLE,
        index: HostIndexProgress = HostIndexProgress.Smart,
        hostLifetime: String = "host",
    ) = HostProgressRecord(hostLifetime, 42, preparation, model, index, 7)

    private fun write(text: String) {
        Files.writeString(state.resolve(HostProgressRecord.FILE_NAME), text)
    }

    private fun line(record: HostProgressRecord): String? = workspaceProgressLine(Observation.verified(record))

    @Test
    fun `the live Host's record is admitted`() {
        write(containerJson.encodeToString(record()))
        assertEquals(Observation.verified(record()), workspaceProgress(state, live))
    }

    @Test
    fun `an earlier Host's record is never current progress`() {
        write(containerJson.encodeToString(record(hostLifetime = "earlier")))
        val progress = workspaceProgress(state, live)
        assertEquals(WorkspaceProgressFailure.EARLIER_HOST.name, progress.reason)
        assertEquals("Workspace progress: none recorded since this Host started", workspaceProgressLine(progress))
    }

    @Test
    fun `absent and unreadable records stay distinct`() {
        assertEquals(WorkspaceProgressFailure.NOT_RECORDED.name, workspaceProgress(state, live).reason)
        write("not a record")
        assertEquals(WorkspaceProgressFailure.INVALID_RECORD.name, workspaceProgress(state, live).reason)
        write(containerJson.encodeToString(record().copy(schemaVersion = 2)))
        assertEquals(WorkspaceProgressFailure.INVALID_RECORD.name, workspaceProgress(state, live).reason)
    }

    @Test
    fun `without a live Host the record is not read and no line is added`() {
        write(containerJson.encodeToString(record()))
        val progress = workspaceProgress(state, Observation.unavailable("DESCRIPTOR_ABSENT"))
        assertEquals(WorkspaceProgressFailure.HOST_NOT_OBSERVED.name, progress.reason)
        assertNull(workspaceProgressLine(progress))
    }

    @Test
    fun `each phase reads as a step with its overlapping work`() {
        val importing = HostPreparationProgress.Running(IdeLifecycleStage.IMPORTING, 1)
        val indexing = HostIndexProgress.Indexing(106_835)
        val expected =
            mapOf(
                record(HostPreparationProgress.Running(IdeLifecycleStage.OPENING, 1)) to
                    "step 1 of 5, opening the project",
                record(importing, HostModelActivity.RESOLVING, indexing) to
                    "step 2 of 5, importing the Gradle model; also indexing (about 106,835 files)",
                record(importing, index = HostIndexProgress.Scanning(0)) to "step 3 of 5, scanning files",
                record(importing, index = indexing) to "step 4 of 5, indexing (about 106,835 files)",
                record(HostPreparationProgress.Running(IdeLifecycleStage.ADMISSION, 1)) to
                    "step 5 of 5, checking readiness",
                record() to "prepared; no import or index work",
                record(HostPreparationProgress.None) to "Host started; no preparation requested yet",
                record(
                    HostPreparationProgress.Blocked(IdeLifecycleStage.IMPORTING, IdeLifecycleFailure.IMPORT_FAILED)
                ) to "failed while importing the Gradle model (import failed)",
            )
        expected.forEach { (facts, text) -> assertEquals("Workspace progress: $text", line(facts)) }
    }
}
