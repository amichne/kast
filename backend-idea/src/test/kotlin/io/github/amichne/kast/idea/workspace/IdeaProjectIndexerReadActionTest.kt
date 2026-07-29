package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class IdeaProjectIndexerReadActionTest {
    @Test
    fun `source indexing does not wrap the cancellable inventory in a blocking read action`() {
        val sourcePath = listOf(
            Path.of("src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt"),
            Path.of("backend-idea/src/main/kotlin/io/github/amichne/kast/idea/workspace/indexing/IdeaProjectIndexer.kt"),
        ).first(Files::exists)
        val source = Files.readString(sourcePath)
            .substringAfter("fun indexSourceIdentifiers(): Collection<String> {")
            .substringBefore("private fun indexSymbolRelationships")

        assertFalse(source.contains("runIdeaReadAction"))
        assertFalse(source.contains("readGradleWorkspaceModel()"))
        assertTrue(source.contains("inventory.snapshotWithGradleModel"))
    }
}
