package io.github.amichne.kast.appserver.ide

import java.nio.file.Path
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExistingIdeStageObservationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `stage recording preserves typed rejections and records no peer payload`() {
        Recording().use { recording ->
            recording.enable("io.github.amichne.kast.ExistingIdeStage").withoutStackTrace()
            recording.start()
            val rejection = ExistingIdeExchange.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED)
            assertSame(rejection, observeExistingIdeStage(ExistingIdeStage.DESCRIPTOR_ADMISSION) { rejection })
            recording.stop()
            val file = temporary.resolve("returned.jfr")
            recording.dump(file)
            val event =
                RecordingFile.readAllEvents(file).single {
                    it.eventType.name == "io.github.amichne.kast.ExistingIdeStage"
                }
            assertEquals("DESCRIPTOR_ADMISSION", event.getString("stage"))
            assertEquals("RETURNED", event.getString("outcome"))
            assertEquals(
                listOf("startTime", "duration", "eventThread", "stackTrace", "stage", "outcome"),
                event.eventType.fields.map { it.name },
            )
        }
    }

    @Test
    fun `throwing stage preserves the original exception and records a finite failure`() {
        Recording().use { recording ->
            recording.enable("io.github.amichne.kast.ExistingIdeStage").withoutStackTrace()
            recording.start()
            val failure = IllegalStateException("case-owned failure")
            assertSame(
                failure,
                assertThrows(IllegalStateException::class.java) {
                    observeExistingIdeStage(ExistingIdeStage.STATUS_EXCHANGE) { throw failure }
                },
            )
            recording.stop()
            val file = temporary.resolve("threw.jfr")
            recording.dump(file)
            val event =
                RecordingFile.readAllEvents(file).single {
                    it.eventType.name == "io.github.amichne.kast.ExistingIdeStage"
                }
            assertEquals("STATUS_EXCHANGE", event.getString("stage"))
            assertEquals("THREW", event.getString("outcome"))
        }
    }
}
