package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HostProgressRecordTest {
    private fun record(
        preparation: HostPreparationProgress,
        model: HostModelActivity = HostModelActivity.IDLE,
        index: HostIndexProgress = HostIndexProgress.Smart,
    ) = HostProgressRecord("host", 42, preparation, model, index, 7)

    private fun running(stage: IdeLifecycleStage) = HostPreparationProgress.Running(stage, 1)

    @Test
    fun `the record encodes every discriminator and its schema version`() {
        assertEquals(
            """{"hostLifetime":"host","hostPid":42,""" +
                """"preparation":{"type":"running","stage":"IMPORTING","startedAtEpochMillis":1},""" +
                """"model":"IDLE","index":{"type":"indexing","queuedFiles":12},""" +
                """"changedAtEpochMillis":7,"phase":"INDEXING","schemaVersion":1}""",
            Json.encodeToString(
                HostProgressRecord.serializer(),
                record(running(IdeLifecycleStage.IMPORTING), index = HostIndexProgress.Indexing(12)),
            ),
        )
    }

    @Test
    fun `a record without a schema version is rejected`() {
        assertThrows<SerializationException> {
            Json.decodeFromString(
                HostProgressRecord.serializer(),
                """{"hostLifetime":"host","hostPid":42,"preparation":{"type":"ready"},"model":"IDLE",""" +
                    """"index":{"type":"smart"},"changedAtEpochMillis":7,"phase":"READY"}""",
            )
        }
    }

    @Test
    fun `a phase that contradicts its facts is rejected`() {
        assertThrows<IllegalArgumentException> {
            record(HostPreparationProgress.Ready).copy(phase = HostPhase.INDEXING)
        }
    }

    @Test
    fun `a phase names the earliest unfinished work`() {
        val importing = running(IdeLifecycleStage.IMPORTING)
        val scanning = HostIndexProgress.Scanning(3)
        val indexing = HostIndexProgress.Indexing(3)
        val expected =
            mapOf(
                record(HostPreparationProgress.None, index = HostIndexProgress.NoProject) to HostPhase.STARTING,
                record(running(IdeLifecycleStage.OPENING), index = indexing) to HostPhase.OPENING,
                record(importing, HostModelActivity.RESOLVING, indexing) to HostPhase.IMPORTING,
                record(importing) to HostPhase.IMPORTING,
                record(importing, index = scanning) to HostPhase.SCANNING,
                record(importing, index = indexing) to HostPhase.INDEXING,
                record(running(IdeLifecycleStage.ADMISSION)) to HostPhase.ADMITTING,
                record(HostPreparationProgress.Ready) to HostPhase.READY,
                record(HostPreparationProgress.Ready, index = indexing) to HostPhase.INDEXING,
                record(
                    HostPreparationProgress.Blocked(IdeLifecycleStage.IMPORTING, IdeLifecycleFailure.IMPORT_FAILED),
                    index = indexing,
                ) to HostPhase.BLOCKED,
            )
        expected.forEach { (facts, phase) -> assertEquals(phase, facts.phase, facts.toString()) }
    }
}
