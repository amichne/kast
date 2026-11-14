package io.github.amichne.kast.distribution.contract

import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HostExitRecordTest {
    @Test
    fun `the record encodes every field, including its schema version, under default-omitting serialization`() {
        assertEquals(
            """{"reason":"IDLE","index":"SMART","hostLifetime":"host-1","schemaVersion":1}""",
            Json.encodeToString(
                HostExitRecord.serializer(),
                HostExitRecord(HostExitReason.IDLE, HostExitIndexState.SMART, "host-1"),
            ),
        )
    }

    @Test
    fun `an unknown reason is rejected`() {
        assertThrows<SerializationException> {
            Json.decodeFromString(
                HostExitRecord.serializer(),
                """{"schemaVersion":1,"reason":"CRASHED","index":"SMART","hostLifetime":"host"}""",
            )
        }
    }

    @Test
    fun `a record without a schema version is rejected`() {
        assertThrows<SerializationException> {
            Json.decodeFromString(
                HostExitRecord.serializer(),
                """{"reason":"IDLE","index":"SMART","hostLifetime":"host"}""",
            )
        }
    }

    @Test
    fun `the record sits directly in the private state directory`() {
        assertEquals(Path.of("/c/state/host-exit.json"), HostExitRecord.path(Path.of("/c/state")))
    }
}
