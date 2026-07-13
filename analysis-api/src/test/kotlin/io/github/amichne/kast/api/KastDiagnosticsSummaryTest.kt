package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import io.github.amichne.kast.api.contract.skill.KastDiagnosticsSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KastDiagnosticsSummaryTest {
    private val filePath = NormalizedPath.ofAbsolute(Path.of("/workspace/Sample.kt"))

    @Test
    fun `incomplete evidence is not clean even without compiler errors`() {
        val result = DiagnosticsResult.of(
            diagnostics = emptyList(),
            fileStatuses = listOf(
                FileAnalysisStatus.skipped(
                    filePath,
                    FileAnalysisState.PENDING_INDEX,
                    "IDEA is indexing",
                ),
            ),
        )

        val summary = KastDiagnosticsSummary.from(result)

        assertFalse(summary.clean)
        assertEquals(0, summary.errorCount)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, summary.semanticOutcome)
        assertEquals(1, summary.requestedFileCount)
        assertEquals(0, summary.analyzedFileCount)
        assertEquals(1, summary.skippedFileCount)
    }

    @Test
    fun `ordinary compiler error remains complete but not clean`() {
        val result = DiagnosticsResult.of(
            diagnostics = listOf(compilerError()),
            fileStatuses = listOf(FileAnalysisStatus.analyzed(filePath)),
        )

        val summary = KastDiagnosticsSummary.from(result)

        assertFalse(summary.clean)
        assertEquals(1, summary.errorCount)
        assertEquals(SemanticAnalysisOutcome.COMPLETE, summary.semanticOutcome)
        assertEquals(1, summary.requestedFileCount)
        assertEquals(1, summary.analyzedFileCount)
        assertEquals(0, summary.skippedFileCount)
    }

    @Test
    fun `independently derived summaries retain value identity`() {
        val result = DiagnosticsResult.of(
            diagnostics = listOf(compilerError()),
            fileStatuses = listOf(FileAnalysisStatus.analyzed(filePath)),
        )

        val first = KastDiagnosticsSummary.from(result)
        val second = KastDiagnosticsSummary.from(result)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `incomplete semantic admission becomes a fail closed mutation summary`() {
        val result = RefreshResult.focused(
            fileStatuses = listOf(
                SemanticAdmissionStatus.incomplete(
                    filePath = filePath,
                    fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
                    sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
                    indexAdmission = IndexAdmissionState.PENDING,
                    analysisAvailability = AnalysisAvailabilityState.PENDING,
                    analysisStatus = FileAnalysisStatus.skipped(
                        filePath,
                        FileAnalysisState.PENDING_INDEX,
                        "IDEA is indexing",
                    ),
                ),
            ),
            attemptCount = 3,
            elapsedMillis = 50,
        )

        val summary = KastDiagnosticsSummary.from(result)

        assertFalse(summary.clean)
        assertEquals(0, summary.errorCount)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, summary.semanticOutcome)
        assertEquals(1, summary.requestedFileCount)
        assertEquals(0, summary.analyzedFileCount)
        assertEquals(1, summary.skippedFileCount)
    }

    private fun compilerError(): Diagnostic = Diagnostic(
        location = Location(
            filePath = filePath.value,
            startOffset = 0,
            endOffset = 1,
            startLine = 0,
            startColumn = 0,
            preview = "x",
        ),
        severity = DiagnosticSeverity.ERROR,
        message = "Type mismatch",
        code = "TYPE_MISMATCH",
    )
}
