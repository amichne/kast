package io.github.amichne.kast.indexstore.store

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SqliteSourceIndexWriterLockTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `one canonical workspace owns the writer for the store lifetime`() {
        val workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"))
        val workspaceAlias = tempDir.resolve("workspace-alias")
        Files.createSymbolicLink(workspaceAlias, workspaceRoot)

        val firstWriter = SqliteSourceIndexStore(workspaceRoot)
        try {
            firstWriter.ensureSchema()

            assertThrows(IllegalStateException::class.java) {
                SqliteSourceIndexStore(workspaceAlias).use { secondWriter ->
                    secondWriter.ensureSchema()
                }
            }
            assertDoesNotThrow {
                SqliteSourceIndexStore(workspaceAlias, SqliteSourceIndexStoreAccess.READ_ONLY).use { reader ->
                    reader.readGeneration()
                }
            }
        } finally {
            firstWriter.close()
        }

        assertDoesNotThrow {
            SqliteSourceIndexStore(workspaceAlias).use { replacement ->
                replacement.ensureSchema()
            }
        }
    }
}
