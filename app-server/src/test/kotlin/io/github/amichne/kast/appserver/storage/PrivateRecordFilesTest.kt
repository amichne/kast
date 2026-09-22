package io.github.amichne.kast.appserver.storage

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PrivateRecordFilesTest {
    @Test
    fun `oversized replacement rejects before changing the durable record`(@TempDir temporary: Path) {
        val files =
            (PrivateRecordFiles.open(temporary.toRealPath().resolve("store")) { it } as Refinement.Refined).value
        val record = files.root.resolve("record.json")
        assertEquals(
            Refinement.Refined(Unit),
            files.locked {
                files.write(record, ValueDocument.serializer(), ValueDocument("first"), maximumBytes = 32)
                Refinement.Refined(Unit)
            },
        )
        val before = Files.readAllBytes(record)
        assertEquals(
            Refinement.Rejected(PrivateRecordFailure.STORE_REJECTED),
            files.locked {
                files.write(
                    record,
                    ValueDocument.serializer(),
                    ValueDocument("replacement".repeat(10)),
                    maximumBytes = 32,
                )
                Refinement.Refined(Unit)
            },
        )
        assertTrue(before.contentEquals(Files.readAllBytes(record)))
    }

    @Serializable private data class ValueDocument(val value: String)
}
