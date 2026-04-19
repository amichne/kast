package io.github.amichne.kast.server

import io.github.amichne.kast.api.AnalysisOpenApiDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class DocExampleGeneratorTest {

    @Test
    fun `checked in doc examples match generated examples`() {
        val generated = DocExampleGenerator.generateExamples()
        val docsExamples = repoRoot().resolve("docs/examples")

        generated.forEach { (operationId, pair) ->
            val expectedRequest = docsExamples.resolve("$operationId-request.json").readText().trimEnd()
            val expectedResponse = docsExamples.resolve("$operationId-response.json").readText().trimEnd()
            assertEquals(expectedRequest, pair.request.trimEnd(), "Request drift for $operationId")
            assertEquals(expectedResponse, pair.response.trimEnd(), "Response drift for $operationId")
        }
    }

    @Test
    fun `every OpenAPI operation has a corresponding example fixture`() {
        val yaml = AnalysisOpenApiDocument.renderYaml()
        val operationIdRegex = Regex("""operationId:\s*(\w+)""")
        val operationIds = operationIdRegex.findAll(yaml).map { it.groupValues[1] }.toSet()

        val docsExamples = repoRoot().resolve("docs/examples")
        operationIds.forEach { id ->
            assertTrue(Files.exists(docsExamples.resolve("$id-request.json")), "Missing request for $id")
            assertTrue(Files.exists(docsExamples.resolve("$id-response.json")), "Missing response for $id")
        }
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("docs")) }
}
