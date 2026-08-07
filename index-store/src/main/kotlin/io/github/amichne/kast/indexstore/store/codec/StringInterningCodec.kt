package io.github.amichne.kast.indexstore.store.codec

import io.github.amichne.kast.indexstore.store.AttachedSqliteDatabase
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

internal enum class StringInterningDomain(
    internal val tableName: String,
    internal val idColumn: String,
    internal val valueColumn: String,
) {
    PATH_PREFIX("path_prefixes", "prefix_id", "dir_path"),
    FQ_NAME("fq_names", "fq_id", "fq_name"),
}

internal class InternedStringReadId internal constructor(internal val value: Int)

internal sealed interface InternedStringReadIdResolution {
    data class Resolved(val id: InternedStringReadId) : InternedStringReadIdResolution

    data object Unavailable : InternedStringReadIdResolution
}

internal sealed interface ReadOnlyInterningAliasFailure {
    data class NonPositiveSourceId(
        val domain: StringInterningDomain,
        val value: Int,
    ) : ReadOnlyInterningAliasFailure

    data class MissingSourceValue(
        val domain: StringInterningDomain,
        val sourceId: Int,
    ) : ReadOnlyInterningAliasFailure
}

internal sealed interface ReadOnlyInterningAliasResolution {
    data object Loaded : ReadOnlyInterningAliasResolution

    data class Rejected(
        val failure: ReadOnlyInterningAliasFailure,
    ) : ReadOnlyInterningAliasResolution
}

internal class StringInterningCodec(
    private val domain: StringInterningDomain,
) {
    private val tableName = domain.tableName
    private val idColumn = domain.idColumn
    private val valueColumn = domain.valueColumn
    @Volatile
    private var valueToId = ConcurrentHashMap<String, Int>()

    @Volatile
    private var idToValue = ConcurrentHashMap<Int, String>()

    @Volatile
    private var readValueToId = ConcurrentHashMap<String, Int>()

    @Volatile
    private var readIdToValue = ConcurrentHashMap<Int, String>()

    @Volatile
    private var loaded = false

    /**
     * Hydrates the cache once for the current connection lifecycle.
     */
    fun loadAll(conn: Connection) {
        if (loaded) return
        synchronized(this) {
            if (!loaded) reloadAllLocked(conn)
        }
    }

    fun reloadAll(conn: Connection) {
        synchronized(this) {
            loaded = false
            reloadAllLocked(conn)
        }
    }

    private fun reloadAllLocked(conn: Connection) {
        val rowCount = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        val loadedValues = ConcurrentHashMap<String, Int>(rowCount)
        val loadedIds = ConcurrentHashMap<Int, String>(rowCount)
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT $idColumn, $valueColumn FROM $tableName").use { rs ->
                while (rs.next()) {
                    val id = rs.getInt(1)
                    val value = rs.getString(2)
                    loadedValues[value] = id
                    loadedIds[id] = value
                }
            }
        }
        valueToId = loadedValues
        idToValue = loadedIds
        readValueToId = ConcurrentHashMap(loadedValues)
        readIdToValue = ConcurrentHashMap(loadedIds)
        loaded = true
    }

    /**
     * Proof transition:
     * `(Connection, AttachedSqliteDatabase) -> ReadOnlyInterningAliasResolution`.
     *
     * Loaded proves every attached positive source ID has a value and has been
     * mapped into the effective negative read namespace without mutating the
     * writable namespace. Corrupt rows are finite
     * [ReadOnlyInterningAliasFailure] data. Raw columns are read only at the
     * SQLite boundary.
     */
    fun loadReadOnlyAliases(
        conn: Connection,
        database: AttachedSqliteDatabase,
    ): ReadOnlyInterningAliasResolution {
        ensureLoaded(conn)
        val values = ConcurrentHashMap(readValueToId)
        val ids = ConcurrentHashMap(readIdToValue)
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT $idColumn, $valueColumn FROM $database.$tableName").use { rows ->
                while (rows.next()) {
                    val sourceId = rows.getInt(1)
                    if (sourceId <= 0) {
                        return ReadOnlyInterningAliasResolution.Rejected(
                            ReadOnlyInterningAliasFailure.NonPositiveSourceId(domain, sourceId),
                        )
                    }
                    val value = rows.getString(2)
                        ?: return ReadOnlyInterningAliasResolution.Rejected(
                            ReadOnlyInterningAliasFailure.MissingSourceValue(domain, sourceId),
                        )
                    if (!values.containsKey(value)) {
                        val effectiveId = Math.negateExact(sourceId)
                        values[value] = effectiveId
                        ids[effectiveId] = value
                    }
                }
            }
        }
        readValueToId = values
        readIdToValue = ids
        return ReadOnlyInterningAliasResolution.Loaded
    }

    fun getOrCreate(
        conn: Connection,
        value: String,
    ): Int {
        ensureLoaded(conn)
        valueToId[value]?.let { return it }
        conn.prepareStatement("INSERT OR IGNORE INTO $tableName ($valueColumn) VALUES (?)").use { stmt ->
            stmt.setString(1, value)
            stmt.executeUpdate()
        }
        val id = selectId(conn, value)
        valueToId[value] = id
        idToValue[id] = value
        readValueToId[value] = id
        readIdToValue[id] = value
        return id
    }

    fun batchEnsure(
        conn: Connection,
        values: Set<String>,
    ) {
        ensureLoaded(conn)
        val missingValues = ArrayList<String>(values.size)
        values.filterTo(missingValues) { !valueToId.containsKey(it) }
        if (missingValues.isEmpty()) return
        conn.prepareStatement("INSERT OR IGNORE INTO $tableName ($valueColumn) VALUES (?)").use { stmt ->
            for (value in missingValues) {
                stmt.setString(1, value)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        loadValues(conn, missingValues)
    }

    fun resolve(id: Int): String =
        readIdToValue[id] ?: throw IllegalStateException("Missing interned string in $tableName for $idColumn=$id")

    fun idFor(value: String): Int? = valueToId[value]

    /**
     * Proof transition: `String -> InternedStringReadIdResolution`.
     *
     * A resolved ID proves that the value is interned by the workspace or its
     * attached repository read authority. Absence is explicit; the raw SQLite
     * ID is extracted only while constructing or binding a query.
     */
    fun idForRead(value: String): InternedStringReadIdResolution = readValueToId[value]
        ?.let(::InternedStringReadId)
        ?.let(InternedStringReadIdResolution::Resolved)
        ?: InternedStringReadIdResolution.Unavailable

    private fun ensureLoaded(conn: Connection) {
        if (!loaded) loadAll(conn)
    }

    private fun loadValues(
        conn: Connection,
        values: List<String>,
    ) {
        var start = 0
        while (start < values.size) {
            val end = minOf(start + SQLITE_QUERY_BATCH_SIZE, values.size)
            val batch = values.subList(start, end)
            val placeholders = batch.joinToString(",") { "?" }
            conn.prepareStatement(
                "SELECT $idColumn, $valueColumn FROM $tableName WHERE $valueColumn IN ($placeholders)",
            ).use { stmt ->
                batch.forEachIndexed { index, value -> stmt.setString(index + 1, value) }
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt(1)
                        val value = rs.getString(2)
                        valueToId[value] = id
                        idToValue[id] = value
                        readValueToId[value] = id
                        readIdToValue[id] = value
                    }
                }
            }
            start = end
        }
        check(values.all(valueToId::containsKey)) { "Failed to load newly interned values from $tableName" }
    }

    private fun selectId(
        conn: Connection,
        value: String,
    ): Int =
        conn.prepareStatement("SELECT $idColumn FROM $tableName WHERE $valueColumn = ?").use { stmt ->
            stmt.setString(1, value)
            val rs = stmt.executeQuery()
            if (rs.next()) {
                rs.getInt(1)
            } else {
                throw IllegalStateException("Failed to intern value in $tableName: $value")
            }
        }

    private companion object {
        const val SQLITE_QUERY_BATCH_SIZE = 900
    }
}
