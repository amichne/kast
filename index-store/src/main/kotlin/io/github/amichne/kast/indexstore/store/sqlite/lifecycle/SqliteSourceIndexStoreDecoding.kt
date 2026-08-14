package io.github.amichne.kast.indexstore.store

import java.sql.ResultSet

internal fun SqliteSourceIndexStoreState.decodeNullablePath(
    rows: ResultSet,
    prefixColumn: Int,
    filenameColumn: Int,
): String? {
    val prefixId = rows.getNullableInt(prefixColumn) ?: return null
    val filename = requireNotNull(rows.getString(filenameColumn)) {
        "Path filename is missing for prefix_id=$prefixId"
    }
    return pathCodec.decode(prefixId, filename)
}
