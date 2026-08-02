package io.github.amichne.kast.idea.backend.semantic

import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileStageVersion
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes

class SemanticGraphContentHashTest {
    @TempDir
    lateinit var workspace: Path

    @Test
    fun `semantic graph uses the persisted byte content representation and a new stage version`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "package demo\r\nclass App\r\n".encodeToByteArray()
        val source = workspace.resolve("App.kt").also { path -> path.writeBytes(bytes) }

        assertEquals(
            FileContentHash.parse(FileHashing.sha256(bytes)),
            semanticGraphContentHash(requireNotNull(SourceIndexFilePolicy.forWorkspace(workspace).sourcePath(source))),
        )
        assertEquals(FileStageVersion.parse("semantic-graph-2"), FileStageVersions.CURRENT.semanticGraph)
    }
}
