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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

/** The native outcome is explicitly scripted. These cases prove persistence, not native compiler coherence. */
class HistoricalSqliteStagingTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `interrupted file transaction resumes from exact committed facts and publishes identical identity`() {
        val stage = directory.toRealPath().resolve("capture.stage.sqlite")
        val artifact = directory.toRealPath().resolve("published.sqlite")
        val basis = scriptedBasis(HistoricalUsageOracle.snapshot())
        val budget = HistoricalStageBudget.parse(1, 5).value()
        val first =
            assertInstanceOf<HistoricalStageAdvance.Advanced>(
                    SqliteHistoricalSnapshotStaging.advance(stage, basis, budget).value()
                )
                .receipt
        assertEquals(HistoricalStageCompletion.PENDING, first.completion)
        assertEquals(1L, first.work.filesCommitted.value)
        assertEquals(4L, first.work.rowsCommitted.value)
        val before = stageCounts(stage)
        val stopped =
            assertInstanceOf<HistoricalStageAdvance.Stopped>(
                SqliteHistoricalSnapshotStaging.advance(stage, basis, budget) {
                        HistoricalStageSignal.Stop(HistoricalStageStopCause.INTERRUPTED)
                    }
                    .value()
            )
        assertEquals(HistoricalStageStopCause.INTERRUPTED, stopped.cause)
        assertEquals(1L, stopped.receipt.work.rolledBackBatches.value)
        assertEquals(before, stageCounts(stage))
        assertEquals(
            HistoricalStorageFailure.SCHEMA_REJECTED,
            assertInstanceOf<Refinement.Rejected<HistoricalStorageFailure>>(SqliteHistoricalSnapshotStore.open(stage))
                .failure,
        )
        val finished =
            assertInstanceOf<HistoricalStageAdvance.Advanced>(
                    SqliteHistoricalSnapshotStaging.advance(
                            stage,
                            scriptedBasis(basis.snapshot),
                            HistoricalStageBudget.parse(10, 5).value(),
                        )
                        .value()
                )
                .receipt
        assertEquals(HistoricalStageCompletion.READY, finished.completion)
        assertResumeWork(finished)
        println(finished)
        val published = SqliteHistoricalSnapshotStaging.publish(stage, artifact, scriptedBasis(basis.snapshot)).value()
        println(published.work)
        SqliteHistoricalSnapshotStore.open(artifact).value().use { opened ->
            assertEquals(basis.snapshot.identity, opened.inventory.identity)
            assertEquals(4L, opened.inventory.counts.occurrences.value)
        }
    }

    @Test
    fun `oversized file rejects before its rows or progress change and pending publication preserves prior bytes`() {
        val stage = directory.toRealPath().resolve("oversized.stage.sqlite")
        val artifact = directory.toRealPath().resolve("prior.sqlite")
        val basis = scriptedBasis(HistoricalUsageOracle.snapshot())
        SqliteHistoricalSnapshotStore.publish(artifact, basis.snapshot).value()
        val original = Files.readAllBytes(artifact)
        SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 4).value()).value()
        val before = stageCounts(stage)
        assertEquals(
            HistoricalStageFailure.FileCapacityExceeded,
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(
                    SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 4).value())
                )
                .failure,
        )
        assertEquals(before, stageCounts(stage))
        assertEquals(
            HistoricalStageFailure.Incomplete,
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(
                    SqliteHistoricalSnapshotStaging.publish(stage, artifact, basis)
                )
                .failure,
        )
        assertArrayEquals(original, Files.readAllBytes(artifact))
    }

    @Test
    fun `tampered committed facts cannot become reusable evidence`() {
        val stage = directory.toRealPath().resolve("corrupt.stage.sqlite")
        val basis = scriptedBasis(HistoricalUsageOracle.snapshot())
        SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 5).value()).value()
        DriverManager.getConnection("jdbc:sqlite:$stage").use { connection ->
            connection.createStatement().use { it.execute("UPDATE historical_declaration SET signature = 'wrong'") }
        }
        assertEquals(
            HistoricalStageFailure.ContentRejected,
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(
                    SqliteHistoricalSnapshotStaging.advance(
                        stage,
                        scriptedBasis(basis.snapshot),
                        HistoricalStageBudget.parse(1, 5).value(),
                    )
                )
                .failure,
        )
    }

    @Test
    fun `changed fresh compiler context rejects resume without changing committed facts`() {
        val stage = directory.toRealPath().resolve("changed-context.stage.sqlite")
        val original = HistoricalUsageOracle.snapshot()
        val basis = scriptedBasis(original)
        SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 5).value()).value()
        val before = stageCounts(stage)
        val changed =
            io.github.amichne.kast.topology.contract.historical
                .HistoricalSnapshotInput(
                    original.header.copy(
                        context =
                            original.header.context.copy(
                                compiler =
                                    io.github.amichne.kast.topology.contract.historical.HistoricalDigest.parse(
                                            "f".repeat(64)
                                        )
                                        .value()
                            )
                    ),
                    original.modules,
                    original.dependencies,
                    original.files,
                    original.declarations,
                    original.occurrences,
                    original.coverage,
                )
                .admit()
                .value()
        assertEquals(
            HistoricalStageFailure.BasisMismatch,
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(
                    SqliteHistoricalSnapshotStaging.advance(
                        stage,
                        scriptedBasis(changed),
                        HistoricalStageBudget.parse(1, 5).value(),
                    )
                )
                .failure,
        )
        assertEquals(before, stageCounts(stage))
    }

    @Test
    fun `explicit cancellation rolls back facts and progress together`() {
        val stage = directory.toRealPath().resolve("cancelled.stage.sqlite")
        val basis = scriptedBasis(HistoricalUsageOracle.snapshot())
        SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 5).value()).value()
        val before = stageCounts(stage)
        val stopped =
            assertInstanceOf<HistoricalStageAdvance.Stopped>(
                SqliteHistoricalSnapshotStaging.advance(
                        stage,
                        scriptedBasis(basis.snapshot),
                        HistoricalStageBudget.parse(1, 5).value(),
                    ) {
                        HistoricalStageSignal.Stop(HistoricalStageStopCause.CANCELLED)
                    }
                    .value()
            )
        assertEquals(HistoricalStageStopCause.CANCELLED, stopped.cause)
        assertEquals(1L, stopped.receipt.work.rolledBackBatches.value)
        assertEquals(before, stageCounts(stage))
    }

    @Test
    fun `effect fault before batch commit preserves progress and previous publication`() {
        val stage = directory.toRealPath().resolve("fault.stage.sqlite")
        val artifact = directory.toRealPath().resolve("unchanged.sqlite")
        val basis = scriptedBasis(HistoricalUsageOracle.snapshot())
        SqliteHistoricalSnapshotStore.publish(artifact, basis.snapshot).value()
        SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 5).value()).value()
        val before = stageCounts(stage)
        val bytes = Files.readAllBytes(artifact)
        val failure =
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(
                SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 5).value()) {
                    throw java.io.IOException("controlled stage effect fault")
                }
            )
        assertEquals(HistoricalStageFailure.Storage(HistoricalStorageFailure.STORAGE_UNAVAILABLE), failure.failure)
        assertEquals(before, stageCounts(stage))
        assertArrayEquals(bytes, Files.readAllBytes(artifact))
    }

    @Test
    fun `zero and excessive stage budgets are rejected`() {
        for ((files, rows) in listOf(0 to 1, 1 to 0, 1001 to 1, 1 to 100001)) {
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(HistoricalStageBudget.parse(files, rows))
        }
    }

    private fun assertResumeWork(finished: HistoricalStageReceipt) {
        assertEquals(1L, finished.work.filesReused.value)
        assertEquals(4L, finished.work.rowsReused.value)
        assertEquals(3L, finished.work.filesCommitted.value)
        assertEquals(10L, finished.work.rowsCommitted.value)
        assertEquals(5L, finished.work.maximumBatchRows.value)
        assertEquals(60L, finished.work.basisFactRowsExamined.value)
    }

    private fun stageCounts(stage: Path): List<Long> =
        DriverManager.getConnection("jdbc:sqlite:$stage").use { connection ->
            listOf("historical_declaration", "historical_stage_occurrence", "historical_stage_progress").map { table ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                }
            }
        }

    private fun scriptedBasis(snapshot: HistoricalSnapshot): HistoricalCaptureBasis {
        val plan =
            HistoricalCapturePlan.admit(snapshot.header, snapshot.modules.map { it.id }.toSet(), snapshot.files).value()
        val attributed =
            HistoricalAttributedInputs.fromGitObservation(
                    plan,
                    snapshot.header.revision,
                    snapshot.files.mapNotNull { file ->
                        when (val provenance = file.provenance) {
                            is HistoricalFileProvenance.Tracked ->
                                HistoricalAttributedSource(file.path, file.content, provenance.blob)
                            is HistoricalFileProvenance.Generated -> null
                        }
                    },
                )
                .value()
        return HistoricalCaptureBasis.fromNativeCapture(
                plan,
                attributed,
                HistoricalNativeCaptureOutcome.Captured(snapshot),
            )
            .value()
    }

    private fun <T, F> Refinement<T, F>.value(): T =
        assertInstanceOf<Refinement.Refined<T>>(this, "actual: $this").value
}
