package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetry
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryConfig
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryDetail
import io.github.amichne.kast.standalone.telemetry.StandaloneTelemetryScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis

/**
 * Verifies that [StandaloneAnalysisBackend.findReferences] enforces a per-file PSI-walk
 * budget ([ServerLimits.perFileScanBudgetMillis]).
 *
 * The strategy:
 *  - "LargeCandidate.kt" has 300 padding functions followed by the actual call to `target()`.
 *    With a 1 ms budget and a check every 100 PSI elements, the walk will hit the deadline
 *    before reaching the reference and the file will be skipped.
 *  - "SmallCandidate.kt" is a trivial 3-line file that completes well within 1 ms.
 *
 * All tests in this class are tagged [Tag("concurrency")] and can be run with:
 *   ./gradlew :backend-standalone:test -PincludeTags=concurrency
 */
@Tag("concurrency")
class PerFileReferenceScanBudgetTest {

    @TempDir
    lateinit var workspaceRoot: Path

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns source for a Kotlin file with 300 padding functions (lots of PSI nodes)
     * followed by a single call to `target()`.
     *
     * The call is placed **after** 300 declarations so that a walk checking every
     * 100 elements will exhaust a 1 ms budget long before it can find the reference.
     */
    private fun largeCandidateFileContent(callerName: String = "usesTarget"): String = buildString {
        appendLine("package sample")
        appendLine()
        // 300 padding functions – each contributes many PSI nodes (name, params, body, etc.)
        for (i in 0..299) {
            appendLine("fun padding$i(a: Int, b: Int, c: Int): Int = a + b + c")
        }
        appendLine()
        // The actual reference to target() is at the very end so the budget
        // check fires before we reach it.
        appendLine("fun $callerName(): String = target()")
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

    private fun backendWithBudget(
        session: StandaloneAnalysisSession,
        budgetMillis: Long,
        telemetry: StandaloneTelemetry = StandaloneTelemetry.fromConfig(workspaceRoot),
    ): StandaloneAnalysisBackend = StandaloneAnalysisBackend(
        workspaceRoot = workspaceRoot,
        limits = ServerLimits(
            maxResults = 100,
            requestTimeoutMillis = 30_000,
            maxConcurrentRequests = 4,
            perFileScanBudgetMillis = budgetMillis,
        ),
        session = session,
        telemetry = telemetry,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A candidate file whose PSI walk exceeds `perFileScanBudgetMillis` must be skipped
     * (contributes 0 references to the result).
     *
     * FAILS on current code (no budget enforcement): the large file IS walked in full
     * and its reference IS found, so the assertion `assertEquals(0, largeRefs)` fails.
     */
    @Test
    fun `file exceeding per-file scan budget is skipped and returns no references`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Target.kt",
            content = "package sample\n\nfun target(): String = \"target\"\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/LargeCandidate.kt",
            content = largeCandidateFileContent(),
        )
        val queryFile = writeFile(
            relativePath = "src/main/kotlin/sample/QueryFile.kt",
            content = "package sample\n\nfun caller(): String = target()\n",
        )
        val offset = Files.readString(queryFile).indexOf("target")

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        ).use { session ->
            val result = backendWithBudget(session, budgetMillis = 1L).findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = queryFile.toString(), offset = offset),
                ),
            )
            val largeRefs = result.references.count { it.filePath.endsWith("LargeCandidate.kt") }
            assertEquals(
                0, largeRefs,
                "Expected LargeCandidate.kt to be skipped (0 refs) because its walk exceeds " +
                "the 1 ms budget, but found $largeRefs references. " +
                "Is perFileScanBudgetMillis being enforced?",
            )
        }
    }

    /**
     * When one large candidate file is skipped, other (small) files in the same candidate
     * set must still produce results.
     *
     * FAILS on current code: the assertion `assertEquals(0, largeRefs)` fails because the
     * large file is fully walked and returns its reference.
     */
    @Test
    fun `other files in candidate set produce results when one file times out`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Target.kt",
            content = "package sample\n\nfun target(): String = \"target\"\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/LargeCandidate.kt",
            content = largeCandidateFileContent("usesTargetLarge"),
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/SmallCandidate.kt",
            content = "package sample\n\nfun usesTargetSmall(): String = target()\n",
        )
        val queryFile = writeFile(
            relativePath = "src/main/kotlin/sample/QueryFile.kt",
            content = "package sample\n\nfun caller(): String = target()\n",
        )
        val offset = Files.readString(queryFile).indexOf("target")

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        ).use { session ->
            val result = backendWithBudget(session, budgetMillis = 1L).findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = queryFile.toString(), offset = offset),
                ),
            )

            val largeRefs = result.references.count { it.filePath.endsWith("LargeCandidate.kt") }
            assertEquals(
                0, largeRefs,
                "LargeCandidate.kt should be skipped but contributed $largeRefs references.",
            )

            val smallRefs = result.references.count { it.filePath.endsWith("SmallCandidate.kt") }
            assertTrue(
                smallRefs > 0,
                "SmallCandidate.kt should still produce references but found $smallRefs.",
            )
        }
    }

    /**
     * Even with a pathologically large candidate file, `findReferences` must finish within
     * a wall-clock ceiling when the per-file budget is enforced.
     *
     * On current code the bound should still hold (PSI walks are generally fast),
     * but this test documents the intent that the budget limits worst-case latency.
     */
    @Test
    fun `findReferences completes within wall-clock bound even when candidate file is large`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Target.kt",
            content = "package sample\n\nfun target(): String = \"target\"\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/LargeCandidate.kt",
            content = largeCandidateFileContent(),
        )
        val queryFile = writeFile(
            relativePath = "src/main/kotlin/sample/QueryFile.kt",
            content = "package sample\n\nfun caller(): String = target()\n",
        )
        val offset = Files.readString(queryFile).indexOf("target")

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        ).use { session ->
            val elapsedMs = measureTimeMillis {
                backendWithBudget(session, budgetMillis = 1L).findReferences(
                    ReferencesQuery(
                        position = FilePosition(filePath = queryFile.toString(), offset = offset),
                    ),
                )
            }
            assertTrue(
                elapsedMs < 60_000,
                "findReferences took ${elapsedMs} ms — expected < 60 s with budget enforcement.",
            )
        }
    }

    /**
     * When at least one file is skipped due to the per-file budget, the `kast.findReferences`
     * OpenTelemetry span must record:
     *  - `kast.references.skippedFileCount` attribute with a value > 0
     *  - a `file-scan-timeout` event (requires VERBOSE detail level)
     *
     * FAILS on current code: neither the attribute nor the event exists yet.
     */
    @Test
    fun `telemetry records skipped files when per-file scan budget is exceeded`() = runTest {
        writeFile(
            relativePath = "src/main/kotlin/sample/Target.kt",
            content = "package sample\n\nfun target(): String = \"target\"\n",
        )
        writeFile(
            relativePath = "src/main/kotlin/sample/LargeCandidate.kt",
            content = largeCandidateFileContent(),
        )
        val queryFile = writeFile(
            relativePath = "src/main/kotlin/sample/QueryFile.kt",
            content = "package sample\n\nfun caller(): String = target()\n",
        )
        val offset = Files.readString(queryFile).indexOf("target")
        val telemetryFile = workspaceRoot.resolve("build/telemetry/budget-spans.jsonl")
        val telemetry = StandaloneTelemetry.create(
            StandaloneTelemetryConfig(
                enabled = true,
                scopes = setOf(StandaloneTelemetryScope.REFERENCES),
                detail = StandaloneTelemetryDetail.VERBOSE,
                outputFile = telemetryFile,
            ),
        )

        StandaloneAnalysisSession(
            workspaceRoot = workspaceRoot,
            sourceRoots = emptyList(),
            classpathRoots = emptyList(),
            moduleName = "sources",
        ).use { session ->
            backendWithBudget(session, budgetMillis = 1L, telemetry = telemetry).findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = queryFile.toString(), offset = offset),
                ),
            )
        }

        val exportedSpans = telemetryFile.readText()
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line -> Json.parseToJsonElement(line).jsonObject }
            .toList()
        val referencesSpan = exportedSpans.find { it["name"]?.toString() == "\"kast.findReferences\"" }
        val attributes = referencesSpan?.get("attributes")?.jsonObject

        val skippedCount = attributes
            ?.get("kast.references.skippedFileCount")?.jsonPrimitive?.content?.toLongOrNull()
        assertTrue(
            skippedCount != null && skippedCount > 0,
            "Expected kast.references.skippedFileCount > 0 in span attributes but got: $attributes",
        )

        val events = referencesSpan?.get("events")?.jsonArray
                     ?: error("No 'events' key in span: $referencesSpan")
        val hasTimeoutEvent = events.any { event ->
            event.jsonObject["name"]?.toString() == "\"file-scan-timeout\""
        }
        assertTrue(
            hasTimeoutEvent,
            "Expected a 'file-scan-timeout' event on the kast.findReferences span but found: " +
            events.map { it.jsonObject["name"] },
        )
    }
}
