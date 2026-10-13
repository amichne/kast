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
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Path
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

/** Scripted capture facts establish only the stage's persistence and budget behavior. */
class HistoricalSqliteBatchCapacityTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `later oversized selected file rejects before admissible preceding file changes committed progress`() {
        val basis = scriptedBasis(snapshot())
        val stage = directory.toRealPath().resolve("reject.sqlite")
        SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(1, 4).value()).value()
        val before = storedRows(stage)
        assertEquals(StoredRows(3, 0, listOf("contracts/Api.kt")), before)
        var commitSignals = 0
        val rejected =
            SqliteHistoricalSnapshotStaging.advance(
                stage,
                basis,
                HistoricalStageBudget.parse(2, 4).value(),
            ) {
                commitSignals++
                HistoricalStageSignal.Continue
            }
        assertEquals(
            HistoricalStageFailure.FileCapacityExceeded,
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(rejected).failure,
        )
        assertEquals(before, storedRows(stage), "The rejected selected batch must keep all prior facts and progress")
        assertEquals(0, commitSignals, "Capacity admission must precede every selected file commit")
        val retried =
            assertInstanceOf<HistoricalStageAdvance.Advanced>(
                    SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(2, 5).value())
                        .value()
                )
                .receipt
        assertEquals(1L, retried.work.filesReused.value)
        assertEquals(4L, retried.work.rowsReused.value)
        assertEquals(2L, retried.work.filesCommitted.value)
        assertEquals(7L, retried.work.rowsCommitted.value)
        assertEquals(5L, retried.work.maximumBatchRows.value)
        assertEquals(55L, retried.work.basisFactRowsExamined.value)
        assertEquals(StoredRows(5, 3, listOf("contracts/Api.kt", "eager/Api.kt", "feature/Main.kt")), storedRows(stage))
    }

    @Test
    fun `fresh stage rejected for a later oversized file keeps no facts or progress and accepts an adequate retry`() {
        val basis = scriptedBasis(snapshot())
        val stage = directory.toRealPath().resolve("accepted.sqlite")
        var commitSignals = 0
        val rejected =
            SqliteHistoricalSnapshotStaging.advance(
                stage,
                basis,
                HistoricalStageBudget.parse(3, 4).value(),
            ) {
                commitSignals++
                HistoricalStageSignal.Continue
            }
        assertEquals(
            HistoricalStageFailure.FileCapacityExceeded,
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(rejected).failure,
        )
        assertEquals(StoredRows(0, 0, emptyList()), storedRows(stage))
        assertEquals(0, commitSignals)
        val receipt =
            assertInstanceOf<HistoricalStageAdvance.Advanced>(
                    SqliteHistoricalSnapshotStaging.advance(stage, basis, HistoricalStageBudget.parse(3, 5).value())
                        .value()
                )
                .receipt
        assertEquals(0L, receipt.work.filesReused.value)
        assertEquals(0L, receipt.work.rowsReused.value)
        assertEquals(3L, receipt.work.filesCommitted.value)
        assertEquals(11L, receipt.work.rowsCommitted.value)
        assertEquals(5L, receipt.work.maximumBatchRows.value)
        assertEquals(77L, receipt.work.basisFactRowsExamined.value)
        assertEquals(StoredRows(5, 3, listOf("contracts/Api.kt", "eager/Api.kt", "feature/Main.kt")), storedRows(stage))
    }

    private fun snapshot(): HistoricalSnapshot {
        val base = HistoricalUsageOracle.snapshot()
        val path = WorkspaceSourcePath.parse("eager/Api.kt").value()
        val source = base.files.single { it.path.value == "isolated/Api.kt" }
        val declaration = base.declarations.single { it.id == HistoricalUsageOracle.isolatedApi }
        return HistoricalSnapshotInput(
                base.header,
                base.modules,
                base.dependencies,
                base.files + source.copy(path = path),
                base.declarations + declaration.copy(id = declaration.id.copy(file = path)),
                base.occurrences,
                base.coverage,
            )
            .admit()
            .value()
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
        val attributed =
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
                attributed,
                HistoricalNativeCaptureOutcome.Captured(snapshot),
            )
            .value()
    }

    private data class StoredRows(val declarations: Long, val occurrences: Long, val progress: List<String>)

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
