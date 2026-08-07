#[test]
fn explicit_refresh_does_not_expand_to_unrelated_pending_graph_files() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = seed_empty_graph_scope(&workspace);
    index.seed_high_cardinality_sources(501);
    index.seed_progress("app", "COMPLETE", 501, 501);
    index
        .connection()
        .execute(
            "DELETE FROM file_stage_outcomes WHERE stage = 'SEMANTIC_GRAPH'",
            [],
        )
        .expect("remove semantic graph outcomes");
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let mut workspace_refresh = complete_refresh(&source, &uuid::Uuid::new_v4().to_string());
    workspace_refresh["relationshipFailures"] = json!([]);
    let socket = fixture.path().join("focused-refresh.sock");
    let backend = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        3,
        vec![
            ("raw/workspace-refresh", workspace_refresh),
            ("raw/diagnostics", diagnostics_with_error(&source)),
            (
                "raw/semantic-graph",
                json!({
                    "generation": 10,
                    "scopeFingerprint": "a".repeat(64),
                    "coverage": {
                        "files": [{
                            "path": "src/main/kotlin/sample/Source0000.kt",
                            "contentHash": "b".repeat(64),
                            "status": "REFRESHED",
                            "diagnostics": []
                        }],
                        "omittedExternalTargetCount": 0
                    },
                    "symbolCount": 1,
                    "edgeOccurrenceCount": 0
                }),
            ),
        ],
    );

    let refresh = kast(&home, &config_home, &workspace)
        .args([
            "workspace",
            "refresh",
            "--file",
            source.to_str().expect("source"),
        ])
        .output()
        .expect("refresh");
    assert!(
        refresh.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&refresh.stdout),
        String::from_utf8_lossy(&refresh.stderr)
    );
    let requests = backend.join().expect("focused refresh backend");
    let graph = requests
        .iter()
        .find(|request| request["method"] == "raw/semantic-graph")
        .expect("graph refresh");
    assert_eq!(graph["params"]["filePaths"], json!([source]));
}
