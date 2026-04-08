package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.ServerLimits
import io.github.amichne.kast.api.SymbolQuery
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

    private suspend fun withBackend(
        telemetry: StandaloneTelemetry,
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
                    maxResults = 100,
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
