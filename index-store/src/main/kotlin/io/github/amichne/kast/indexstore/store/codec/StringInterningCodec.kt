package io.github.amichne.kast.indexstore.store.codec

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

internal class StringInterningCodec(
    private val tableName: String,
    private val idColumn: String,
    private val valueColumn: String,
) {
    @Volatile
    private var valueToId = ConcurrentHashMap<String, Int>()

    @Volatile
    private var idToValue = ConcurrentHashMap<Int, String>()

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
        loaded = true
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
        idToValue[id] ?: throw IllegalStateException("Missing interned string in $tableName for $idColumn=$id")

    fun resolveOrNull(id: Int): String? = idToValue[id]

    fun idFor(value: String): Int? = valueToId[value]

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
