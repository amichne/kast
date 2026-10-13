package io.github.amichne.kast.evidence.sqlite.historical

import java.sql.Connection

internal const val HISTORICAL_SCHEMA_VERSION = 1
internal const val HISTORICAL_APPLICATION_ID = 1262572372

internal val historicalSchema =
    listOf(
        """CREATE TABLE historical_header (
        singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
        repository TEXT NOT NULL, commit_id TEXT NOT NULL, tree_id TEXT NOT NULL,
        model_digest TEXT NOT NULL, resolution_digest TEXT NOT NULL, compiler_digest TEXT NOT NULL,
        profile TEXT NOT NULL, producer TEXT NOT NULL, logical_digest TEXT NOT NULL
    ) STRICT""",
        """CREATE TABLE historical_module (
        id TEXT PRIMARY KEY, project TEXT NOT NULL, name TEXT NOT NULL,
        source_set TEXT
    ) STRICT""",
        """CREATE TABLE historical_dependency (
        source TEXT NOT NULL REFERENCES historical_module(id),
        target TEXT NOT NULL REFERENCES historical_module(id), PRIMARY KEY(source, target)
    ) STRICT""",
        """CREATE TABLE historical_file (
        ordinal INTEGER PRIMARY KEY CHECK(ordinal >= 0), path TEXT NOT NULL UNIQUE, content TEXT NOT NULL,
        module TEXT NOT NULL REFERENCES historical_module(id),
        provenance TEXT NOT NULL CHECK(provenance IN ('TRACKED', 'GENERATED')),
        provenance_value TEXT NOT NULL
    ) STRICT""",
        """CREATE TABLE historical_declaration (
        ordinal INTEGER PRIMARY KEY CHECK(ordinal >= 0),
        compiler TEXT NOT NULL, file TEXT NOT NULL REFERENCES historical_file(path),
        start_offset INTEGER NOT NULL CHECK(start_offset >= 0),
        end_offset INTEGER NOT NULL CHECK(end_offset > start_offset),
        name TEXT NOT NULL, signature TEXT NOT NULL, kind TEXT NOT NULL,
        UNIQUE(compiler, file, start_offset, end_offset)
    ) STRICT""",
        """CREATE TABLE historical_occurrence (
        ordinal INTEGER PRIMARY KEY CHECK(ordinal >= 0),
        source_file TEXT NOT NULL REFERENCES historical_file(path),
        start_offset INTEGER NOT NULL CHECK(start_offset >= 0),
        end_offset INTEGER NOT NULL CHECK(end_offset > start_offset),
        target INTEGER NOT NULL REFERENCES historical_declaration(ordinal),
        owner_kind TEXT NOT NULL CHECK(owner_kind IN ('DECLARATION', 'FILE_SCOPED', 'UNAVAILABLE')),
        owner_declaration INTEGER REFERENCES historical_declaration(ordinal), owner_detail TEXT,
        kind TEXT NOT NULL, context TEXT NOT NULL,
        CHECK((owner_kind = 'DECLARATION' AND owner_declaration IS NOT NULL)
            OR (owner_kind <> 'DECLARATION' AND owner_declaration IS NULL))
    ) STRICT""",
        """CREATE TABLE historical_coverage (
        module TEXT NOT NULL REFERENCES historical_module(id), family TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('COMPLETE', 'NOT_COLLECTED', 'OUTSIDE_PROFILE', 'QUALIFIED')),
        PRIMARY KEY(module, family)
    ) STRICT""",
        """CREATE TABLE historical_limitation (
        module TEXT NOT NULL, family TEXT NOT NULL, reason TEXT NOT NULL,
        PRIMARY KEY(module, family, reason),
        FOREIGN KEY(module, family) REFERENCES historical_coverage(module, family)
    ) STRICT""",
        "CREATE INDEX historical_occurrence_target ON historical_occurrence(target, ordinal)",
        "CREATE INDEX historical_occurrence_source ON historical_occurrence(source_file, ordinal)",
    )

internal fun Connection.initializeHistoricalSchema() {
    createStatement().use { statement ->
        statement.execute("PRAGMA journal_mode = DELETE")
        statement.execute("PRAGMA synchronous = FULL")
        statement.execute("PRAGMA foreign_keys = ON")
        statement.execute("PRAGMA application_id = $HISTORICAL_APPLICATION_ID")
        statement.execute("PRAGMA user_version = $HISTORICAL_SCHEMA_VERSION")
        historicalSchema.forEach(statement::execute)
    }
}
