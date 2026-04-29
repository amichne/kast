package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.CallDirection
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryConfig
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryDetail
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StandaloneAnalysisBackendTelemetryTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `otel telemetry exports rename and call hierarchy spans to the configured file`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"
                fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        val telemetryFile = workspaceRoot.resolve("build/telemetry/spans.jsonl")
        val telemetry = StandaloneTelemetry.create(
            StandaloneTelemetryConfig(
                enabled = true,
                scopes = setOf(
                    StandaloneTelemetryScope.RENAME,
                    StandaloneTelemetryScope.CALL_HIERARCHY,
                ),
                detail = StandaloneTelemetryDetail.VERBOSE,
                outputFile = telemetryFile,
            ),
        )
        val queryOffset = Files.readString(file).indexOf("greet()")

        withBackend(telemetry) { backend ->
            backend.rename(
                RenameQuery(
                    position = FilePosition(filePath = file.toString(), offset = queryOffset),
                    newName = "welcome",
                ),
            )
            backend.callHierarchy(
                CallHierarchyQuery(
                    position = FilePosition(filePath = file.toString(), offset = queryOffset),
                    direction = CallDirection.INCOMING,
                    depth = 1,
                ),
            )
        }

        val exportedSpans = telemetryFile.readText()
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line -> Json.parseToJsonElement(line).jsonObject }
            .toList()

        assertTrue(exportedSpans.any { span -> span["name"]?.toString() == "\"kast.rename\"" })
        assertTrue(exportedSpans.any { span -> span["name"]?.toString() == "\"kast.callHierarchy\"" })
        val hasCandidateFilesEvent = exportedSpans.any { span ->
            val events = span["events"]?.jsonArray ?: return@any false
            events.any { event -> event.jsonObject["name"]?.toString() == "\"candidate-files\"" }
        }
        assertTrue(hasCandidateFilesEvent)

        // Verify timing fields are present and sensible
        exportedSpans.forEach { span ->
            val durationNanos = span["durationNanos"]?.toString()?.toLongOrNull()
            val startNanos = span["startEpochNanos"]?.toString()?.toLongOrNull()
            val endNanos = span["endEpochNanos"]?.toString()?.toLongOrNull()
            assertTrue(durationNanos != null && durationNanos > 0, "Expected durationNanos > 0 in span ${span["name"]}")
            assertTrue(startNanos != null && endNanos != null && startNanos < endNanos, "Expected startEpochNanos < endEpochNanos in span ${span["name"]}")
        }
    }

    @Test
    fun `resolveSymbol emits SYMBOL_RESOLVE span when scope is enabled`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"
            """.trimIndent() + "\n",
        )
        val telemetryFile = workspaceRoot.resolve("build/telemetry/resolve-spans.jsonl")
        val telemetry = StandaloneTelemetry.create(
            StandaloneTelemetryConfig(
                enabled = true,
                scopes = setOf(StandaloneTelemetryScope.SYMBOL_RESOLVE),
                detail = StandaloneTelemetryDetail.VERBOSE,
                outputFile = telemetryFile,
            ),
        )
        val queryOffset = Files.readString(file).indexOf("greet()")

        withBackend(telemetry) { backend ->
            backend.resolveSymbol(
                SymbolQuery(
                    position = FilePosition(filePath = file.toString(), offset = queryOffset),
                ),
            )
        }

        val exportedSpans = telemetryFile.readText()
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line -> Json.parseToJsonElement(line).jsonObject }
            .toList()

        assertTrue(
            exportedSpans.any { span -> span["name"]?.toString() == "\"kast.resolveSymbol\"" },
            "Expected a kast.resolveSymbol span but found: ${exportedSpans.map { it["name"] }}",
        )
    }

    @Test
    fun `findReferences emits REFERENCES span with candidate file count`() = runTest {
        val file = writeFile(
            relativePath = "src/main/kotlin/sample/Greeter.kt",
            content = """
                package sample

                fun greet(): String = "hi"
                fun use(): String = greet()
            """.trimIndent() + "\n",
        )
        val telemetryFile = workspaceRoot.resolve("build/telemetry/refs-spans.jsonl")
        val telemetry = StandaloneTelemetry.create(
            StandaloneTelemetryConfig(
                enabled = true,
                scopes = setOf(StandaloneTelemetryScope.REFERENCES),
                detail = StandaloneTelemetryDetail.VERBOSE,
                outputFile = telemetryFile,
            ),
        )
        val queryOffset = Files.readString(file).indexOf("greet()")

        withBackend(telemetry) { backend ->
            backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = file.toString(), offset = queryOffset),
                    includeDeclaration = false,
                ),
            )
        }

        val exportedSpans = telemetryFile.readText()
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line -> Json.parseToJsonElement(line).jsonObject }
            .toList()

        val referencesSpan = exportedSpans.find { span -> span["name"]?.toString() == "\"kast.findReferences\"" }
        assertTrue(
            referencesSpan != null,
            "Expected a kast.findReferences span but found: ${exportedSpans.map { it["name"] }}",
        )
        val attributes = referencesSpan!!["attributes"]?.jsonObject
        assertTrue(
            attributes?.containsKey("kast.references.candidateFileCount") == true,
            "Expected candidateFileCount attribute in span attributes: $attributes",
        )
    }

    @Test
    fun `workspaceFiles emits WORKSPACE_FILES span with scope and truncation counts`() = runTest {
        writeFile("src/main/kotlin/sample/A.kt", "package sample\n")
        writeFile("src/main/kotlin/sample/B.kt", "package sample\n")
        val telemetryFile = workspaceRoot.resolve("build/telemetry/workspace-files-spans.jsonl")
        val telemetry = StandaloneTelemetry.create(
            StandaloneTelemetryConfig(
                enabled = true,
                scopes = setOf(StandaloneTelemetryScope.WORKSPACE_FILES),
                detail = StandaloneTelemetryDetail.BASIC,
                outputFile = telemetryFile,
            ),
        )

        withBackend(telemetry, maxResults = 1) { backend ->
            backend.workspaceFiles(WorkspaceFilesQuery(includeFiles = true))
        }

        val exportedSpans = telemetryFile.readText()
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line -> Json.parseToJsonElement(line).jsonObject }
            .toList()

        val workspaceFilesSpan = exportedSpans.find { span -> span["name"]?.toString() == "\"kast.workspaceFiles\"" }
        assertTrue(
            workspaceFilesSpan != null,
            "Expected a kast.workspaceFiles span but found: ${exportedSpans.map { it["name"] }}",
        )
        val attributes = workspaceFilesSpan!!["attributes"]?.jsonObject
        assertTrue(
            attributes?.containsKey("kast.workspaceFiles.truncatedModuleCount") == true,
            "Expected truncatedModuleCount attribute in span attributes: $attributes",
        )
    }

    private suspend fun withBackend(
        telemetry: StandaloneTelemetry,
        maxResults: Int = 100,
        block: suspend (StandaloneAnalysisBackend) -> Unit,
    ) {
        val session = StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        )
        session.use { session ->
            val backend = StandaloneAnalysisBackend(
                workspaceRoot = workspaceRoot,
                limits = ServerLimits(
                    maxResults = maxResults,
                    requestTimeoutMillis = 30_000,
                    maxConcurrentRequests = 4,
                ),
                session = session,
                telemetry = telemetry,
            )
            block(backend)
        }
    }

    private fun writeFile(
        relativePath: String,
        content: String,
    ): Path {
        val path = workspaceRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        path.writeText(content)
        return path
    }
}
