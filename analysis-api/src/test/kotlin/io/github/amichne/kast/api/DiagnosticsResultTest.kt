package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiagnosticsResultTest {
    private val first = NormalizedPath.ofAbsolute(Path.of("/workspace/First.kt"))
    private val second = NormalizedPath.ofAbsolute(Path.of("/workspace/Second.kt"))

    @Test
    fun `all analyzed files produce complete counts`() {
        val result = DiagnosticsResult.of(
            diagnostics = emptyList(),
            fileStatuses = listOf(
                FileAnalysisStatus.analyzed(first),
                FileAnalysisStatus.analyzed(second),
            ),
        )

        assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
        assertEquals(2, result.requestedFileCount)
        assertEquals(2, result.analyzedFileCount)
        assertEquals(0, result.skippedFileCount)
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
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
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
