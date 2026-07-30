package io.github.amichne.kast.indexstore.store

import java.sql.Connection

internal fun foreignKeySignatures(conn: Connection, tableName: String): Set<String> {
    val columnsById = mutableMapOf<Int, MutableList<Triple<Int, String, String>>>()
    val targetTableById = mutableMapOf<Int, String>()
    val onDeleteById = mutableMapOf<Int, String>()
    conn.createStatement().use { stmt ->
        val rs = stmt.executeQuery("PRAGMA foreign_key_list('$tableName')")
        while (rs.next()) {
            val id = rs.getInt("id")
            columnsById.getOrPut(id) { mutableListOf() }.add(
                Triple(rs.getInt("seq"), rs.getString("from"), rs.getString("to")),
            )
            targetTableById[id] = rs.getString("table")
            onDeleteById[id] = rs.getString("on_delete")
        }
    }
    return columnsById.mapTo(mutableSetOf()) { (id, columns) ->
        val mappings = columns.sortedBy { (position, _, _) -> position }.joinToString(",") { (_, from, to) ->
            "$from->$to"
        }
        "${targetTableById.getValue(id)}|${onDeleteById.getValue(id)}|$mappings"
    }
}
