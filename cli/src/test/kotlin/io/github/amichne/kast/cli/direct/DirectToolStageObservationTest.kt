package io.github.amichne.kast.cli.direct

import java.nio.file.Path
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DirectToolStageObservationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `stage recording preserves returned values and records finite witnesses`() {
        Recording().use { recording ->
            recording.enable("io.github.amichne.kast.DirectToolStage").withoutStackTrace()
            recording.start()
            val returned = observeDirectToolStage(DirectToolStage.REQUEST_ADMISSION) { InstalledToolAdmission.STOPPED }
            assertEquals(InstalledToolAdmission.STOPPED, returned)
            recording.stop()
            val file = temporary.resolve("returned.jfr")
            recording.dump(file)
            val events =
                RecordingFile.readAllEvents(file).filter {
                    it.eventType.name == "io.github.amichne.kast.DirectToolStage"
                }
            assertEquals(1, events.size)
            assertEquals("REQUEST_ADMISSION", events.single().getString("stage"))
            assertEquals("RETURNED", events.single().getString("outcome"))
            assertEquals(
                listOf("startTime", "duration", "eventThread", "stackTrace", "stage", "outcome"),
                events.single().eventType.fields.map { it.name },
            )
        }
    }

    @Test
    fun `throwing stage records failure and rethrows the original exception`() {
        Recording().use { recording ->
            recording.enable("io.github.amichne.kast.DirectToolStage").withoutStackTrace()
            recording.start()
            val failure = IllegalStateException("case-owned failure")
            val caught =
                assertThrows(IllegalStateException::class.java) {
                    observeDirectToolStage(DirectToolStage.INVOCATION) { throw failure }
                }
            assertSame(failure, caught)
            recording.stop()
            val file = temporary.resolve("threw.jfr")
            recording.dump(file)
            val event =
                RecordingFile.readAllEvents(file).single {
                    it.eventType.name == "io.github.amichne.kast.DirectToolStage"
                }
            assertEquals("INVOCATION", event.getString("stage"))
            assertEquals("THREW", event.getString("outcome"))
        }
    }
}
