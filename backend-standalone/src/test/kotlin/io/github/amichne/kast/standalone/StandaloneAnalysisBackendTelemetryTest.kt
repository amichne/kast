package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.ServerLimits
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
