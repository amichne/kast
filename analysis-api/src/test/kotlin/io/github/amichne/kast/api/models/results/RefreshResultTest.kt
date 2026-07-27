package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RefreshResultTest {
    private val admittedPath = NormalizedPath.ofAbsolute(Path.of("/workspace/src/main/kotlin/Admitted.kt"))
    private val removedPath = NormalizedPath.ofAbsolute(Path.of("/workspace/src/main/kotlin/Removed.kt"))
    private val pendingPath = NormalizedPath.ofAbsolute(Path.of("/workspace/src/test/kotlin/Pending.kt"))

    @Test
    fun `admitted and removed files produce a complete focused refresh`() {
        val result = RefreshResult.focused(
            fileStatuses = listOf(
                SemanticAdmissionStatus.admitted(admittedPath),
                SemanticAdmissionStatus.removed(removedPath),
            ),
            attemptCount = 1,
            elapsedMillis = 4,
        )

        assertEquals(listOf(admittedPath.value), result.refreshedFiles)
        assertEquals(listOf(removedPath.value), result.removedFiles)
        assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
        assertEquals(1, result.requestedFileCount)
        assertEquals(1, result.analyzedFileCount)
        assertEquals(0, result.skippedFileCount)
        assertEquals(1, result.removedFileCount)
        assertEquals(1, result.attemptCount)
        assertEquals(4, result.elapsedMillis)
        assertEquals(false, result.fullRefresh)
    }

    @Test
    fun `pending admission preserves separate stages and incomplete analysis evidence`() {
        val analysisStatus = FileAnalysisStatus.skipped(
            pendingPath,
            FileAnalysisState.PENDING_INDEX,
            "IDEA has not created PSI for the file yet",
        )
        val admissionStatus = SemanticAdmissionStatus.incomplete(
            filePath = pendingPath,
            fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
            sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
            indexAdmission = IndexAdmissionState.ADMITTED,
            analysisAvailability = AnalysisAvailabilityState.PENDING,
            analysisStatus = analysisStatus,
        )

        val result = RefreshResult.focused(
            fileStatuses = listOf(admissionStatus),
            attemptCount = 7,
            elapsedMillis = 1_500,
        )

        assertEquals(emptyList<String>(), result.refreshedFiles)
        assertEquals(emptyList<String>(), result.removedFiles)
        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(1, result.requestedFileCount)
        assertEquals(0, result.analyzedFileCount)
        assertEquals(1, result.skippedFileCount)
        assertEquals(0, result.removedFileCount)
        assertEquals(7, result.attemptCount)
        assertEquals(1_500, result.elapsedMillis)
        assertEquals(FileSystemDiscoveryState.DISCOVERED, admissionStatus.fileSystemDiscovery)
        assertEquals(SourceModuleOwnershipState.OWNED, admissionStatus.sourceModuleOwnership)
        assertEquals(IndexAdmissionState.ADMITTED, admissionStatus.indexAdmission)
        assertEquals(AnalysisAvailabilityState.PENDING, admissionStatus.analysisAvailability)
        assertEquals(FileAnalysisState.PENDING_INDEX, admissionStatus.analysisStatus?.state)
    }

    @Test
    fun `full refresh makes no per file semantic admission claim`() {
        val result = RefreshResult.full()

        assertEquals(true, result.fullRefresh)
        assertEquals(emptyList<SemanticAdmissionStatus>(), result.fileStatuses)
        assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
        assertEquals(0, result.requestedFileCount)
        assertEquals(0, result.analyzedFileCount)
        assertEquals(0, result.skippedFileCount)
        assertEquals(0, result.removedFileCount)
        assertEquals(1, result.attemptCount)
    }
}
