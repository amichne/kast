package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.evidence.sqlite.historical.cli.HistoricalUsageOracle
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalAttributedInputs
import io.github.amichne.kast.topology.contract.historical.HistoricalAttributedSource
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasis
import io.github.amichne.kast.topology.contract.historical.HistoricalCapturePlan
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureOutcome
import java.io.IOException
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Scripted capture facts and real SQLite establish effect outcomes, not native capture or process-crash durability. */
class HistoricalSqliteStageEffectsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `second commit failure before durable write keeps only its acknowledged prefix and fresh retry reuses it`() {
        assertCommitFailure(Step.COMMIT_FAIL_BEFORE, 1, 4, StoredRows(3, 0, listOf("contracts/Api.kt")))
    }

    @Test
    fun `second commit failure after durable write remains uncertain and fresh retry admits the actual prefix`() {
        assertCommitFailure(
            Step.COMMIT_FAIL_AFTER,
            2,
            9,
            StoredRows(4, 3, listOf("contracts/Api.kt", "feature/Main.kt")),
        )
    }

    @Test
    fun `rollback failure preserves the control fault and never claims a rolled back batch`() {
        val stage = stage()
        val basis = scriptedBasis()
        val effects = ConnectionScript(Step.COMMIT, Step.ROLLBACK_FAIL, Step.CLOSE)
        val control = SecondFileControl { throw IOException("controlled before-commit failure") }
        val failure = effectFailure(advance(stage, basis, effects, control))
        assertEquals(
            HistoricalStageFault.Storage(HistoricalStagePhase.CONTROL, HistoricalStageEffectCause.IO),
            failure.fault,
        )
        val uncertain = assertInstanceOf<HistoricalStageProgress.Uncertain>(failure.progress)
        assertEquals(HistoricalStageTransactionUncertainty.ROLLBACK, uncertain.transaction)
        assertPrefix(uncertain.lastConfirmed, basis, 1, 4, 0)
        assertEquals(HistoricalStageCleanupOutcome.Failed(HistoricalStageEffectCause.SQL), failure.cleanup.rollback)
        assertEquals(HistoricalStageCleanupOutcome.Completed, failure.cleanup.close)
        assertEquals(StoredRows(3, 0, listOf("contracts/Api.kt")), storedRows(stage))
        effects.assertConsumed()
        control.assertConsumed()
    }

    @Test
    fun `close failure after acknowledged commits preserves exact settled progress`() {
        val stage = stage()
        val basis = scriptedBasis()
        val effects = ConnectionScript(Step.COMMIT, Step.COMMIT, Step.ROLLBACK, Step.CLOSE_FAIL)
        val failure = effectFailure(advance(stage, basis, effects))
        assertEquals(
            HistoricalStageFault.Storage(HistoricalStagePhase.CLOSE, HistoricalStageEffectCause.SQL),
            failure.fault,
        )
        val settled = assertInstanceOf<HistoricalStageProgress.Settled>(failure.progress)
        assertPrefix(settled.receipt, basis, 2, 9, 0)
        assertEquals(HistoricalStageCleanupOutcome.Completed, failure.cleanup.rollback)
        assertEquals(HistoricalStageCleanupOutcome.Failed(HistoricalStageEffectCause.SQL), failure.cleanup.close)
        assertEquals(StoredRows(4, 3, listOf("contracts/Api.kt", "feature/Main.kt")), storedRows(stage))
        effects.assertConsumed()
    }

    @Test
    fun `close failure after an explicit stop preserves the stop cause and settled prefix`() {
        val stage = stage()
        val basis = scriptedBasis()
        val effects = ConnectionScript(Step.COMMIT, Step.ROLLBACK, Step.CLOSE_FAIL)
        val control = SecondFileControl { HistoricalStageSignal.Stop(HistoricalStageStopCause.INTERRUPTED) }
        val failure = effectFailure(advance(stage, basis, effects, control))
        assertEquals(HistoricalStageFault.Stopped(HistoricalStageStopCause.INTERRUPTED), failure.fault)
        val settled = assertInstanceOf<HistoricalStageProgress.Settled>(failure.progress)
        assertPrefix(settled.receipt, basis, 1, 4, 1)
        assertEquals(HistoricalStageCleanupOutcome.Completed, failure.cleanup.rollback)
        assertEquals(HistoricalStageCleanupOutcome.Failed(HistoricalStageEffectCause.SQL), failure.cleanup.close)
        assertEquals(StoredRows(3, 0, listOf("contracts/Api.kt")), storedRows(stage))
        effects.assertConsumed()
        control.assertConsumed()
    }

    @Test
    fun `thrown cancellation preserves its category original cause and acknowledged progress`() {
        val stage = stage()
        val basis = scriptedBasis()
        val effects = ConnectionScript(Step.COMMIT, Step.ROLLBACK, Step.CLOSE)
        val original = CancellationException("controlled cancellation")
        val control = SecondFileControl { throw original }
        val cancelled = assertThrows<HistoricalStageCancellationException> { advance(stage, basis, effects, control) }
        assertSame(original, cancelled.cause)
        val settled = assertInstanceOf<HistoricalStageProgress.Settled>(cancelled.progress)
        assertPrefix(settled.receipt, basis, 1, 4, 1)
        assertEquals(HistoricalStageCleanupOutcome.Completed, cancelled.cleanup.rollback)
        assertEquals(HistoricalStageCleanupOutcome.Completed, cancelled.cleanup.close)
        assertEquals(StoredRows(3, 0, listOf("contracts/Api.kt")), storedRows(stage))
        effects.assertConsumed()
        control.assertConsumed()
    }

    @Test
    fun `thrown cancellation keeps rollback and close failures without a false receipt`() {
        val stage = stage()
        val basis = scriptedBasis()
        val effects = ConnectionScript(Step.COMMIT, Step.ROLLBACK_FAIL, Step.CLOSE_FAIL)
        val original = CancellationException("controlled cancellation with cleanup faults")
        val control = SecondFileControl { throw original }
        val cancelled = assertThrows<HistoricalStageCancellationException> { advance(stage, basis, effects, control) }
        assertSame(original, cancelled.cause)
        val uncertain = assertInstanceOf<HistoricalStageProgress.Uncertain>(cancelled.progress)
        assertEquals(HistoricalStageTransactionUncertainty.ROLLBACK, uncertain.transaction)
        assertPrefix(uncertain.lastConfirmed, basis, 1, 4, 0)
        assertEquals(HistoricalStageCleanupOutcome.Failed(HistoricalStageEffectCause.SQL), cancelled.cleanup.rollback)
        assertEquals(HistoricalStageCleanupOutcome.Failed(HistoricalStageEffectCause.SQL), cancelled.cleanup.close)
        assertEquals(StoredRows(3, 0, listOf("contracts/Api.kt")), storedRows(stage))
        effects.assertConsumed()
        control.assertConsumed()
    }

    private fun assertCommitFailure(step: Step, reusedFiles: Long, reusedRows: Long, expectedRows: StoredRows) {
        val stage = stage()
        val basis = scriptedBasis()
        val effects = ConnectionScript(Step.COMMIT, step, Step.ROLLBACK, Step.CLOSE)
        val failure = effectFailure(advance(stage, basis, effects))
        assertEquals(
            HistoricalStageFault.Storage(HistoricalStagePhase.COMMIT, HistoricalStageEffectCause.SQL),
            failure.fault,
        )
        val uncertain = assertInstanceOf<HistoricalStageProgress.Uncertain>(failure.progress)
        assertEquals(HistoricalStageTransactionUncertainty.COMMIT, uncertain.transaction)
        assertPrefix(uncertain.lastConfirmed, basis, 1, 4, 0)
        assertEquals(HistoricalStageCleanupOutcome.Completed, failure.cleanup.rollback)
        assertEquals(HistoricalStageCleanupOutcome.Completed, failure.cleanup.close)
        assertEquals(expectedRows, storedRows(stage))
        effects.assertConsumed()
        val retried =
            assertInstanceOf<HistoricalStageAdvance.Advanced>(
                    SqliteHistoricalSnapshotStaging.advance(
                            stage,
                            scriptedBasis(),
                            HistoricalStageBudget.parse(4, 5).value(),
                        )
                        .value()
                )
                .receipt
        assertEquals(basis.snapshot.identity, retried.identity)
        assertEquals(HistoricalStageCompletion.READY, retried.completion)
        assertEquals(reusedFiles, retried.work.filesReused.value)
        assertEquals(reusedRows, retried.work.rowsReused.value)
        assertEquals(4 - reusedFiles, retried.work.filesCommitted.value)
        assertEquals(14 - reusedRows, retried.work.rowsCommitted.value)
        assertEquals(
            StoredRows(6, 4, listOf("contracts/Api.kt", "feature/Main.kt", "feature/Test.kt", "isolated/Api.kt")),
            storedRows(stage),
        )
    }

    private fun advance(
        stage: Path,
        basis: HistoricalCaptureBasis,
        effects: ConnectionScript,
        control: HistoricalStageControl = HistoricalStageControl.Uninterrupted,
    ) =
        SqliteHistoricalSnapshotStaging.advance(
            stage,
            basis,
            HistoricalStageBudget.parse(2, 5).value(),
            control,
            effects::open,
        )

    private fun effectFailure(result: Refinement<HistoricalStageAdvance, HistoricalStageFailure>) =
        assertInstanceOf<HistoricalStageFailure.Effect>(
            assertInstanceOf<Refinement.Rejected<HistoricalStageFailure>>(result).failure
        )

    private fun assertPrefix(
        receipt: HistoricalStageReceipt,
        basis: HistoricalCaptureBasis,
        files: Long,
        rows: Long,
        rolledBack: Long,
    ) {
        assertEquals(basis.snapshot.identity, receipt.identity)
        assertEquals(HistoricalStageCompletion.PENDING, receipt.completion)
        assertEquals(0L, receipt.work.filesReused.value)
        assertEquals(0L, receipt.work.rowsReused.value)
        assertEquals(files, receipt.work.filesCommitted.value)
        assertEquals(rows, receipt.work.rowsCommitted.value)
        assertEquals(rolledBack, receipt.work.rolledBackBatches.value)
        assertEquals(5L, receipt.work.maximumBatchRows.value)
    }

    private fun stage(): Path = directory.toRealPath().resolve("effects.stage.sqlite")

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

    private fun scriptedBasis(): HistoricalCaptureBasis {
        val snapshot = HistoricalUsageOracle.snapshot()
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

    private class SecondFileControl(private val stop: () -> HistoricalStageSignal) : HistoricalStageControl {
        private var calls = 0

        override fun beforeCommit(): HistoricalStageSignal {
            calls++
            assertTrue(calls <= 2, "Unexpected beforeCommit call")
            return if (calls == 1) HistoricalStageSignal.Continue else stop()
        }

        fun assertConsumed() = assertEquals(2, calls)
    }

    private enum class Operation {
        COMMIT,
        ROLLBACK,
        CLOSE,
    }

    private enum class Step(val operation: Operation) {
        COMMIT(Operation.COMMIT),
        COMMIT_FAIL_BEFORE(Operation.COMMIT),
        COMMIT_FAIL_AFTER(Operation.COMMIT),
        ROLLBACK(Operation.ROLLBACK),
        ROLLBACK_FAIL(Operation.ROLLBACK),
        CLOSE(Operation.CLOSE),
        CLOSE_FAIL(Operation.CLOSE),
    }

    private class ConnectionScript(private vararg val expected: Step) {
        private var opened = 0
        private var consumed = 0

        fun open(path: Path): Connection {
            assertEquals(0, opened++, "Only the opened stage receives this connection seam")
            val real = DriverManager.getConnection("jdbc:sqlite:$path")
            return object : Connection by real {
                override fun commit() {
                    val step = next(Operation.COMMIT)
                    if (step == Step.COMMIT_FAIL_BEFORE) throw SQLException("controlled failure before commit")
                    real.commit()
                    if (step == Step.COMMIT_FAIL_AFTER) throw SQLException("controlled failure after commit")
                }

                override fun rollback() {
                    if (next(Operation.ROLLBACK) == Step.ROLLBACK_FAIL)
                        throw SQLException("controlled rollback failure")
                    real.rollback()
                }

                override fun close() {
                    val step = next(Operation.CLOSE)
                    real.close()
                    if (step == Step.CLOSE_FAIL) throw SQLException("controlled close failure")
                }
            }
        }

        private fun next(operation: Operation): Step {
            assertTrue(consumed < expected.size, "Unexpected transaction effect: $operation")
            val step = expected[consumed++]
            assertEquals(step.operation, operation)
            return step
        }

        fun assertConsumed() {
            assertEquals(1, opened)
            assertEquals(expected.size, consumed)
        }
    }

    private data class StoredRows(val declarations: Long, val occurrences: Long, val progress: List<String>)

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
