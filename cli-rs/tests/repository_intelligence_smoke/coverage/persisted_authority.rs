#[test]
fn repository_cached_overlay_base_facts_fail_closed() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let base_database = fixture
        .database_path()
        .with_file_name("repository-base.db");
    rusqlite::Connection::open(&base_database)
        .expect("repository base")
        .execute_batch(&format!(
            "CREATE TABLE schema_version(
                 version INTEGER NOT NULL,
                 generation INTEGER NOT NULL
             );
             INSERT INTO schema_version VALUES ({}, 41);
             CREATE TABLE semantic_files(
                 id INTEGER PRIMARY KEY,
                 path TEXT NOT NULL UNIQUE,
                 package_name TEXT,
                 module_name TEXT,
                 refresh_status TEXT NOT NULL
             );
             CREATE TABLE semantic_symbols(
                 id INTEGER PRIMARY KEY,
                 stable_key TEXT,
                 kind TEXT,
                 name TEXT,
                 file_id INTEGER
             );
             CREATE TABLE semantic_edge_occurrences(
                 id INTEGER PRIMARY KEY,
                 source_id INTEGER,
                 target_id INTEGER,
                 source_file_id INTEGER,
                 kind TEXT,
                 context TEXT
             );
             INSERT INTO semantic_files(path, package_name, module_name, refresh_status)
             VALUES (
                 'src/main/kotlin/sample/BaseOnly.kt', 'sample', 'app.main', 'REFRESHED'
             );
             INSERT INTO semantic_symbols(id, stable_key, kind, name, file_id)
             VALUES (1, 'callable:baseOnly', 'FUNCTION', 'baseOnly', 1);",
            env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        ))
        .expect("repository base schema");
    fixture
        .connection()
        .execute_batch(
            "CREATE TABLE repository_overlay_tombstones(path TEXT PRIMARY KEY) WITHOUT ROWID;
             INSERT INTO semantic_files(
                 path, package_name, module_name, content_hash, refresh_status, diagnostics_json
             ) VALUES (
                 'src/main/kotlin/sample/BaseOnly.kt', NULL, NULL, NULL, 'CACHED', '[]'
             );",
        )
        .expect("cached overlay boundary");
    std::fs::write(
        fixture
            .database_path()
            .with_file_name("repository-overlay.json"),
        serde_json::to_vec(&serde_json::json!({
            "baseDatabase": base_database
        }))
        .expect("overlay descriptor JSON"),
    )
    .expect("overlay descriptor");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "cached-overlay-base-facts",
            "method": "repository/query",
            "params": {
                "question": "Resolve baseOnly.",
                "intent": "resolve",
                "canonicalKey": "callable:baseOnly",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(!status.success(), "{response:#}");
    assert_eq!(response["code"], "GRAPH_COVERAGE_UNAVAILABLE", "{response:#}");
}

#[test]
fn repository_scope_fingerprint_uses_kotlin_utf16_path_order() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let directory = "src/main/kotlin/sample";
    let private_use_filename = "\u{e000}.kt";
    let non_bmp_filename = "\u{1f600}.kt";
    for filename in [private_use_filename, non_bmp_filename] {
        fixture.insert_manifest_file(1, directory, filename, true);
    }
    let private_use = "src/main/kotlin/sample/\u{e000}.kt";
    let non_bmp = "src/main/kotlin/sample/\u{1f600}.kt";
    let connection = fixture.connection();
    for (file_id, filename, path) in [
        (2, private_use_filename, private_use),
        (3, non_bmp_filename, non_bmp),
    ] {
        connection
            .execute(
                "INSERT INTO file_metadata(
                     prefix_id, filename, package_fq_id, package_state,
                     package_unproven_reason, module_path, source_set
                 ) VALUES (1, ?, 1, 'PROVEN_NAMED', NULL, 'indexer.app.main', 'main')",
                params![filename],
            )
            .expect("Unicode source metadata");
        connection
            .execute(
                "INSERT INTO file_gradle_projects(
                     prefix_id, filename, build_root, project_path
                 ) VALUES (1, ?, '.', ':app')",
                params![filename],
            )
            .expect("Unicode project evidence");
        connection
            .execute(
                "INSERT INTO file_gradle_source_sets(
                     prefix_id, filename, build_root, project_path, source_set_name
                 ) VALUES (1, ?, '.', ':app', 'main')",
                params![filename],
            )
            .expect("Unicode source-set evidence");
        let content = std::fs::read(workspace.join(path)).expect("Unicode Kotlin source");
        connection
            .execute(
                "INSERT INTO semantic_files(
                     id, path, package_name, module_name, content_hash,
                     refresh_status, diagnostics_json
                 ) VALUES (?, ?, 'sample', 'app.main', ?, 'REFRESHED', '[]')",
                params![file_id, path, hex::encode(Sha256::digest(content))],
            )
            .expect("Unicode semantic path");
    }
    drop(connection);
    fixture.synchronize_semantic_graph_scope_fingerprints();
    fixture.seed_progress("app", "COMPLETE", 3, 3);

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "utf16-scope-order",
            "method": "repository/query",
            "params": {
                "question": "Does DefinitelyMissing exist?",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(response["result"]["status"], "EMPTY", "{response:#}");
    assert_eq!(response["result"]["coverage"]["complete"], true, "{response:#}");
}

#[test]
fn repository_malformed_semantic_scope_fingerprint_fails_closed() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    fixture
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET stage_input_fingerprint = 'not-a-sha-256'
             WHERE stage = 'SEMANTIC_GRAPH'",
            [],
        )
        .expect("malform semantic scope fingerprint");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "malformed-fingerprint",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );

    assert!(!status.success(), "{response:#}");
    assert_eq!(response["code"], "GRAPH_COVERAGE_UNAVAILABLE", "{response:#}");
}

#[test]
fn repository_refreshed_semantic_path_without_manifest_fails_closed() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let direct_path = "src/main/kotlin/sample/Direct.kt";
    let connection = fixture.connection();
    connection
        .execute(
            "INSERT INTO semantic_files(
                 id, path, package_name, module_name, content_hash, refresh_status, diagnostics_json
             ) VALUES (2, ?, 'sample', 'app.main', ?, 'REFRESHED', '[]')",
            params![direct_path, "a".repeat(64)],
        )
        .expect("direct semantic file");
    connection
        .execute(
            "INSERT INTO semantic_symbols(
                 id, stable_key, file_id, owner_id, kind, name, fq_name, signature,
                 start_offset, end_offset, line
             ) VALUES (
                 100, 'callable:orphanDirect', 2, NULL, 'FUNCTION', 'orphanDirect',
                 'sample.orphanDirect', 'sample.orphanDirect|-|||0', 0, 10, 1
             )",
            [],
        )
        .expect("direct semantic symbol");
    let fingerprint_input = format!(
        "selected:{direct_path}\n\
         selected:src/main/kotlin/sample/Source0000.kt\n"
    );
    connection
        .execute(
            "UPDATE file_stage_outcomes
             SET stage_input_fingerprint = ?
             WHERE stage = 'SEMANTIC_GRAPH'",
            params![hex::encode(Sha256::digest(fingerprint_input.as_bytes()))],
        )
        .expect("direct scope fingerprint");
    drop(connection);

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "direct-no-manifest",
            "method": "repository/query",
            "params": {
                "question": "Resolve orphanDirect.",
                "intent": "resolve",
                "canonicalKey": "callable:orphanDirect",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(!status.success(), "{response:#}");
    assert_eq!(response["code"], "GRAPH_COVERAGE_UNAVAILABLE", "{response:#}");
}
