package io.github.amichne.kast.indexstore.store

import java.sql.Statement

internal class SourceIndexSchemaTables {
    internal fun createPathPrefixTable(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS path_prefixes (
                prefix_id INTEGER PRIMARY KEY,
                dir_path TEXT NOT NULL UNIQUE
            )""",
        )
    }

    internal fun createFqNameTable(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS fq_names (
                fq_id INTEGER PRIMARY KEY,
                fq_name TEXT NOT NULL UNIQUE
            )""",
        )
    }

    internal fun createFqNameSearchIndex(stmt: java.sql.Statement) {
        stmt.execute("""CREATE VIRTUAL TABLE IF NOT EXISTS fq_names_fts USING fts5(fq_name, tokenize='trigram')""")
        stmt.execute(
            """CREATE TRIGGER IF NOT EXISTS fq_names_ai
               AFTER INSERT ON fq_names BEGIN
                   INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
               END""",
        )
        stmt.execute(
            """CREATE TRIGGER IF NOT EXISTS fq_names_ad
               AFTER DELETE ON fq_names BEGIN
                   DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
               END""",
        )
        stmt.execute(
            """CREATE TRIGGER IF NOT EXISTS fq_names_au
               AFTER UPDATE OF fq_name ON fq_names BEGIN
                   DELETE FROM fq_names_fts WHERE rowid = old.fq_id;
                   INSERT INTO fq_names_fts(rowid, fq_name) VALUES (new.fq_id, new.fq_name);
               END""",
        )
    }

    internal fun createSourceIndexTables(stmt: java.sql.Statement) {
        stmt.execute(
            """CREATE TABLE IF NOT EXISTS identifier_paths (
                identifier TEXT NOT NULL,
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                PRIMARY KEY (identifier, prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_metadata (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                package_fq_id INTEGER,
                package_state TEXT NOT NULL CHECK(package_state IN ('PROVEN_ROOT','PROVEN_NAMED','UNPROVEN')),
                package_unproven_reason TEXT,
                module_path TEXT,
                source_set TEXT,
                PRIMARY KEY (prefix_id, filename),
                CHECK(
                    (package_state = 'PROVEN_ROOT' AND package_fq_id IS NULL AND package_unproven_reason IS NULL)
                    OR (package_state = 'PROVEN_NAMED' AND package_fq_id IS NOT NULL AND package_unproven_reason IS NULL)
                    OR (
                        package_state = 'UNPROVEN'
                        AND package_fq_id IS NULL
                        AND package_unproven_reason IN (
                            'NOT_SCANNED',
                            'SEMANTIC_ANALYSIS_UNAVAILABLE',
                            'SEMANTIC_ANALYSIS_FAILED',
                            'LEGACY_TEXT_ONLY'
                        )
                    )
                ),
                FOREIGN KEY(package_fq_id) REFERENCES fq_names(fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_gradle_projects (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                build_root TEXT NOT NULL,
                project_path TEXT NOT NULL,
                PRIMARY KEY (prefix_id, filename, build_root, project_path),
                FOREIGN KEY(prefix_id, filename) REFERENCES file_metadata(prefix_id, filename) ON DELETE CASCADE
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_gradle_source_sets (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                build_root TEXT NOT NULL,
                project_path TEXT NOT NULL,
                source_set_name TEXT NOT NULL,
                PRIMARY KEY (prefix_id, filename, build_root, project_path, source_set_name),
                FOREIGN KEY(prefix_id, filename, build_root, project_path)
                    REFERENCES file_gradle_projects(prefix_id, filename, build_root, project_path)
                    ON DELETE CASCADE
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_imports (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename, fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_wildcard_imports (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                fq_id INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename, fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS file_manifest (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                last_modified_millis INTEGER NOT NULL,
                PRIMARY KEY (prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS symbol_references (
                src_prefix_id INTEGER NOT NULL,
                src_filename TEXT NOT NULL,
                source_offset INTEGER NOT NULL,
                source_fq_id INTEGER,
                target_fq_id INTEGER NOT NULL,
                tgt_prefix_id INTEGER,
                tgt_filename TEXT,
                target_offset INTEGER,
                edge_kind TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK(edge_kind IN ('CALL','TYPE_REF','INHERITANCE','OVERRIDE','IMPORT','ANNOTATION','UNKNOWN')),
                PRIMARY KEY (src_prefix_id, src_filename, source_offset, target_fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS declarations (
                fq_id INTEGER NOT NULL,
                kind TEXT NOT NULL CHECK(kind IN ('CLASS','INTERFACE','OBJECT','FUNCTION','PROPERTY','TYPEALIAS','ENUM_CLASS','ENUM_ENTRY','CONSTRUCTOR')),
                visibility TEXT NOT NULL CHECK(visibility IN ('PUBLIC','INTERNAL','PROTECTED','PRIVATE','LOCAL')),
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                declaration_offset INTEGER,
                module_path TEXT,
                source_set TEXT,
                PRIMARY KEY (fq_id, prefix_id, filename)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS declaration_supertypes (
                declaration_fq_id INTEGER NOT NULL,
                supertype_fq_id INTEGER NOT NULL,
                PRIMARY KEY (declaration_fq_id, supertype_fq_id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS pending_updates (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                op TEXT NOT NULL CHECK(op IN ('upsert_file','remove_file','upsert_ref','remove_ref')),
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                payload TEXT,
                session_id TEXT,
                epoch_ms INTEGER NOT NULL,
                applied INTEGER NOT NULL DEFAULT 0
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS module_index_progress (
                module_name TEXT PRIMARY KEY,
                phase2_status TEXT NOT NULL DEFAULT 'PENDING' CHECK(phase2_status IN ('PENDING','INDEXING','COMPLETE','FAILED')),
                indexed_file_count INTEGER NOT NULL DEFAULT 0,
                total_file_count INTEGER NOT NULL DEFAULT 0,
                last_indexed_epoch_ms INTEGER
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_files (
                id INTEGER PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                package_name TEXT,
                module_name TEXT,
                content_hash TEXT,
                refresh_status TEXT NOT NULL CHECK(refresh_status IN ('REFRESHED','CACHED','REMOVED')),
                diagnostics_json TEXT NOT NULL
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS repository_overlay_tombstones (
                path TEXT PRIMARY KEY
            ) WITHOUT ROWID""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_types (
                id INTEGER PRIMARY KEY,
                stable_key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                classifier TEXT,
                nullability TEXT NOT NULL,
                debug_text TEXT NOT NULL,
                flexible_lower_id INTEGER,
                flexible_upper_id INTEGER,
                receiver_type_id INTEGER,
                return_type_id INTEGER
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_symbols (
                id INTEGER PRIMARY KEY,
                stable_key TEXT NOT NULL UNIQUE,
                file_id INTEGER NOT NULL,
                owner_id INTEGER,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                fq_name TEXT,
                signature TEXT,
                visibility TEXT NOT NULL DEFAULT 'PUBLIC',
                modality TEXT,
                origin TEXT NOT NULL DEFAULT 'SOURCE',
                is_expect INTEGER NOT NULL DEFAULT 0,
                is_actual INTEGER NOT NULL DEFAULT 0,
                is_override INTEGER NOT NULL DEFAULT 0,
                is_sealed INTEGER NOT NULL DEFAULT 0,
                is_delegated INTEGER NOT NULL DEFAULT 0,
                declared_type_id INTEGER,
                receiver_type_id INTEGER,
                return_type_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL,
                FOREIGN KEY(file_id) REFERENCES semantic_files(id) ON DELETE CASCADE,
                FOREIGN KEY(owner_id) REFERENCES semantic_symbols(id) ON DELETE CASCADE,
                FOREIGN KEY(declared_type_id) REFERENCES semantic_types(id),
                FOREIGN KEY(receiver_type_id) REFERENCES semantic_types(id),
                FOREIGN KEY(return_type_id) REFERENCES semantic_types(id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_type_edges (
                id INTEGER PRIMARY KEY,
                parent_type_id INTEGER NOT NULL,
                child_type_id INTEGER,
                role TEXT NOT NULL,
                position INTEGER NOT NULL,
                variance TEXT NOT NULL,
                FOREIGN KEY(parent_type_id) REFERENCES semantic_types(id) ON DELETE CASCADE,
                FOREIGN KEY(child_type_id) REFERENCES semantic_types(id)
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_symbol_annotations (
                symbol_id INTEGER NOT NULL,
                annotation_name TEXT NOT NULL,
                PRIMARY KEY(symbol_id, annotation_name),
                FOREIGN KEY(symbol_id) REFERENCES semantic_symbols(id) ON DELETE CASCADE
            )""",
        )

        stmt.execute(
            """CREATE TABLE IF NOT EXISTS semantic_edge_occurrences (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                source_file_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                context TEXT NOT NULL,
                resolved_target_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL,
                FOREIGN KEY(source_id) REFERENCES semantic_symbols(id) ON DELETE CASCADE,
                FOREIGN KEY(target_id) REFERENCES semantic_symbols(id) ON DELETE CASCADE,
                FOREIGN KEY(source_file_id) REFERENCES semantic_files(id) ON DELETE CASCADE,
                FOREIGN KEY(resolved_target_id) REFERENCES semantic_symbols(id) ON DELETE SET NULL
            )""",
        )

        stmt.execute(
            """CREATE VIEW semantic_file_quotient AS
               SELECT source.file_id AS source_container_id,
                      target.file_id AS target_container_id,
                      edges.kind AS kind,
                      edges.context AS context,
                      COUNT(*) AS weight
               FROM semantic_edge_occurrences edges
               JOIN semantic_symbols source ON source.id = edges.source_id
               JOIN semantic_symbols target ON target.id = edges.target_id
               GROUP BY source.file_id, target.file_id, edges.kind, edges.context""",
        )
        stmt.execute(
            """CREATE VIEW semantic_package_quotient AS
               SELECT source_file.package_name AS source_container,
                      target_file.package_name AS target_container,
                      edges.kind AS kind,
                      edges.context AS context,
                      COUNT(*) AS weight
               FROM semantic_edge_occurrences edges
               JOIN semantic_symbols source ON source.id = edges.source_id
               JOIN semantic_files source_file ON source_file.id = source.file_id
               JOIN semantic_symbols target ON target.id = edges.target_id
               JOIN semantic_files target_file ON target_file.id = target.file_id
               WHERE source_file.package_name IS NOT NULL AND target_file.package_name IS NOT NULL
               GROUP BY source_file.package_name, target_file.package_name, edges.kind, edges.context""",
        )
        stmt.execute(
            """CREATE VIEW semantic_module_quotient AS
               SELECT source_file.module_name AS source_container,
                      target_file.module_name AS target_container,
                      edges.kind AS kind,
                      edges.context AS context,
                      COUNT(*) AS weight
               FROM semantic_edge_occurrences edges
               JOIN semantic_symbols source ON source.id = edges.source_id
               JOIN semantic_files source_file ON source_file.id = source.file_id
               JOIN semantic_symbols target ON target.id = edges.target_id
               JOIN semantic_files target_file ON target_file.id = target.file_id
               WHERE source_file.module_name IS NOT NULL AND target_file.module_name IS NOT NULL
               GROUP BY source_file.module_name, target_file.module_name, edges.kind, edges.context""",
        )
    }

}
