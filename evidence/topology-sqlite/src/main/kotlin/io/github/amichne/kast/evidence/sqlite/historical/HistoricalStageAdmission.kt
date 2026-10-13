package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalCaptureBasis
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.sql.Connection
import java.sql.ResultSet

internal data class HistoricalStageAdmission(
    val completedFiles: List<WorkspaceSourcePath>,
    val reusedRows: Long,
    val examinedRows: Long,
)

internal fun Connection.admitHistoricalStage(
    basis: HistoricalCaptureBasis
): Refinement<HistoricalStageAdmission, HistoricalStageFailure> {
    examineHistoricalSchema(historicalSchema + historicalStageSchema).stage().orReturn {
        return it
    }
    val snapshot = basis.snapshot
    admitStageMetadata(snapshot).orReturn {
        return it
    }
    val completed =
        queryHistorical("SELECT file FROM historical_stage_progress") { rows ->
                WorkspaceSourcePath.parse(rows.getString(1).orEmpty()).storage()
            }
            .stage()
            .orReturn {
                return it
            }
            .sorted()
    if (completed != snapshot.files.take(completed.size).map { it.path })
        return Refinement.Rejected(HistoricalStageFailure.ContentRejected)
    val completedSet = completed.toSet()
    val declarations =
        compareStageRows(
                "SELECT * FROM historical_declaration ORDER BY ordinal",
                snapshot.declarations.withIndex().asSequence().filter { it.value.id.file in completedSet },
                { historicalDeclaration(it) },
            )
            .orReturn {
                return it
            }
    val occurrences =
        compareStageRows(
                "SELECT * FROM historical_stage_occurrence ORDER BY ordinal",
                snapshot.occurrences.withIndex().asSequence().filter { it.value.sourceFile in completedSet },
                ::historicalOccurrence,
            )
            .orReturn {
                return it
            }
    val publishedOccurrences =
        queryHistorical("SELECT ordinal FROM historical_occurrence LIMIT 1") {
                Refinement.Refined(it.getLong(1))
            }
            .stage()
            .orReturn {
                return it
            }
    if (publishedOccurrences.isNotEmpty()) return Refinement.Rejected(HistoricalStageFailure.ContentRejected)
    val reused = declarations + occurrences + completed.size
    return Refinement.Refined(HistoricalStageAdmission(completed, reused, snapshot.stageMetadataRows() + reused))
}

private fun Connection.admitStageMetadata(snapshot: HistoricalSnapshot): Refinement<Unit, HistoricalStageFailure> {
    val header =
        readHistoricalHeader().stage().orReturn {
            return it
        }
    if (header.first != snapshot.header || header.second != snapshot.identity)
        return Refinement.Rejected(HistoricalStageFailure.BasisMismatch)
    val modules =
        readHistoricalModules()
            .stage()
            .orReturn {
                return it
            }
            .sortedBy { it.id }
    val dependencies =
        readHistoricalDependencies()
            .stage()
            .orReturn {
                return it
            }
            .sortedWith(compareBy({ it.source }, { it.target }))
    val files =
        queryHistoricalOrdinal("SELECT * FROM historical_file ORDER BY ordinal", ::historicalFile).stage().orReturn {
            return it
        }
    val coverage =
        readHistoricalCoverage().stage().orReturn {
            return it
        }
    val modelMatches = modules == snapshot.modules && dependencies == snapshot.dependencies
    if (!modelMatches || files != snapshot.files || coverage != snapshot.coverage)
        return Refinement.Rejected(HistoricalStageFailure.BasisMismatch)
    return Refinement.Refined(Unit)
}

private fun <T> Connection.compareStageRows(
    sql: String,
    expected: Sequence<IndexedValue<T>>,
    parse: (ResultSet) -> Refinement<T, HistoricalStorageFailure>,
): Refinement<Long, HistoricalStageFailure> =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> rows.compareHistoricalStageRows(expected.iterator(), parse) }
    }

private fun <T> ResultSet.compareHistoricalStageRows(
    expected: Iterator<IndexedValue<T>>,
    parse: (ResultSet) -> Refinement<T, HistoricalStorageFailure>,
): Refinement<Long, HistoricalStageFailure> {
    var examined = 0L
    while (next()) {
        if (!expected.hasNext()) return Refinement.Rejected(HistoricalStageFailure.ContentRejected)
        val value = expected.next()
        val parsed =
            parse(this).stage().orReturn {
                return it
            }
        if (getLong("ordinal") != value.index.toLong() || parsed != value.value)
            return Refinement.Rejected(HistoricalStageFailure.ContentRejected)
        examined++
    }
    return if (expected.hasNext()) Refinement.Rejected(HistoricalStageFailure.ContentRejected)
    else Refinement.Refined(examined)
}

internal fun HistoricalSnapshot.stageMetadataRows(): Long = historicalRowCount() - declarations.size - occurrences.size
