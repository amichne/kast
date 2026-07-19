package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiagnosticsResultTest {
    private val first = NormalizedPath.ofAbsolute(Path.of("/workspace/First.kt"))
    private val second = NormalizedPath.ofAbsolute(Path.of("/workspace/Second.kt"))
    private val firstHash = FileHash(first.value, FileHashing.sha256("first"))
    private val secondHash = FileHash(second.value, FileHashing.sha256("second"))

    @Test
    fun `all analyzed files produce complete counts`() {
        val result = DiagnosticsResult.of(
            diagnostics = emptyList(),
            fileStatuses = listOf(
                FileAnalysisStatus.analyzed(first),
                FileAnalysisStatus.analyzed(second),
            ),
            fileHashes = listOf(firstHash, secondHash),
        )

        assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
        assertEquals(2, result.requestedFileCount)
        assertEquals(2, result.analyzedFileCount)
        assertEquals(0, result.skippedFileCount)
    }

    @Test
    fun `exact diagnostic cardinality carries its wire discriminator`() {
        val result = DiagnosticsResult.of(
            diagnostics = emptyList(),
            fileStatuses = listOf(FileAnalysisStatus.analyzed(first)),
            fileHashes = listOf(firstHash),
        )

        val cardinality = Json.encodeToJsonElement(DiagnosticsResult.serializer(), result)
            .jsonObject
            .getValue("cardinality")
            .jsonObject
        val encoded = Json.encodeToJsonElement(DiagnosticsResult.serializer(), result).jsonObject

        assertEquals("EXACT", cardinality.getValue("type").jsonPrimitive.content)
        assertEquals(0, cardinality.getValue("totalCount").jsonPrimitive.content.toInt())
        assertTrue(encoded.containsKey("fileHashes"))
    }

    @Test
    fun `a skipped file produces an incomplete result`() {
        val result = DiagnosticsResult.of(
            diagnostics = listOf(analysisFailure(second.value, "File not found")),
            fileStatuses = listOf(
                FileAnalysisStatus.analyzed(first),
                FileAnalysisStatus.skipped(
                    second,
                    FileAnalysisState.MISSING_ON_DISK,
                    "File not found",
                ),
            ),
            fileHashes = listOf(firstHash),
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(2, result.requestedFileCount)
        assertEquals(1, result.analyzedFileCount)
        assertEquals(1, result.skippedFileCount)
    }

    @Test
    fun `analysis failure cannot produce a complete result`() {
        val result = DiagnosticsResult.of(
            diagnostics = listOf(analysisFailure(first.value, "backend failed")),
            fileStatuses = listOf(FileAnalysisStatus.analyzed(first)),
            fileHashes = listOf(firstHash),
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
    }

    @Test
    fun `file hashes bind exactly the analyzed files in request order`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsResult.of(
                diagnostics = emptyList(),
                fileStatuses = listOf(
                    FileAnalysisStatus.analyzed(first),
                    FileAnalysisStatus.analyzed(second),
                ),
                fileHashes = listOf(secondHash, firstHash),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsResult.of(
                diagnostics = emptyList(),
                fileStatuses = listOf(FileAnalysisStatus.analyzed(first)),
                fileHashes = emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsResult.of(
                diagnostics = emptyList(),
                fileStatuses = listOf(FileAnalysisStatus.analyzed(first)),
                fileHashes = listOf(FileHash(first.value, "not-a-sha-256-digest")),
            )
        }
    }

    @Test
    fun `high cardinality diagnostics pages retain exact counts and do not overlap`() {
        val diagnostics = (0 until 500).map { offset ->
            Diagnostic(
                location = Location(
                    filePath = first.value,
                    startOffset = offset,
                    endOffset = offset + 1,
                    startLine = offset + 1,
                    startColumn = 1,
                    preview = "diagnostic $offset",
                ),
                severity = if (offset == 0) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                message = "compiler diagnostic $offset",
                code = if (offset == 0) "COMPILER_ERROR" else "COMPILER_WARNING",
            )
        }

        val firstPage = DiagnosticsResult.paged(
            diagnostics = diagnostics,
            fileStatuses = listOf(FileAnalysisStatus.analyzed(first)),
            fileHashes = listOf(firstHash),
            pageOffset = 0,
            maxResults = 8,
            nextPageToken = "00000000-0000-0000-0000-000000000338",
        )
        val secondPage = DiagnosticsResult.paged(
            diagnostics = diagnostics,
            fileStatuses = listOf(FileAnalysisStatus.analyzed(first)),
            fileHashes = listOf(firstHash),
            pageOffset = 8,
            maxResults = 8,
            nextPageToken = "00000000-0000-0000-0000-000000000339",
        )

        assertEquals(ResultCardinality.Exact(500), firstPage.cardinality)
        assertEquals(1, firstPage.severityCounts.error)
        assertEquals(499, firstPage.severityCounts.warning)
        assertEquals("00000000-0000-0000-0000-000000000338", firstPage.page?.nextPageToken)
        assertEquals("00000000-0000-0000-0000-000000000339", secondPage.page?.nextPageToken)
        assertEquals(8, firstPage.diagnostics.size)
        assertEquals(8, secondPage.diagnostics.size)
        assertEquals(listOf(firstHash), firstPage.fileHashes)
        assertEquals(firstPage.fileHashes, secondPage.fileHashes)
        assertEquals(
            emptySet<Diagnostic>(),
            firstPage.diagnostics.toSet().intersect(secondPage.diagnostics.toSet()),
        )
    }

    private fun analysisFailure(filePath: String, message: String): Diagnostic = Diagnostic(
        location = Location(
            filePath = filePath,
            startOffset = 0,
            endOffset = 0,
            startLine = 0,
            startColumn = 0,
            preview = "",
        ),
        severity = DiagnosticSeverity.ERROR,
        message = message,
        code = "ANALYSIS_FAILURE",
    )
}
