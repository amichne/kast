package io.github.amichne.kast.server

import io.github.amichne.kast.api.docs.OpenApiDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText

class DocExampleGeneratorTest {

    @Test
    fun `workspace examples execute linked raw and public continuation flows`() {
        val examples = DocExampleGenerator.generateExamples()
        val metadata = examples.getValue("workspaceFiles")
        val page = examples.getValue("workspaceFilesPage")
        val issue = examples.getValue("workspaceFilesContinuation")
        val consume = examples.getValue("workspaceFilesContinuationConsume")

        val metadataParams = metadata.requestObject().params()
        assertEquals(false, metadataParams.getValue("includeFiles").jsonPrimitive.boolean)
        assertTrue("snapshotToken" !in metadataParams)
        assertTrue("pageToken" !in metadataParams)
        val snapshotToken = metadata.responseObject().result().string("snapshotToken")

        val pageParams = page.requestObject().params()
        assertEquals(true, pageParams.getValue("includeFiles").jsonPrimitive.boolean)
        assertEquals("fake-module", pageParams.string("moduleName"))
        assertEquals(snapshotToken, pageParams.string("snapshotToken"))
        assertTrue("pageToken" !in pageParams)
        assertEquals(snapshotToken, page.responseObject().result().string("snapshotToken"))
        assertTrue(page.responseObject().result().toString().contains(RAW_WORKSPACE_PAGE_HANDLE))

        val issueParams = issue.requestObject().params()
        assertEquals("ISSUE", issueParams.string("action"))
        assertTrue("state" in issueParams)
        assertTrue("pageToken" !in issueParams)
        val publicPageToken = issue.responseObject().result().string("pageToken")

        val consumeParams = consume.requestObject().params()
        assertEquals("CONSUME", consumeParams.string("action"))
        assertTrue("state" !in consumeParams)
        assertEquals(publicPageToken, consumeParams.string("pageToken"))
        val consumedResult = consume.responseObject().result()
        assertEquals("CONSUMED", consumedResult.string("type"))
        assertTrue("state" in consumedResult)
        assertTrue("pageToken" !in consumedResult)
    }

    @Test
    fun `random workspace handles are deterministic valid version four UUID examples`() {
        val first = DocExampleGenerator.generateExamples()
        val second = DocExampleGenerator.generateExamples()

        assertEquals(first, second, "Generated examples must not retain runtime-random continuation handles")

        val expectedHandles = setOf(
            "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002",
            "00000000-0000-4000-8000-000000000003",
        )
        val generatedHandles = listOf(
            "workspaceFiles",
            "workspaceFilesPage",
            "workspaceFilesContinuation",
            "workspaceFilesContinuationConsume",
        )
            .mapNotNull(first::get)
            .flatMap { pair ->
                UUID_PATTERN.findAll(pair.request + pair.response).map(MatchResult::value).toList()
            }
            .toSet()

        assertEquals(expectedHandles, generatedHandles)
        generatedHandles.map(UUID::fromString).forEach { handle ->
            assertEquals(4, handle.version())
            assertEquals(2, handle.variant())
        }
    }

    @Test
    fun `checked in doc examples match generated examples`() {
        val generated = DocExampleGenerator.generateExamples()
        val docsExamples = repoRoot().resolve("cli-rs/protocol/examples")

        generated.forEach { (operationId, pair) ->
            val expectedRequest = docsExamples.resolve("$operationId-request.json").readText().trimEnd()
            val expectedResponse = docsExamples.resolve("$operationId-response.json").readText().trimEnd()
            assertEquals(expectedRequest, pair.request.trimEnd(), "Request drift for $operationId")
            assertEquals(expectedResponse, pair.response.trimEnd(), "Response drift for $operationId")
        }
    }

    @Test
    fun `every OpenAPI operation has a corresponding example fixture`() {
        val yaml = OpenApiDocument.renderYaml()
        val operationIdRegex = Regex("""operationId:\s*(\w+)""")
        val operationIds = operationIdRegex.findAll(yaml).map { it.groupValues[1] }.toSet()

        val docsExamples = repoRoot().resolve("cli-rs/protocol/examples")
        operationIds.forEach { id ->
            assertTrue(Files.exists(docsExamples.resolve("$id-request.json")), "Missing request for $id")
            assertTrue(Files.exists(docsExamples.resolve("$id-response.json")), "Missing response for $id")
        }
    }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("docs")) }

    private companion object {
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        const val RAW_WORKSPACE_PAGE_HANDLE = "00000000-0000-4000-8000-000000000001"
    }

    private fun DocExampleGenerator.ExamplePair.requestObject(): JsonObject =
        Json.parseToJsonElement(request).jsonObject

    private fun DocExampleGenerator.ExamplePair.responseObject(): JsonObject =
        Json.parseToJsonElement(response).jsonObject

    private fun JsonObject.params(): JsonObject = getValue("params").jsonObject

    private fun JsonObject.result(): JsonObject = getValue("result").jsonObject

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
}
