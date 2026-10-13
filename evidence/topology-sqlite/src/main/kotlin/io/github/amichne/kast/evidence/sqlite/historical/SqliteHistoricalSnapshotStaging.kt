package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasis
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.sql.Connection

/** One explicit coordinator owns metadata admission, per-file commits, resumption, and publication. */
object SqliteHistoricalSnapshotStaging {
    fun advance(
        stage: Path,
        basis: HistoricalCaptureBasis,
        budget: HistoricalStageBudget,
        control: HistoricalStageControl = HistoricalStageControl.Uninterrupted,
    ): Refinement<HistoricalStageAdvance, HistoricalStageFailure> = historicalStageEffect {
        admitHistoricalPath(stage, mustExist = false).stage().orReturn {
            return@historicalStageEffect it
        }
        if (!Files.exists(stage, NOFOLLOW_LINKS)) initializeStage(stage, basis)
        historicalWriteConnection(stage).use { connection ->
            connection.enableStageIntegrity()
            connection.autoCommit = false
            try {
                connection.admitHistoricalStage(basis).flatMap { admitted ->
                    advanceFiles(connection, basis, budget, control, admitted)
                }
            } finally {
                connection.rollback()
            }
        }
    }

    fun publish(
        stage: Path,
        destination: Path,
        basis: HistoricalCaptureBasis,
    ): Refinement<HistoricalSqlitePublication, HistoricalStageFailure> = historicalStageEffect {
        if (stage == destination)
            return@historicalStageEffect Refinement.Rejected(
                HistoricalStageFailure.Storage(HistoricalStorageFailure.PATH_REJECTED)
            )
        admitHistoricalPath(stage, mustExist = true).stage().orReturn {
            return@historicalStageEffect it
        }
        historicalWriteConnection(stage).use { connection ->
            connection.enableStageIntegrity()
            connection.autoCommit = false
            try {
                val admitted =
                    connection.admitHistoricalStage(basis).orReturn {
                        return@historicalStageEffect it
                    }
                if (admitted.completedFiles.size != basis.files.size)
                    return@historicalStageEffect Refinement.Rejected(HistoricalStageFailure.Incomplete)
                SqliteHistoricalSnapshotStore.publishPrepared(
                        destination,
                        basis.snapshot.identity,
                        basis.snapshot.historicalRowCount(),
                        extraVerificationRows = admitted.examinedRows,
                    ) { temporary ->
                        Files.copy(stage, temporary, REPLACE_EXISTING)
                        historicalWriteConnection(temporary).use { candidate ->
                            candidate.enableStageIntegrity()
                            candidate.finalizeHistoricalStage(basis.snapshot.identity)
                        }
                    }
                    .stage()
            } finally {
                connection.rollback()
            }
        }
    }

    private fun initializeStage(stage: Path, basis: HistoricalCaptureBasis) {
        val temporary = Files.createTempFile(stage.parent, ".kast-stage-", ".sqlite")
        try {
            historicalWriteConnection(temporary).use { it.initializeHistoricalStage(basis.snapshot) }
            Files.move(temporary, stage, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun advanceFiles(
        connection: Connection,
        basis: HistoricalCaptureBasis,
        budget: HistoricalStageBudget,
        control: HistoricalStageControl,
        admitted: HistoricalStageAdmission,
    ): Refinement<HistoricalStageAdvance, HistoricalStageFailure> {
        val work = HistoricalStageRunWork()
        for (file in basis.files.drop(admitted.completedFiles.size).take(budget.files)) {
            val batch =
                historicalStageBatch(basis.snapshot, file, budget).orReturn {
                    return it
                }
            work.maximumBatchRows = maxOf(work.maximumBatchRows, batch.rowCount)
            work.basisFactRowsExamined += batch.basisFactRowsExamined
            when (val signal = connection.commitStageBatch(batch, control)) {
                HistoricalStageSignal.Continue -> {
                    work.committedFiles++
                    work.committedRows += batch.rowCount
                }
                is HistoricalStageSignal.Stop ->
                    return Refinement.Refined(
                        HistoricalStageAdvance.Stopped(
                            signal.cause,
                            receipt(basis, admitted, work, 1),
                        )
                    )
            }
        }
        return Refinement.Refined(HistoricalStageAdvance.Advanced(receipt(basis, admitted, work, 0)))
    }

    private fun receipt(
        basis: HistoricalCaptureBasis,
        admitted: HistoricalStageAdmission,
        work: HistoricalStageRunWork,
        rolledBack: Long,
    ): HistoricalStageReceipt {
        val completion =
            if (admitted.completedFiles.size + work.committedFiles == basis.files.size.toLong())
                HistoricalStageCompletion.READY
            else HistoricalStageCompletion.PENDING
        return HistoricalStageReceipt(
            basis.snapshot.identity,
            completion,
            HistoricalStageWork(
                historicalCount(admitted.completedFiles.size.toLong()),
                historicalCount(admitted.reusedRows),
                historicalCount(work.committedFiles),
                historicalCount(work.committedRows),
                historicalCount(rolledBack),
                historicalCount(work.maximumBatchRows),
                historicalCount(admitted.examinedRows),
                historicalCount(basis.snapshot.historicalRowCount()),
                historicalCount(work.basisFactRowsExamined),
            ),
        )
    }
}

private class HistoricalStageRunWork {
    var committedFiles = 0L
    var committedRows = 0L
    var maximumBatchRows = 0L
    var basisFactRowsExamined = 0L
}

private enum class HistoricalBatchResolution {
    COMMITTED,
    ROLLBACK,
}

private fun Connection.commitStageBatch(
    batch: HistoricalStageFileBatch,
    control: HistoricalStageControl,
): HistoricalStageSignal {
    var resolution = HistoricalBatchResolution.ROLLBACK
    try {
        writeHistoricalStageBatch(batch)
        val signal = control.beforeCommit()
        if (signal == HistoricalStageSignal.Continue) {
            commit()
            resolution = HistoricalBatchResolution.COMMITTED
        }
        return signal
    } finally {
        if (resolution == HistoricalBatchResolution.ROLLBACK) rollback()
    }
}

private fun Connection.enableStageIntegrity() {
    createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
}
