#[test]
fn repository_negative_answers_follow_coverage_state() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let request = |scope: serde_json::Value| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "negative",
            "method": "repository/query",
            "params": {
                "question": "Does DefinitelyMissing exist?",
                "intent": "resolve",
                "scope": scope,
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };

    let (status, complete) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{complete:#}");
    assert_eq!(complete["result"]["status"], "EMPTY");
    assert_eq!(complete["result"]["coverage"]["complete"], true);

    fixture
        .connection()
        .execute(
            "UPDATE file_manifest SET content_hash = ? WHERE filename = 'Source0000.kt'",
            params!["f".repeat(64)],
        )
        .expect("advance persisted source hash");
    let (status, stale) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{stale:#}");
    assert_eq!(stale["result"]["status"], "QUALIFIED_EMPTY");
    assert_eq!(stale["result"]["coverage"]["stale"], 1);
    assert!(stale["result"]["qualification"].is_string());

    fixture
        .connection()
        .execute(
            "DELETE FROM file_stage_outcomes WHERE stage = 'SEMANTIC_GRAPH'",
            [],
        )
        .expect("remove semantic graph outcome");
    let (status, pending) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{pending:#}");
    assert_eq!(pending["result"]["coverage"]["pending"], 1);
    assert_eq!(pending["result"]["coverage"]["complete"], false);
    let (_, pending_coverage) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "failed-coverage",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert_eq!(
        pending_coverage["result"]["files"][0]["diagnostics"][0]["code"],
        "SEMANTIC_GRAPH_MISSING"
    );

    fixture
        .connection()
        .execute("DELETE FROM file_metadata", [])
        .expect("remove compilation ownership evidence");
    let (status, excluded) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{excluded:#}");
    assert_eq!(excluded["result"]["coverage"]["excluded"], 1);
    assert_eq!(excluded["result"]["coverage"]["failed"], 0);
    assert_eq!(excluded["result"]["coverage"]["eligibilityProven"], false);
    assert_eq!(excluded["result"]["coverage"]["complete"], false);
    let (_, excluded_coverage) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "excluded-coverage",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert_eq!(
        excluded_coverage["result"]["files"][0]["reasonCode"],
        "SOURCE_INDEX_METADATA_UNAVAILABLE"
    );
}

#[cfg(unix)]
#[test]
fn repository_coverage_ignores_live_source_content_and_resolution_drift() {
    use std::os::unix::fs::symlink;

    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    std::fs::write(
        &source,
        "package sample\nclass ChangedOutsideTheIndex\n",
    )
    .expect("change live source only");

    let request = || {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "persisted-authority",
            "method": "repository/query",
            "params": {
                "question": "Does DefinitelyMissing exist?",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };
    let assert_exact = || {
        let (status, response) = rpc(&home, &config_home, &workspace, request());
        assert!(status.success(), "{response:#}");
        assert_eq!(response["result"]["status"], "EMPTY", "{response:#}");
        assert_eq!(response["result"]["coverage"]["complete"], true, "{response:#}");
    };

    assert_exact();
    std::fs::remove_file(&source).expect("remove live source");
    assert_exact();

    let outside = tempfile::tempdir().expect("outside source directory");
    std::fs::write(outside.path().join("Outside.kt"), "package outside\n")
        .expect("outside source");
    symlink(outside.path().join("Outside.kt"), &source).expect("escaping source symlink");
    assert_exact();

    std::fs::remove_file(&source).expect("remove escaping symlink");
    symlink(workspace.join("missing-target.kt"), &source).expect("dangling source symlink");
    assert_exact();
}

#[test]
fn repository_missing_semantic_scope_fingerprint_cannot_produce_exact_empty() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET stage_input_fingerprint = NULL
             WHERE stage = 'SEMANTIC_GRAPH'",
            [],
        )
        .expect("remove semantic scope fingerprint");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "missing-fingerprint",
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
    assert_eq!(response["result"]["status"], "QUALIFIED_EMPTY", "{response:#}");
    assert_eq!(response["result"]["coverage"]["complete"], false, "{response:#}");
    assert_eq!(response["result"]["coverage"]["stale"], 1, "{response:#}");
}

#[test]
fn repository_mixed_semantic_scope_fingerprints_cannot_produce_exact_empty() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET stage_input_fingerprint = ?
             WHERE stage = 'SEMANTIC_GRAPH' AND filename = 'Source0001.kt'",
            params!["b".repeat(64)],
        )
        .expect("split semantic scope fingerprint");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "mixed-fingerprint",
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
    assert_eq!(response["result"]["status"], "QUALIFIED_EMPTY", "{response:#}");
    assert_eq!(response["result"]["coverage"]["complete"], false, "{response:#}");
    assert_eq!(response["result"]["coverage"]["stale"], 1, "{response:#}");
}

#[test]
fn repository_removed_semantic_path_invalidates_surviving_scope_fingerprint() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "DELETE FROM semantic_files
             WHERE path = 'src/main/kotlin/sample/Source0001.kt'",
            [],
        )
        .expect("remove one persisted semantic path");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "narrowed-fingerprint",
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
    assert_eq!(response["result"]["status"], "QUALIFIED_EMPTY", "{response:#}");
    assert_eq!(response["result"]["coverage"]["complete"], false, "{response:#}");
    assert_eq!(response["result"]["coverage"]["stale"], 2, "{response:#}");
}

#[test]
fn repository_malformed_matching_hashes_and_versions_fail_closed() {
    let corruptions = [
        (
            "matching malformed hashes",
            "UPDATE file_manifest SET content_hash = 'x' WHERE filename = 'Source0000.kt';
             UPDATE file_stage_outcomes
             SET content_hash = 'x'
             WHERE filename = 'Source0000.kt' AND stage = 'SEMANTIC_GRAPH';",
        ),
        (
            "matching blank stage versions",
            "UPDATE file_manifest
             SET desired_semantic_graph_version = ' '
             WHERE filename = 'Source0000.kt';
             UPDATE file_stage_outcomes
             SET stage_version = ' '
             WHERE filename = 'Source0000.kt' AND stage = 'SEMANTIC_GRAPH';",
        ),
        (
            "matching control stage versions",
            "UPDATE file_manifest
             SET desired_semantic_graph_version = char(10)
             WHERE filename = 'Source0000.kt';
             UPDATE file_stage_outcomes
             SET stage_version = char(10)
             WHERE filename = 'Source0000.kt' AND stage = 'SEMANTIC_GRAPH';",
        ),
    ];

    for (label, corruption) in corruptions {
        let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
        fixture
            .connection()
            .execute_batch(corruption)
            .unwrap_or_else(|error| panic!("{label}: {error}"));
        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": "malformed-stage-authority",
                "method": "graph/coverage",
                "params": {"scope": {"language": "kotlin"}}
            }),
        );
        assert!(!status.success(), "{label}: {response:#}");
        assert_eq!(
            response["code"],
            "GRAPH_COVERAGE_UNAVAILABLE",
            "{label}: {response:#}"
        );
    }
}

#[test]
fn repository_malformed_semantic_source_path_fails_closed() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_files
             (path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
             VALUES ('../Escaped.kt', 'escaped', 'escaped.main', ?, 'REFRESHED', '[]')",
            params!["a".repeat(64)],
        )
        .expect("malformed semantic source path");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "malformed-semantic-path",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert!(!status.success(), "{response:#}");
    assert_eq!(response["code"], "GRAPH_COVERAGE_UNAVAILABLE", "{response:#}");
}

#[test]
fn repository_committed_empty_inventory_produces_exact_empty() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(0);
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute("DELETE FROM module_index_progress", [])
        .expect("remove inventory completion evidence");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "empty-inventory",
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
    assert_eq!(response["result"]["coverage"]["total"], 0, "{response:#}");
    assert_eq!(response["result"]["coverage"]["complete"], true, "{response:#}");
    assert_eq!(
        response["result"]["coverage"]["eligibleForCompleteNegative"],
        true,
        "{response:#}"
    );
}
