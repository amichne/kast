package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationId
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrence
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types

internal val historicalStageSchema =
    listOf(
        """CREATE TABLE historical_stage_progress (
        file TEXT PRIMARY KEY REFERENCES historical_file(path)
    ) STRICT""",
        """CREATE TABLE historical_stage_occurrence (
        ordinal INTEGER PRIMARY KEY CHECK(ordinal >= 0),
        source_file TEXT NOT NULL REFERENCES historical_file(path),
        start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL,
        target_compiler TEXT NOT NULL, target_file TEXT NOT NULL,
        target_start_offset INTEGER NOT NULL, target_end_offset INTEGER NOT NULL,
        owner_kind TEXT NOT NULL, owner_compiler TEXT, owner_file TEXT,
        owner_start_offset INTEGER, owner_end_offset INTEGER, owner_detail TEXT,
        kind TEXT NOT NULL, context TEXT NOT NULL,
        CHECK((owner_kind = 'DECLARATION' AND owner_compiler IS NOT NULL AND owner_file IS NOT NULL
            AND owner_start_offset IS NOT NULL AND owner_end_offset IS NOT NULL AND owner_detail IS NULL)
            OR (owner_kind IN ('FILE_SCOPED', 'UNAVAILABLE') AND owner_compiler IS NULL
            AND owner_file IS NULL AND owner_start_offset IS NULL AND owner_end_offset IS NULL AND owner_detail IS NOT NULL))
    ) STRICT""",
    )

internal data class HistoricalStageFileBatch(
    val file: HistoricalFile,
    val declarations: List<IndexedValue<HistoricalDeclaration>>,
    val occurrences: List<IndexedValue<HistoricalOccurrence>>,
    val basisFactRowsExamined: Long,
) {
    val rowCount: Long
        get() = declarations.size.toLong() + occurrences.size + 1
}

internal fun historicalStageBatch(
    snapshot: HistoricalSnapshot,
    file: HistoricalFile,
    budget: HistoricalStageBudget,
): Refinement<HistoricalStageFileBatch, HistoricalStageFailure> {
    var examined = 0L
    val rows =
        snapshot.declarations
            .count {
                examined++
                it.id.file == file.path
            }
            .toLong() +
            snapshot.occurrences.count {
                examined++
                it.sourceFile == file.path
            } +
            1
    if (rows > budget.rowsPerFile) return Refinement.Rejected(HistoricalStageFailure.FileCapacityExceeded)
    val declarations =
        snapshot.declarations.withIndex().filter {
            examined++
            it.value.id.file == file.path
        }
    val occurrences =
        snapshot.occurrences.withIndex().filter {
            examined++
            it.value.sourceFile == file.path
        }
    return Refinement.Refined(HistoricalStageFileBatch(file, declarations, occurrences, examined))
}

internal fun Connection.initializeHistoricalStage(snapshot: HistoricalSnapshot) {
    initializeHistoricalSchema()
    createStatement().use { statement -> historicalStageSchema.forEach(statement::execute) }
    autoCommit = false
    try {
        writeHistoricalHeader(snapshot)
        writeHistoricalModules(snapshot)
        writeHistoricalDependencies(snapshot)
        writeHistoricalFiles(snapshot)
        writeHistoricalCoverage(snapshot)
        commit()
    } finally {
        if (!autoCommit) rollback()
        autoCommit = true
    }
}

internal fun Connection.writeHistoricalStageBatch(batch: HistoricalStageFileBatch) {
    writeHistoricalDeclarationRows(batch.declarations)
    prepareStatement("INSERT INTO historical_stage_occurrence VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .use { statement ->
            batch.occurrences.forEach { (ordinal, occurrence) -> statement.writeStageOccurrence(ordinal, occurrence) }
        }
    prepareStatement("INSERT INTO historical_stage_progress VALUES (?)").use { statement ->
        statement.setString(1, batch.file.path.value)
        statement.executeUpdate()
    }
}

private fun PreparedStatement.writeStageOccurrence(ordinal: Int, value: HistoricalOccurrence) {
    var parameter = 1
    setInt(parameter++, ordinal)
    setString(parameter++, value.sourceFile.value)
    setInt(parameter++, value.range.startInclusive)
    setInt(parameter++, value.range.endExclusive)
    parameter = stageIdentity(parameter, value.target)
    when (val owner = value.owner) {
        is HistoricalOccurrenceOwner.Declaration -> {
            setString(parameter++, "DECLARATION")
            parameter = stageIdentity(parameter, owner.id)
            setNull(parameter++, Types.VARCHAR)
        }
        is HistoricalOccurrenceOwner.FileScoped -> {
            setString(parameter++, "FILE_SCOPED")
            repeat(DECLARATION_ID_COLUMNS) { setNull(parameter++, Types.VARCHAR) }
            setString(parameter++, owner.context.name)
        }
        is HistoricalOccurrenceOwner.Unavailable -> {
            setString(parameter++, "UNAVAILABLE")
            repeat(DECLARATION_ID_COLUMNS) { setNull(parameter++, Types.VARCHAR) }
            setString(parameter++, owner.cause.name)
        }
    }
    setString(parameter++, value.kind.name)
    setString(parameter, value.context.name)
    executeUpdate()
}

private fun PreparedStatement.stageIdentity(start: Int, id: HistoricalDeclarationId): Int {
    var parameter = start
    setString(parameter++, id.compiler.value)
    setString(parameter++, id.file.value)
    setInt(parameter++, id.range.startInclusive)
    setInt(parameter++, id.range.endExclusive)
    return parameter
}

internal fun Connection.finalizeHistoricalStage(
    identity: HistoricalDigest
): Refinement<Unit, HistoricalStorageFailure> {
    autoCommit = false
    try {
        createStatement().use { statement ->
            statement.executeUpdate(
                """INSERT INTO historical_occurrence
                SELECT o.ordinal, o.source_file, o.start_offset, o.end_offset, t.ordinal,
                o.owner_kind, d.ordinal, o.owner_detail, o.kind, o.context
                FROM historical_stage_occurrence o JOIN historical_declaration t
                ON t.compiler = o.target_compiler AND t.file = o.target_file
                AND t.start_offset = o.target_start_offset AND t.end_offset = o.target_end_offset
                LEFT JOIN historical_declaration d
                ON d.compiler = o.owner_compiler AND d.file = o.owner_file
                AND d.start_offset = o.owner_start_offset AND d.end_offset = o.owner_end_offset"""
            )
            statement.execute("DROP TABLE historical_stage_occurrence")
            statement.execute("DROP TABLE historical_stage_progress")
        }
        val restored =
            readHistoricalSnapshot().orReturn {
                return it
            }
        if (restored.identity != identity) return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        commit()
        return Refinement.Refined(Unit)
    } finally {
        if (!autoCommit) rollback()
        autoCommit = true
    }
}

private const val DECLARATION_ID_COLUMNS = 4
