package io.github.amichne.kast.indexstore.store

import java.sql.Connection

internal fun validateDurableEvidenceSchema(conn: Connection) {
    val requiredColumns = mapOf(
        "evidence_lane_sets" to mapOf(
            "set_id" to true,
            "lane" to true,
            "workspace_identity" to true,
            "environment_fingerprint" to true,
        ),
        "evidence_candidate_shards" to mapOf(
            "set_id" to true,
            "lane" to true,
            "source_path" to true,
            "content_hash" to true,
            "stage_version" to true,
            "payload" to true,
        ),
        "evidence_lane_candidates" to mapOf(
            "lane" to false,
            "set_id" to true,
        ),
        "evidence_lane_publications" to mapOf(
            "lane" to false,
            "current_set_id" to true,
            "current_revision" to true,
            "current_published_at_epoch_millis" to true,
            "previous_set_id" to false,
            "previous_revision" to false,
            "previous_published_at_epoch_millis" to false,
        ),
    )
    requiredColumns.forEach { (tableName, columns) ->
        val actual = tableColumns(conn, tableName)
        check(actual.isNotEmpty()) {
            "Source index schema $SOURCE_INDEX_SCHEMA_VERSION is missing required table $tableName"
        }
        columns.forEach { (columnName, mustBeNonNull) ->
            val actualNonNull = actual[columnName]
            check(actualNonNull != null) {
                "Source index schema $SOURCE_INDEX_SCHEMA_VERSION is missing required column $tableName.$columnName"
            }
            check(!mustBeNonNull || actualNonNull) {
                "Source index schema $SOURCE_INDEX_SCHEMA_VERSION requires $tableName.$columnName to be non-null"
            }
        }
    }

    val requiredPrimaryKeys = mapOf(
        "evidence_lane_sets" to listOf("set_id", "lane"),
        "evidence_candidate_shards" to listOf("set_id", "lane", "source_path"),
        "evidence_lane_candidates" to listOf("lane"),
        "evidence_lane_publications" to listOf("lane"),
    )
    requiredPrimaryKeys.forEach { (tableName, expected) ->
        check(tablePrimaryKey(conn, tableName) == expected) {
            "Source index schema $SOURCE_INDEX_SCHEMA_VERSION has invalid primary key for $tableName"
        }
    }

    val requiredForeignKeys = mapOf(
        "evidence_candidate_shards" to setOf(
            "evidence_lane_sets|CASCADE|set_id->set_id,lane->lane",
        ),
        "evidence_lane_candidates" to setOf(
            "evidence_lane_sets|NO ACTION|set_id->set_id,lane->lane",
        ),
        "evidence_lane_publications" to setOf(
            "evidence_lane_sets|NO ACTION|current_set_id->set_id,lane->lane",
            "evidence_lane_sets|NO ACTION|previous_set_id->set_id,lane->lane",
        ),
    )
    requiredForeignKeys.forEach { (tableName, expected) ->
        check(foreignKeySignatures(conn, tableName).containsAll(expected)) {
            "Source index schema $SOURCE_INDEX_SCHEMA_VERSION has invalid foreign keys for $tableName"
        }
    }
}

private fun tableColumns(conn: Connection, tableName: String): Map<String, Boolean> =
    conn.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info('$tableName')").use { rows ->
            buildMap {
                while (rows.next()) put(rows.getString("name"), rows.getInt("notnull") == 1)
            }
        }
    }

private fun tablePrimaryKey(conn: Connection, tableName: String): List<String> =
    conn.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info('$tableName')").use { rows ->
            buildList {
                while (rows.next()) {
                    val position = rows.getInt("pk")
                    if (position > 0) add(position to rows.getString("name"))
                }
            }.sortedBy { (position, _) -> position }.map { (_, columnName) -> columnName }
        }
    }
