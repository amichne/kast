fn seed_repository_graph(fixture: &WorkspaceIndexFixture) {
    fixture
        .connection()
        .execute_batch(
            "
            CREATE TABLE semantic_types (
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
            );
            CREATE TABLE semantic_symbols (
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
                line INTEGER NOT NULL
            );
            CREATE TABLE semantic_symbol_annotations (
                symbol_id INTEGER NOT NULL,
                annotation_name TEXT NOT NULL,
                PRIMARY KEY(symbol_id, annotation_name)
            );
            CREATE TABLE semantic_edge_occurrences (
                id INTEGER PRIMARY KEY,
                source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL,
                source_file_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                context TEXT NOT NULL,
                resolved_target_id INTEGER,
                start_offset INTEGER NOT NULL,
                end_offset INTEGER NOT NULL,
                line INTEGER NOT NULL
            );
            INSERT INTO semantic_types
                (id, stable_key, kind, classifier, nullability, debug_text)
                VALUES (1, 'type:kotlin.String', 'CLASS', 'kotlin.String', 'NON_NULL', 'String');
            INSERT INTO semantic_symbols
                (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, return_type_id, start_offset, end_offset, line)
                VALUES
                (1, 'class:SemanticGraphSha256', 1, NULL, 'CLASS', 'SemanticGraphSha256', 'sample.SemanticGraphSha256', NULL, NULL, 0, 100, 1),
                (2, 'object:SemanticGraphSha256.Companion', 1, 1, 'OBJECT', 'Companion', 'sample.SemanticGraphSha256.Companion', NULL, NULL, 10, 90, 2),
                (3, 'callable:semanticGraphOperation', 1, NULL, 'FUNCTION', 'semanticGraphOperation', 'sample.semanticGraphOperation', 'sample.semanticGraphOperation|-|||0', NULL, 100, 200, 10),
                (4, 'callable:buildSemanticGraphSnapshot', 1, NULL, 'FUNCTION', 'buildSemanticGraphSnapshot', 'sample.buildSemanticGraphSnapshot', 'sample.buildSemanticGraphSnapshot|-|||0', NULL, 210, 400, 20),
                (5, 'local:hash', 1, 4, 'PROPERTY', 'hash', NULL, NULL, NULL, 250, 300, 25),
                (6, 'callable:SemanticGraphSha256.parse', 1, 2, 'MEMBER_FUNCTION', 'parse', 'sample.SemanticGraphSha256.Companion.parse', 'sample.SemanticGraphSha256.Companion.parse|-||kotlin.String|0', 1, 40, 80, 4),
                (7, 'callable:calls', 1, NULL, 'FUNCTION', 'calls', 'sample.calls', 'sample.calls|-|||0', NULL, 410, 420, 41),
                (8, 'callable:other.parse', 1, NULL, 'FUNCTION', 'parse', 'sample.parse', 'sample.parse|-||kotlin.String|0', 1, 430, 440, 43),
                (9, 'callable:cycleTarget', 1, NULL, 'FUNCTION', 'cycleTarget', 'other.cycleTarget', 'other.cycleTarget|-|||0', NULL, 450, 470, 45);
            INSERT INTO semantic_edge_occurrences
                (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
                VALUES
                (1, 3, 4, 1, 'CALLS', 'CALL', 4, 150, 170, 15),
                (2, 4, 5, 1, 'CONTAINS', 'NONE', 5, 250, 300, 25),
                (3, 5, 6, 1, 'CALLS', 'CALL', 6, 270, 280, 27),
                (4, 5, 6, 1, 'CALLS', 'CALL', 6, 281, 290, 28),
                (5, 5, 6, 1, 'CALLS', 'CALL', 6, 291, 300, 29),
                (6, 3, 9, 1, 'CALLS', 'CALL', 9, 180, 190, 18),
                (7, 9, 3, 1, 'CALLS', 'CALL', 3, 460, 470, 46);
            ",
        )
        .expect("semantic graph facts");
}

fn seed_expect_actual_relationship(fixture: &WorkspaceIndexFixture) {
    fixture
        .connection()
        .execute_batch(
            "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
                  is_expect, is_actual, start_offset, end_offset, line)
             VALUES
                 (20, 'class:actual:PlatformClock', 1, NULL, 'CLASS', 'PlatformClock',
                  'sample.PlatformClock', NULL, 0, 1, 500, 510, 50),
                 (21, 'class:expect:CommonClock', 1, NULL, 'CLASS', 'CommonClock',
                  'sample.CommonClock', NULL, 1, 0, 511, 520, 51);
             INSERT INTO semantic_edge_occurrences
                 (id, source_id, target_id, source_file_id, kind, context, resolved_target_id,
                  start_offset, end_offset, line)
             VALUES
                 (70, 20, 21, 1, 'EXPECT_ACTUAL', 'NONE', 21, 500, 510, 50);",
        )
        .expect("compiler-backed expect/actual relationship");
}

fn seed_high_cardinality_outgoing_calls(fixture: &WorkspaceIndexFixture) {
    seed_outgoing_calls(fixture, 100..200);
}

fn seed_outgoing_calls(fixture: &WorkspaceIndexFixture, ids: std::ops::Range<i64>) {
    let mut connection = fixture.connection();
    let transaction = connection
        .transaction()
        .expect("outgoing calls seed transaction");
    for id in ids {
        let name = format!("target{id}");
        transaction
            .execute(
                "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, start_offset, end_offset, line)
                 VALUES (?, ?, 1, NULL, 'FUNCTION', ?, ?, ?, ?, ?, ?)",
                params![
                    id,
                    format!("callable:{name}"),
                    name,
                    format!("sample.{name}"),
                    format!("sample.{name}|-|||0"),
                    id * 10,
                    id * 10 + 5,
                    id
                ],
            )
            .expect("high-cardinality semantic symbol");
        transaction
            .execute(
                "INSERT INTO semantic_edge_occurrences
                 (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
                 VALUES (?, 3, ?, 1, 'CALLS', 'CALL', ?, ?, ?, ?)",
                params![id, id, id, id * 10, id * 10 + 5, id],
            )
            .expect("high-cardinality semantic edge");
    }
    transaction.commit().expect("outgoing calls seed commit");
}

fn seed_discovery_name_collision(fixture: &WorkspaceIndexFixture) {
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_symbols
             (id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
              start_offset, end_offset, line)
             VALUES
             (30, 'callable:other.semanticGraphOperation', 1, NULL, 'FUNCTION',
              'semanticGraphOperation', 'other.semanticGraphOperation',
              'other.semanticGraphOperation|-|||0', 480, 500, 48)",
            [],
        )
        .expect("discovery name collision");
}

fn seed_out_of_scope_repository_target(fixture: &WorkspaceIndexFixture) {
    fixture.insert_manifest_file(2, "other/src/test/kotlin/other", "OutsideScope.kt", true);
    let path = "other/src/test/kotlin/other/OutsideScope.kt";
    let content = std::fs::read(fixture.workspace_root().join(path)).expect("outside source");
    let connection = fixture.connection();
    connection
        .execute(
            "INSERT INTO fq_names(fq_id, fq_name) VALUES (2, 'other')",
            [],
        )
        .expect("outside package");
    connection
        .execute(
            "INSERT INTO file_metadata
             (prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set)
             VALUES (2, 'OutsideScope.kt', 2, 'PROVEN_NAMED', NULL, 'indexer.other.test', 'test')",
            [],
        )
        .expect("outside metadata");
    drop(connection);
    fixture.insert_project_evidence(2, "OutsideScope.kt", ".", ":other", "test");
    fixture.seed_progress("other", "COMPLETE", 1, 1);
    let connection = fixture.connection();
    connection
        .execute(
            "INSERT INTO semantic_files
             (id, path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
             VALUES (2, ?, 'other', 'other.test', ?, 'REFRESHED', '[]')",
            params![path, hex::encode(Sha256::digest(content))],
        )
        .expect("outside semantic file");
    connection
        .execute(
            "INSERT INTO semantic_symbols
             (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, start_offset, end_offset, line)
             VALUES
             (10, 'callable:outsideScope', 2, NULL, 'FUNCTION', 'outsideScope',
              'other.outsideScope', 'other.outsideScope|-|||0', 0, 20, 1)",
            [],
        )
        .expect("outside semantic symbol");
    fixture.synchronize_semantic_graph_scope_fingerprints();
    connection
        .execute(
            "INSERT INTO semantic_edge_occurrences
             (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
             VALUES (8, 3, 10, 1, 'CALLS', 'CALL', 10, 195, 205, 19)",
            [],
        )
        .expect("cross-scope semantic edge");
}
