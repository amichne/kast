#[test]
fn agent_graph_refresh_requires_ready_before_semantic_graph_rpc() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("Sample.kt");
    let socket_path = temp.path().join("indexer.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    std::fs::write(&source, "package sample\nclass Sample\n").expect("source");
    let handle = spawn_sequenced_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            (
                "runtime/status",
                json!({
                    "state": "INDEXING",
                    "backendName": "indexer",
                    "backendVersion": "scripted-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "readiness": {
                        "runtime": available_current_lane(1),
                        "model": building_current_lane(),
                        "workspaceFiles": building_current_lane(),
                        "compiler": building_current_lane(),
                        "sourceIndex": building_retained_lane(),
                        "references": blocked_retained_lane(),
                        "semanticGraph": building_retained_lane(),
                        "mutation": blocked_current_lane()
                    },
                    "schemaVersion": api_schema_version()
                }),
            ),
        ],
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "graph",
            "--operation",
            "refresh",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--file-path",
            source.to_str().expect("source"),
        ])
        .output()
        .expect("graph refresh while indexing");

    let stdout: Value = serde_json::from_slice(&output.stdout).expect("graph readiness error json");
    assert_eq!(stdout["error"]["code"], "RUNTIME_NOT_READY", "{stdout}");
    let requests = handle.join().expect("indexing backend");
    assert_eq!(
        requests
            .iter()
            .map(|request| request["method"].as_str())
            .collect::<Vec<_>>(),
        vec![Some("runtime/status")]
    );
}
