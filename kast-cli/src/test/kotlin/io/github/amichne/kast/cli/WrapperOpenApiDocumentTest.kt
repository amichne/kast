package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class WrapperOpenApiDocumentTest {
    @Test
    fun `checked in wrapper openapi matches generated document`() {
        val expected = repoRoot()
            .resolve(".agents/skills/kast/fixtures/maintenance/references/wrapper-openapi.yaml")
            .readText()
        val generated = WrapperOpenApiDocument.renderYaml()

        assertEquals(expected.trimEnd(), generated.trimEnd())
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { current -> current.parent }
            .first { candidate -> Files.isDirectory(candidate.resolve(".agents")) }
}
