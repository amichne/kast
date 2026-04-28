package io.github.amichne.kast.indexstore

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap

internal class StringInterningCodec(
    private val tableName: String,
    private val idColumn: String,
    private val valueColumn: String,
) {
    private val valueToId = ConcurrentHashMap<String, Int>()
    private val idToValue = ConcurrentHashMap<Int, String>()

    @Volatile
    private var loaded = false

    fun loadAll(conn: Connection) {
        val loadedValues = mutableMapOf<String, Int>()
        val loadedIds = mutableMapOf<Int, String>()
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT $idColumn, $valueColumn FROM $tableName")
            while (rs.next()) {
                val id = rs.getInt(1)
                val value = rs.getString(2)
                loadedValues[value] = id
                loadedIds[id] = value
            }
        }
        valueToId.clear()
        valueToId.putAll(loadedValues)
        idToValue.clear()
        idToValue.putAll(loadedIds)
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
        val missingValues = values.filterNot { valueToId.containsKey(it) }
        if (missingValues.isEmpty()) return
        conn.prepareStatement("INSERT OR IGNORE INTO $tableName ($valueColumn) VALUES (?)").use { stmt ->
            for (value in missingValues) {
                stmt.setString(1, value)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        loadAll(conn)
    }

    fun resolve(id: Int): String =
        idToValue[id] ?: throw IllegalStateException("Missing interned string in $tableName for $idColumn=$id")

    fun resolveOrNull(id: Int): String? = idToValue[id]

    fun idFor(value: String): Int? = valueToId[value]

    private fun ensureLoaded(conn: Connection) {
        if (!loaded) loadAll(conn)
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
}
