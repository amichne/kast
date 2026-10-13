package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.evidence.sqlite.historical.cli.HistoricalUsageOracle
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalAttributedInputs
import io.github.amichne.kast.topology.contract.historical.HistoricalAttributedSource
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasis
import io.github.amichne.kast.topology.contract.historical.HistoricalCapturePlan
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureOutcome
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

/** Scripted facts prove durable progress and failure evidence, not native extraction. */
class HistoricalSqliteLateFailureTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `late fault in fresh stage reports committed progress and retry matches uninterrupted publication`() {
        examineLateFailure(
            existing = StoredRows(0, 0, emptyList()),
            afterFault = StoredRows(3, 0, listOf("contracts/Api.kt")),
            retryFiles = 3,
            retryRows = 10,
        )
    }

    @Test
    fun `late fault in partial stage reports new progress and retry matches uninterrupted publication`() {
        examineLateFailure(
            existing = StoredRows(3, 0, listOf("contracts/Api.kt")),
            afterFault = StoredRows(4, 3, listOf("contracts/Api.kt", "feature/Main.kt")),
            retryFiles = 2,
            retryRows = 5,
        )
    }

    @Test
    fun `late SQL fault in fresh stage preserves its exact committed receipt`() {
        examineLateFailure(
            existing = StoredRows(0, 0, emptyList()),
            afterFault = StoredRows(3, 0, listOf("contracts/Api.kt")),
            retryFiles = 3,
            retryRows = 10,
            cause = HistoricalStageEffectCause.SQL,
        )
    }

    @Test
    fun `late SQL fault in partial stage preserves its exact committed receipt`() {
        examineLateFailure(
            existing = StoredRows(3, 0, listOf("contracts/Api.kt")),
            afterFault = StoredRows(4, 3, listOf("contracts/Api.kt", "feature/Main.kt")),
            retryFiles = 2,
            retryRows = 5,
            cause = HistoricalStageEffectCause.SQL,
        )
    }

    @Test
    fun `first file fault reports settled zero new commits and permits complete retry`() {
        examineLateFailure(
            existing = StoredRows(0, 0, emptyList()),
            afterFault = StoredRows(0, 0, emptyList()),
            retryFiles = 4,
            retryRows = 14,
            faultAt = 1,
        )
    }

    private fun examineLateFailure(
        existing: StoredRows,
        afterFault: StoredRows,
        retryFiles: Long,
        retryRows: Long,
        cause: HistoricalStageEffectCause = HistoricalStageEffectCause.IO,
        faultAt: Int = 2,
    ) {
        val root = directory.toRealPath()
        val stage = root.resolve("capture.stage.sqlite")
        val artifact = root.resolve("published.sqlite")
        val uninterrupted = root.resolve("uninterrupted.sqlite")
        val basis = scriptedBasis(HistoricalUsageOracle.snapshot())
        SqliteHistoricalSnapshotStore.publish(artifact, basis.snapshot).value()
        val priorPublication = Files.readAllBytes(artifact)
        if (existing.progress.isNotEmpty()) {
            SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 5).value()).value()
            assertEquals(existing, storedRows(stage))
        }
        var commitSignals = 0
        val outcome =
            SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(4, 5).value()) {
                commitSignals++
                if (commitSignals == faultAt) throwControlFault(cause)
                HistoricalStageSignal.Continue
            }
        assertEquals(faultAt, commitSignals)
        assertEquals(afterFault, storedRows(stage))
        assertFailureReceipt(outcome, basis, existing, afterFault, cause, faultAt)
        assertArrayEquals(priorPublication, Files.readAllBytes(artifact))
        assertRetry(stage, basis, afterFault, retryFiles, retryRows)
        assertArrayEquals(priorPublication, Files.readAllBytes(artifact))
        SqliteHistoricalSnapshotStaging.publish(stage, artifact, scriptedBasis(basis.snapshot)).value()
        SqliteHistoricalSnapshotStore.publish(uninterrupted, basis.snapshot).value()
        assertSameFacts(artifact, uninterrupted, basis.snapshot)
    }

    private fun throwControlFault(cause: HistoricalStageEffectCause): Nothing =
        when (cause) {
            HistoricalStageEffectCause.IO -> throw IOException("controlled beforeCommit fault")
            HistoricalStageEffectCause.SQL -> throw SQLException("controlled beforeCommit fault")
            HistoricalStageEffectCause.ACCESS -> error("This fixture declares only IO and SQL faults")
        }

    private fun assertFailureReceipt(
        outcome: Refinement<HistoricalStageAdvance, HistoricalStageFailure>,
        basis: HistoricalCaptureBasis,
        existing: StoredRows,
        afterFault: StoredRows,
        cause: HistoricalStageEffectCause,
        faultAt: Int,
    ) {
        val rejected = assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(outcome)
        val failure = assertInstanceOf<HistoricalStageFailure.Effect>(rejected.failure)
        assertEquals(HistoricalStageFault.Storage(HistoricalStagePhase.CONTROL, cause), failure.fault)
        assertEquals(
            HistoricalStageCleanup(HistoricalStageCleanupOutcome.Completed, HistoricalStageCleanupOutcome.Completed),
            failure.cleanup,
        )
        val receipt = assertInstanceOf<HistoricalStageProgress.Settled>(failure.progress).receipt
        assertEquals(basis.snapshot.identity, receipt.identity)
        assertEquals(HistoricalStageCompletion.PENDING, receipt.completion)
        assertEquals(existing.progress.size.toLong(), receipt.work.filesReused.value)
        assertEquals(existing.totalRows, receipt.work.rowsReused.value)
        assertEquals(afterFault.progress.size.toLong() - existing.progress.size, receipt.work.filesCommitted.value)
        assertEquals(afterFault.totalRows - existing.totalRows, receipt.work.rowsCommitted.value)
        assertEquals(1L, receipt.work.rolledBackBatches.value)
        assertEquals(if (faultAt == 1) 4L else 5L, receipt.work.maximumBatchRows.value)
        assertEquals(38L + existing.totalRows, receipt.work.verificationRows.value)
        assertEquals(48L, receipt.work.completeBasisRowsRetained.value)
        // Fresh-basis comparison visits ten semantic facts. Each file preflight and materialization visits ten more.
        // Fresh late failure: 10 + 40 + 20. Partial late failure: 10 + 30 + 20. First failure: 10 + 40 + 10.
        val expectedVisits = if (faultAt == 1 || existing.progress.isNotEmpty()) 60L else 70L
        assertEquals(expectedVisits, receipt.work.basisFactRowsExamined.value)
    }

    private fun assertRetry(
        stage: Path,
        basis: HistoricalCaptureBasis,
        afterFault: StoredRows,
        retryFiles: Long,
        retryRows: Long,
    ) {
        val retried =
            assertInstanceOf<HistoricalStageAdvance.Advanced>(
                    SqliteHistoricalSnapshotStaging.advance(
                            stage,
                            scriptedBasis(basis.snapshot),
                            HistoricalStageBudget.parse(4, 5).value(),
                        )
                        .value()
                )
                .receipt
        assertEquals(HistoricalStageCompletion.READY, retried.completion)
        assertEquals(afterFault.progress.size.toLong(), retried.work.filesReused.value)
        assertEquals(
            afterFault.declarations + afterFault.occurrences + afterFault.progress.size,
            retried.work.rowsReused.value,
        )
        assertEquals(retryFiles, retried.work.filesCommitted.value)
        assertEquals(retryRows, retried.work.rowsCommitted.value)
    }

    private fun assertSameFacts(artifact: Path, uninterrupted: Path, expected: HistoricalSnapshot) {
        val resumed = openHistoricalConnection(artifact).use { it.readHistoricalSnapshot().value() }
        val baseline = openHistoricalConnection(uninterrupted).use { it.readHistoricalSnapshot().value() }
        assertEquals(expected.identity, resumed.identity)
        assertEquals(baseline.identity, resumed.identity)
        assertEquals(baseline.header, resumed.header)
        assertEquals(baseline.modules, resumed.modules)
        assertEquals(baseline.dependencies, resumed.dependencies)
        assertEquals(baseline.files, resumed.files)
        assertEquals(baseline.declarations, resumed.declarations)
        assertEquals(baseline.occurrences, resumed.occurrences)
        assertEquals(baseline.coverage, resumed.coverage)
    }

    private fun storedRows(stage: Path): StoredRows =
        DriverManager.getConnection("jdbc:sqlite:$stage").use { connection ->
            val counts =
                listOf("historical_declaration", "historical_stage_occurrence").map { table ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                            rows.next()
                            rows.getLong(1)
                        }
                    }
                }
            val progress =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT file FROM historical_stage_progress ORDER BY file").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            StoredRows(counts[0], counts[1], progress)
        }

    private fun scriptedBasis(snapshot: HistoricalSnapshot): HistoricalCaptureBasis {
        val plan =
            HistoricalCapturePlan.admit(snapshot.header, snapshot.modules.map { it.id }.toSet(), snapshot.files).value()
        val attribution =
            HistoricalAttributedInputs.fromGitObservation(
                    plan,
                    snapshot.header.revision,
                    snapshot.files.map { file ->
                        val tracked = assertInstanceOf<HistoricalFileProvenance.Tracked>(file.provenance)
                        HistoricalAttributedSource(file.path, file.content, tracked.blob)
                    },
                )
                .value()
        return HistoricalCaptureBasis.fromNativeCapture(
                plan,
                attribution,
                HistoricalNativeCaptureOutcome.Captured(snapshot),
            )
            .value()
    }

    private data class StoredRows(val declarations: Long, val occurrences: Long, val progress: List<String>) {
        val totalRows: Long
            get() = declarations + occurrences + progress.size
    }

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
