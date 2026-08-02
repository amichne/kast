package io.github.amichne.kast.idea

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.result.AnalysisAvailabilityState
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.api.contract.result.FileSystemDiscoveryState
import io.github.amichne.kast.api.contract.result.IndexAdmissionState
import io.github.amichne.kast.api.contract.result.SemanticAdmissionStatus
import io.github.amichne.kast.api.contract.result.SourceModuleOwnershipState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class IdeaSemanticAdmissionAwaiterTest {
    private val filePath = NormalizedPath.ofAbsolute(Path.of("/workspace/src/main/kotlin/Sample.kt"))

    @Test
    fun `already admitted file returns on the first probe`() = runBlocking {
        var currentNanos = 0L
        var pauseCount = 0
        val awaiter = IdeaSemanticAdmissionAwaiter(
            maxWaitMillis = 1_500,
            pollIntervalMillis = 25,
            nanoTime = { currentNanos },
            pause = { pauseMillis ->
                pauseCount += 1
                currentNanos += pauseMillis * NANOS_PER_MILLISECOND
            },
        )

        val result = awaiter.await(listOf(filePath)) { SemanticAdmissionStatus.admitted(it) }

        assertEquals(1, result.attemptCount)
        assertEquals(0, result.elapsedMillis)
        assertEquals(0, pauseCount)
        assertEquals(FileAnalysisState.ANALYZED, result.fileStatuses.single().analysisStatus?.state)
    }

    @Test
    fun `pending file is retried until it is admitted`() = runBlocking {
        var currentNanos = 0L
        var probeCount = 0
        val awaiter = IdeaSemanticAdmissionAwaiter(
            maxWaitMillis = 1_500,
            pollIntervalMillis = 25,
            nanoTime = { currentNanos },
            pause = { pauseMillis -> currentNanos += pauseMillis * NANOS_PER_MILLISECOND },
        )

        val result = awaiter.await(listOf(filePath)) { path ->
            probeCount += 1
            if (probeCount == 1) pending(path) else SemanticAdmissionStatus.admitted(path)
        }

        assertEquals(2, result.attemptCount)
        assertEquals(25, result.elapsedMillis)
        assertEquals(FileAnalysisState.ANALYZED, result.fileStatuses.single().analysisStatus?.state)
    }

    @Test
    fun `persistent pending file returns bounded progress and incomplete evidence`() = runBlocking {
        var currentNanos = 0L
        val awaiter = IdeaSemanticAdmissionAwaiter(
            maxWaitMillis = 100,
            pollIntervalMillis = 25,
            nanoTime = { currentNanos },
            pause = { pauseMillis -> currentNanos += pauseMillis * NANOS_PER_MILLISECOND },
        )

        val result = awaiter.await(listOf(filePath), ::pending)

        assertEquals(5, result.attemptCount)
        assertEquals(100, result.elapsedMillis)
        assertEquals(FileAnalysisState.PENDING_INDEX, result.fileStatuses.single().analysisStatus?.state)
    }

    private fun pending(path: NormalizedPath): SemanticAdmissionStatus = SemanticAdmissionStatus.incomplete(
        filePath = path,
        fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
        sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
        indexAdmission = IndexAdmissionState.PENDING,
        analysisAvailability = AnalysisAvailabilityState.PENDING,
        analysisStatus = FileAnalysisStatus.skipped(
            path,
            FileAnalysisState.PENDING_INDEX,
            "IDEA indexing is still in progress",
        ),
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
