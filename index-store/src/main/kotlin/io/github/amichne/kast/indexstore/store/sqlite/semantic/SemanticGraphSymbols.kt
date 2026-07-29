package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.result.SemanticGraphSymbol
import java.sql.Connection

internal fun insertSemanticSymbol(conn: Connection, symbol: SemanticGraphSymbol, authoritative: Boolean) {
    val sql = buildString {
        append(
            """INSERT INTO semantic_symbols(
                   stable_key, file_id, owner_id, kind, name, fq_name, signature,
                   visibility, modality, origin, is_expect, is_actual, is_override,
                   is_sealed, is_delegated, declared_type_id, receiver_type_id, return_type_id,
                   start_offset, end_offset, line
               ) VALUES (
                   ?, (SELECT id FROM semantic_files WHERE path = ?), NULL, ?, ?, ?, ?,
                   ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
               )""",
        )
        if (authoritative) {
            append(
                """ ON CONFLICT(stable_key) DO UPDATE SET
                        file_id = excluded.file_id,
                        kind = excluded.kind,
                        name = excluded.name,
                        fq_name = excluded.fq_name,
                        signature = excluded.signature,
                        visibility = excluded.visibility,
                        modality = excluded.modality,
                        origin = excluded.origin,
                        is_expect = excluded.is_expect,
                        is_actual = excluded.is_actual,
                        is_override = excluded.is_override,
                        is_sealed = excluded.is_sealed,
                        is_delegated = excluded.is_delegated,
                        declared_type_id = excluded.declared_type_id,
                        receiver_type_id = excluded.receiver_type_id,
                        return_type_id = excluded.return_type_id,
                        start_offset = excluded.start_offset,
                        end_offset = excluded.end_offset,
                        line = excluded.line""",
            )
        } else {
            append(" ON CONFLICT(stable_key) DO NOTHING")
        }
    }
    conn.prepareStatement(sql).use { statement ->
        statement.setString(1, symbol.canonicalKey.value)
        statement.setString(2, symbol.path.value)
        statement.setString(3, symbol.kind.name)
        statement.setString(4, symbol.name.value)
        statement.setString(5, symbol.fqName?.value)
        statement.setString(6, symbol.signature?.value)
        statement.setString(7, symbol.visibility.name)
        statement.setString(8, symbol.modality?.name)
        statement.setString(9, symbol.origin.name)
        statement.setInt(10, if (symbol.flags.isExpect) 1 else 0)
        statement.setInt(11, if (symbol.flags.isActual) 1 else 0)
        statement.setInt(12, if (symbol.flags.isOverride) 1 else 0)
        statement.setInt(13, if (symbol.flags.isSealed) 1 else 0)
        statement.setInt(14, if (symbol.flags.isDelegated) 1 else 0)
        statement.setObject(15, symbol.declaredTypeKey?.value?.let { key -> semanticTypeIdOrNull(conn, key) })
        statement.setObject(16, symbol.receiverTypeKey?.value?.let { key -> semanticTypeIdOrNull(conn, key) })
        statement.setObject(17, symbol.returnTypeKey?.value?.let { key -> semanticTypeIdOrNull(conn, key) })
        statement.setInt(18, symbol.startOffset.value)
        statement.setInt(19, symbol.endOffset.value)
        statement.setInt(20, symbol.line.value)
        statement.executeUpdate()
    }
    if (authoritative) {
        val symbolId = semanticSymbolId(conn, symbol.canonicalKey.value)
        conn.prepareStatement("DELETE FROM semantic_symbol_annotations WHERE symbol_id = ?").use { statement ->
            statement.setLong(1, symbolId)
            statement.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO semantic_symbol_annotations(symbol_id, annotation_name) VALUES (?, ?)",
        ).use { statement ->
            symbol.annotations.distinct().sortedBy(NonBlankString::value).forEach { annotation ->
                statement.setLong(1, symbolId)
                statement.setString(2, annotation.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}

internal fun updateSemanticSymbolOwner(conn: Connection, symbol: SemanticGraphSymbol) {
    val ownerKey = symbol.ownerKey ?: return
    conn.prepareStatement(
        """UPDATE semantic_symbols
           SET owner_id = (SELECT id FROM semantic_symbols WHERE stable_key = ?)
           WHERE stable_key = ?""",
    ).use { statement ->
        statement.setString(1, ownerKey.value)
        statement.setString(2, symbol.canonicalKey.value)
        statement.executeUpdate()
    }
}

internal fun semanticSymbolId(conn: Connection, key: String): Long =
    requiredSemanticId(conn, "SELECT id FROM semantic_symbols WHERE stable_key = ?", key)

internal fun semanticSymbolIdOrNull(conn: Connection, key: String): Long? =
    optionalSemanticId(conn, "SELECT id FROM semantic_symbols WHERE stable_key = ?", key)

internal fun semanticTypeId(conn: Connection, key: String): Long =
    requiredSemanticId(conn, "SELECT id FROM semantic_types WHERE stable_key = ?", key)

internal fun semanticTypeIdOrNull(conn: Connection, key: String): Long? =
    optionalSemanticId(conn, "SELECT id FROM semantic_types WHERE stable_key = ?", key)

internal fun requiredSemanticId(conn: Connection, sql: String, value: String): Long =
    requireNotNull(optionalSemanticId(conn, sql, value)) { "Missing canonical semantic identity: $value" }

internal fun optionalSemanticId(conn: Connection, sql: String, value: String): Long? =
    conn.prepareStatement(sql).use { statement ->
        statement.setString(1, value)
        val rows = statement.executeQuery()
        if (rows.next()) rows.getLong(1) else null
    }
