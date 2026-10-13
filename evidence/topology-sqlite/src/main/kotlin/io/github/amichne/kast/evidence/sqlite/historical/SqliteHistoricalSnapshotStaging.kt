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
    ): Refinement<HistoricalStageAdvance, HistoricalStageFailure> =
        advance(stage, basis, budget, control, ::historicalWriteConnection)

    internal fun advance(
        stage: Path,
        basis: HistoricalCaptureBasis,
        budget: HistoricalStageBudget,
        control: HistoricalStageControl,
        openConnection: (Path) -> Connection,
    ): Refinement<HistoricalStageAdvance, HistoricalStageFailure> = historicalStageEffect {
        admitHistoricalPath(stage, mustExist = false).stage().orReturn {
            return@historicalStageEffect it
        }
        if (!Files.exists(stage, NOFOLLOW_LINKS)) initializeStage(stage, basis)
        val connection = openConnection(stage)
        var ownership = HistoricalStageConnectionOwnership.CLOSE
        try {
            connection.enableStageIntegrity()
            connection.autoCommit = false
            val admitted =
                connection.admitHistoricalStage(basis).orReturn {
                    return@historicalStageEffect it
                }
            val selected =
                planFiles(basis, budget, admitted).orReturn {
                    return@historicalStageEffect it
                }
            val run = HistoricalStageRun(connection, basis, admitted, selected)
            ownership = HistoricalStageConnectionOwnership.RUN
            run.advance(control)
        } finally {
            if (ownership == HistoricalStageConnectionOwnership.CLOSE) connection.close()
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

    private fun planFiles(
        basis: HistoricalCaptureBasis,
        budget: HistoricalStageBudget,
        admitted: HistoricalStageAdmission,
    ): Refinement<List<HistoricalStageFilePlan>, HistoricalStageFailure> {
        val selected = mutableListOf<HistoricalStageFilePlan>()
        for (file in basis.files.asSequence().drop(admitted.completedFiles.size).take(budget.files)) {
            when (val plan = HistoricalStageFilePlan.admit(basis.snapshot, file, budget)) {
                is Refinement.Refined -> selected += plan.value
                is Refinement.Rejected -> return plan
            }
        }
        return Refinement.Refined(selected)
    }
}

private enum class HistoricalStageConnectionOwnership {
    CLOSE,
    RUN,
}

private fun Connection.enableStageIntegrity() {
    createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
}
