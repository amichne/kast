package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import java.sql.Connection
import java.sql.ResultSet

internal fun Connection.readHistoricalSnapshot(): Refinement<HistoricalSnapshot, HistoricalStorageFailure> {
    val (header, digest) =
        readHistoricalHeader().orReturn {
            return it
        }
    val modules =
        readHistoricalModules().orReturn {
            return it
        }
    val dependencies =
        readHistoricalDependencies().orReturn {
            return it
        }
    val files =
        queryHistoricalOrdinal("SELECT * FROM historical_file ORDER BY ordinal", ::historicalFile).orReturn {
            return it
        }
    val declarations =
        queryHistoricalOrdinal("SELECT * FROM historical_declaration ORDER BY ordinal") { historicalDeclaration(it) }
            .orReturn {
                return it
            }
    val occurrences =
        queryHistoricalOrdinal("$HISTORICAL_OCCURRENCE_QUERY ORDER BY o.ordinal", ::historicalOccurrence).orReturn {
            return it
        }
    val coverage =
        readHistoricalCoverage().orReturn {
            return it
        }
    val snapshot =
        HistoricalSnapshotInput(header, modules, dependencies, files, declarations, occurrences, coverage)
            .admit()
            .storage()
            .orReturn {
                return it
            }
    val ordered =
        snapshot.files == files && snapshot.declarations == declarations && snapshot.occurrences == occurrences
    return if (snapshot.identity == digest && ordered) Refinement.Refined(snapshot)
    else Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
}

internal fun <T> Connection.queryHistoricalOrdinal(
    sql: String,
    read: (ResultSet) -> Refinement<T, HistoricalStorageFailure>,
): Refinement<List<T>, HistoricalStorageFailure> {
    var expected = 0L
    return queryHistorical(sql) { rows ->
        if (rows.getLong("ordinal") != expected++) Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        else read(rows)
    }
}
