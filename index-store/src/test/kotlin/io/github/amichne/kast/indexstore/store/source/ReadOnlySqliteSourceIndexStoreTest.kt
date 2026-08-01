package io.github.amichne.kast.indexstore.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReadOnlySqliteSourceIndexStoreTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `read only store reads committed facts and rejects persistence`() {
        val sourcePath = workspaceRoot.resolve("src/Caller.kt").toString()
        val generation = SqliteSourceIndexStore(workspaceRoot).use { writer ->
            writer.ensureSchema()
            writer.upsertSymbolReference(
                sourcePath = sourcePath,
                sourceOffset = 1,
                targetFqName = "demo.Target",
                targetPath = workspaceRoot.resolve("src/Target.kt").toString(),
                targetOffset = 1,
            )
            writer.readGeneration()
        }

        SqliteSourceIndexStore(workspaceRoot, SqliteSourceIndexStoreAccess.READ_ONLY).use { reader ->
            assertEquals(generation, reader.readGeneration())
            assertEquals(1, reader.referencesFromFile(sourcePath).size)
            assertThrows(Exception::class.java) {
                reader.clearReferencesFromFile(sourcePath)
            }
        }

        SqliteSourceIndexStore(workspaceRoot).use { verifier ->
            assertEquals(generation, verifier.readGeneration())
            assertEquals(1, verifier.referencesFromFile(sourcePath).size)
        }
    }
}
