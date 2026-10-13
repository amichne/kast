package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationId
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationReference
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalInventoryPage
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalOpenWork
import io.github.amichne.kast.topology.contract.historical.HistoricalPageEnd
import io.github.amichne.kast.topology.contract.historical.HistoricalPageRequest
import io.github.amichne.kast.topology.contract.historical.HistoricalPageWork
import io.github.amichne.kast.topology.contract.historical.HistoricalReadCount
import io.github.amichne.kast.topology.contract.historical.HistoricalReadFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalReadResult
import io.github.amichne.kast.topology.contract.historical.HistoricalRowOrdinal
import io.github.amichne.kast.topology.contract.historical.HistoricalRowPosition
import io.github.amichne.kast.topology.contract.historical.HistoricalSelectedDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInventory
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotReader
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageEnd
import io.github.amichne.kast.topology.contract.historical.HistoricalUsagePage
import io.github.amichne.kast.topology.contract.historical.HistoricalUsagePosition
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageQuery
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageRow
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/** Logical domain rows held by admission collections. This is not a JVM heap measurement. */
data class HistoricalSqliteRetention(
    val metadataRows: HistoricalReadCount,
    val maximumFactRows: HistoricalReadCount,
)

/** The retained capability reads the admitted artifact only. Pages use stored canonical ordinals. */
class SqliteHistoricalSnapshotReader
internal constructor(
    private val connection: Connection,
    private val image: HistoricalPrivateReaderImage,
    override val inventory: HistoricalSnapshotInventory,
    override val openWork: HistoricalOpenWork,
    val admissionRetention: HistoricalSqliteRetention,
    val admissionEndpointIdentifiers: HistoricalReadCount,
) : HistoricalSnapshotReader {
    override fun files(request: HistoricalPageRequest): HistoricalReadResult<HistoricalInventoryPage<HistoricalFile>> =
        page("historical_file", HistoricalFactFamily.FILES, request, ::historicalFile)

    override fun declarations(
        request: HistoricalPageRequest
    ): HistoricalReadResult<HistoricalInventoryPage<HistoricalDeclaration>> =
        page("historical_declaration", HistoricalFactFamily.DECLARATIONS, request) { historicalDeclaration(it) }

    override fun select(id: HistoricalDeclarationId): HistoricalReadResult<HistoricalSelectedDeclaration> =
        historicalEffect {
            exactDeclaration(id).flatMap { declaration ->
                work(1, 1).map {
                    HistoricalSelectedDeclaration(
                        HistoricalDeclarationReference.fromStored(inventory.identity, declaration),
                        declaration,
                        it,
                    )
                }
            }
        }
        .asRead()

    override fun usage(query: HistoricalUsageQuery): HistoricalReadResult<HistoricalUsagePage> {
        if (query.snapshot != inventory.identity)
            return HistoricalReadResult.Rejected(HistoricalReadFailure.SNAPSHOT_MISMATCH)
        if (!query.usesInventory(inventory))
            return HistoricalReadResult.Rejected(HistoricalReadFailure.QUERY_INVENTORY_MISMATCH)
        return historicalEffect {
            admitTargets(query).flatMap { targetRows ->
                admitUsageCursor(query).flatMap { cursorRows ->
                    readUsage(query, HistoricalOrdinalComparison.AFTER, query.pageSize.value + 1).flatMap { values ->
                        val visible = values.take(query.pageSize.value)
                        val end =
                            if (values.size > visible.size)
                                HistoricalUsageEnd.More(query.cursorAfter(visible.last().ordinal))
                            else HistoricalUsageEnd.Exhausted
                        work(targetRows + cursorRows + values.size, visible.size.toLong()).map {
                            HistoricalUsagePage(query, visible, end, it)
                        }
                    }
                }
            }
        }
            .asRead()
    }

    private fun admitUsageCursor(query: HistoricalUsageQuery): Refinement<Long, HistoricalStorageFailure> =
        when (query.position) {
            HistoricalUsagePosition.First -> Refinement.Refined(0L)
            is HistoricalUsagePosition.After ->
                readUsage(query, HistoricalOrdinalComparison.EXACT, 1).flatMap {
                    if (it.isEmpty()) Refinement.Rejected(HistoricalStorageFailure.CURSOR_MISMATCH)
                    else Refinement.Refined(it.size.toLong())
                }
        }

    private fun readUsage(
        query: HistoricalUsageQuery,
        comparison: HistoricalOrdinalComparison,
        limit: Int,
    ): Refinement<List<HistoricalUsageRow>, HistoricalStorageFailure> {
        val sourcePlaceholders = query.sourceModules.joinToString(",") { "?" }
        val targetPredicates =
            query.targets.joinToString(" OR ") {
                "(t.compiler = ? AND t.file = ? AND t.start_offset = ? AND t.end_offset = ?)"
            }
        val operator =
            when (comparison) {
                HistoricalOrdinalComparison.AFTER -> ">"
                HistoricalOrdinalComparison.EXACT -> "="
            }
        val sql =
            HISTORICAL_OCCURRENCE_QUERY.replace(
                "SELECT o.*,",
                "SELECT o.*, sf.module AS source_module, tf.module AS target_module,",
            ) +
                " JOIN historical_file sf ON sf.path = o.source_file" +
                " JOIN historical_file tf ON tf.path = t.file" +
                " WHERE o.ordinal $operator ? AND sf.module IN ($sourcePlaceholders) AND ($targetPredicates) ORDER BY o.ordinal LIMIT ?"
        val after =
            when (val position = query.position) {
                HistoricalUsagePosition.First -> -1L
                is HistoricalUsagePosition.After -> position.cursor.after.value
            }
        return connection.prepareStatement(sql).use { statement ->
            var parameter = 1
            statement.setLong(parameter++, after)
            query.sourceModules.forEach { statement.setString(parameter++, it.value) }
            query.targets.forEach { parameter = statement.bindDeclaration(parameter, it.declaration) }
            statement.setInt(parameter, limit)
            statement.executeQuery().use { rows -> rows.readHistoricalRows(::usageRow) }
        }
    }

    override fun close() {
        try {
            connection.close()
        } finally {
            image.close()
        }
    }

    private fun admitTargets(query: HistoricalUsageQuery): Refinement<Long, HistoricalStorageFailure> {
        var examined = 0L
        for (reference in query.targets) {
            when (val selection = exactDeclaration(reference.declaration)) {
                is Refinement.Refined -> examined++
                is Refinement.Rejected -> return selection
            }
        }
        return Refinement.Refined(examined)
    }

    private fun usageRow(rows: ResultSet): Refinement<HistoricalUsageRow, HistoricalStorageFailure> =
        HistoricalRowOrdinal.parse(rows.getLong("ordinal")).storage().flatMap { ordinal ->
            historicalModuleId(rows, "source_module").flatMap { source ->
                historicalModuleId(rows, "target_module").flatMap { target ->
                    historicalOccurrence(rows).map { HistoricalUsageRow(ordinal, source, target, it) }
                }
            }
        }

    private fun exactDeclaration(
        id: HistoricalDeclarationId
    ): Refinement<HistoricalDeclaration, HistoricalStorageFailure> =
        connection
            .prepareStatement(
                """SELECT * FROM historical_declaration
                    WHERE compiler = ? AND file = ? AND start_offset = ? AND end_offset = ?"""
            )
            .use { statement ->
                statement.bindDeclaration(1, id)
                statement.executeQuery().use { rows ->
                    if (rows.next()) historicalDeclaration(rows)
                    else Refinement.Rejected(HistoricalStorageFailure.DECLARATION_NOT_FOUND)
                }
            }

    private fun <T> page(
        table: String,
        family: HistoricalFactFamily,
        request: HistoricalPageRequest,
        read: (ResultSet) -> Refinement<T, HistoricalStorageFailure>,
    ): HistoricalReadResult<HistoricalInventoryPage<T>> {
        val after =
            when (val position = request.position) {
                HistoricalRowPosition.First -> -1L
                is HistoricalRowPosition.After -> {
                    if (position.snapshot != inventory.identity)
                        return HistoricalReadResult.Rejected(HistoricalReadFailure.SNAPSHOT_MISMATCH)
                    if (position.family != family)
                        return HistoricalReadResult.Rejected(HistoricalReadFailure.CURSOR_MISMATCH)
                    position.ordinal.value
                }
            }
        return historicalEffect {
            admitInventoryCursor(table, after).flatMap { cursorRows ->
                val found =
                    connection
                        .prepareStatement("SELECT * FROM $table WHERE ordinal > ? ORDER BY ordinal LIMIT ?")
                        .use { statement ->
                            statement.setLong(1, after)
                            statement.setInt(2, request.size.value + 1)
                            statement.executeQuery().use { rows ->
                                rows.readHistoricalRows { row ->
                                    HistoricalRowOrdinal.parse(row.getLong("ordinal")).storage().flatMap { ordinal ->
                                        read(row).map { ordinal to it }
                                    }
                                }
                            }
                        }
                found.flatMap { values ->
                    val visible = values.take(request.size.value)
                    val end =
                        if (values.size > visible.size)
                            HistoricalPageEnd.More(
                                HistoricalRowPosition.After(inventory.identity, family, visible.last().first)
                            )
                        else HistoricalPageEnd.Exhausted
                    work(cursorRows + values.size, visible.size.toLong()).map {
                        HistoricalInventoryPage(visible.map { it.second }, end, it)
                    }
                }
            }
        }
            .asRead()
    }

    private fun admitInventoryCursor(table: String, after: Long): Refinement<Long, HistoricalStorageFailure> {
        if (after == -1L) return Refinement.Refined(0L)
        return connection.prepareStatement("SELECT ordinal FROM $table WHERE ordinal = ?").use { statement ->
            statement.setLong(1, after)
            statement.executeQuery().use { rows ->
                if (rows.next()) Refinement.Refined(1L)
                else Refinement.Rejected(HistoricalStorageFailure.CURSOR_MISMATCH)
            }
        }
    }

    private fun historicalModuleId(
        rows: ResultSet,
        column: String,
    ): Refinement<HistoricalModuleId, HistoricalStorageFailure> =
        historicalText(rows, column).flatMap { HistoricalModuleId.parse(it.value).storage() }
}

private enum class HistoricalOrdinalComparison {
    AFTER,
    EXACT,
}

private fun PreparedStatement.bindDeclaration(start: Int, id: HistoricalDeclarationId): Int {
    var parameter = start
    setString(parameter++, id.compiler.value)
    setString(parameter++, id.file.value)
    setInt(parameter++, id.range.startInclusive)
    setInt(parameter++, id.range.endExclusive)
    return parameter
}

private fun work(examined: Long, returned: Long): Refinement<HistoricalPageWork, HistoricalStorageFailure> =
    HistoricalReadCount.parse(examined).storage().flatMap { seen ->
        HistoricalReadCount.parse(returned).storage().map { HistoricalPageWork(seen, it) }
    }

private fun <T> Refinement<T, HistoricalStorageFailure>.asRead(): HistoricalReadResult<T> =
    when (this) {
        is Refinement.Refined -> HistoricalReadResult.Read(value)
        is Refinement.Rejected ->
            HistoricalReadResult.Rejected(
                when (failure) {
                    HistoricalStorageFailure.PATH_REJECTED,
                    HistoricalStorageFailure.STORAGE_UNAVAILABLE,
                    HistoricalStorageFailure.ATOMIC_PUBLICATION_UNAVAILABLE -> HistoricalReadFailure.STORAGE_UNAVAILABLE
                    HistoricalStorageFailure.SCHEMA_REJECTED -> HistoricalReadFailure.SCHEMA_UNSUPPORTED
                    HistoricalStorageFailure.PROFILE_REJECTED -> HistoricalReadFailure.PROFILE_UNSUPPORTED
                    HistoricalStorageFailure.CONTENT_REJECTED -> HistoricalReadFailure.CORRUPT_ARTIFACT
                    HistoricalStorageFailure.CAPACITY_EXCEEDED -> HistoricalReadFailure.CAPACITY_EXCEEDED
                    HistoricalStorageFailure.DECLARATION_NOT_FOUND -> HistoricalReadFailure.DECLARATION_NOT_FOUND
                    HistoricalStorageFailure.CURSOR_MISMATCH -> HistoricalReadFailure.CURSOR_MISMATCH
                }
            )
    }
