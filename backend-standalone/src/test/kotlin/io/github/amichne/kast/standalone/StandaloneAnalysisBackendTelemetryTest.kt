package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.CallDirection
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.FilePosition
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.ServerLimits
import kotlinx.coroutines.test.runTest
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
        assertTrue(exportedSpans.contains("\"name\":\"kast.rename\""))
        assertTrue(exportedSpans.contains("\"name\":\"kast.callHierarchy\""))
        assertTrue(exportedSpans.contains("candidate-files"))
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
        try {
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
        } finally {
            session.close()
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
