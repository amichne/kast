#[test]
fn agent_graph_refresh_routes_selected_files_through_compiler_graph() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("src/Sample.kt");
    let removed = workspace.join("src/Removed.kt");
    let socket_path = temp.path().join("headless.sock");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source directory");
    std::fs::write(&source, "package sample\nclass Sample\n").expect("source");
    let handle = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![(
            "raw/semantic-graph",
            json!({
                "generation": 7,
                "scopeFingerprint": "a".repeat(64),
                "coverage": {
                    "files": [{
                        "path": "src/Sample.kt",
                        "contentHash": "b".repeat(64),
                        "status": "REFRESHED",
                        "diagnostics": []
                    }, {
                        "path": "src/Removed.kt",
                        "contentHash": null,
                        "status": "REMOVED",
                        "diagnostics": []
                    }],
                    "omittedExternalTargetCount": 2
                },
                "symbolCount": 3,
                "edgeOccurrenceCount": 4
            }),
        )],
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
            "--removed-file-path",
            removed.to_str().expect("removed"),
        ])
        .output()
        .expect("graph refresh");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("graph refresh json");
    assert_eq!(stdout["method"], "agent/graph");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_GRAPH_REFRESH");
    assert_eq!(stdout["result"]["operation"], "REFRESH");
    assert_eq!(stdout["result"]["generation"], 7);
    assert_eq!(stdout["result"]["symbolCount"], 3);
    assert_eq!(stdout["result"]["edgeOccurrenceCount"], 4);
    assert_eq!(
        stdout["result"]["coverage"]["files"][0]["status"],
        "REFRESHED"
    );
    assert_eq!(
        stdout["result"]["coverage"]["files"][1]["status"],
        "REMOVED"
    );

    let requests = handle.join().expect("scripted backend");
    let refresh = requests
        .iter()
        .find(|request| request["method"] == "raw/semantic-graph")
        .expect("semantic graph request");
    assert_eq!(
        refresh["params"]["filePaths"],
        json!([source.canonicalize().expect("canonical source")])
    );
    assert_eq!(
        refresh["params"]["removedFilePaths"],
        json!([workspace
            .canonicalize()
            .expect("canonical workspace")
            .join("src/Removed.kt")])
    );
    assert!(
        refresh["params"].get("expectedGeneration").is_none(),
        "{refresh}"
    );
}

#[test]
fn agent_graph_rejects_operation_irrelevant_flags_as_agent_usage() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("Sample.kt");

    for query_args in [
        ["--scope", "symbol"],
        ["--symbol", "sample.Sample"],
        ["--generation", "1"],
        ["--after-id", "0"],
        ["--limit", "100"],
        ["--resolution", "1.0"],
    ] {
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
            .args(query_args)
            .output()
            .expect("graph refresh with query-only flag");
        let stdout: Value =
            serde_json::from_slice(&output.stdout).expect("graph refresh error json");

        assert_eq!(
            stdout["error"]["code"], "AGENT_USAGE",
            "{query_args:?}: {stdout}"
        );
    }

    for operation_args in [
        ["summary", "--symbol", "sample.Sample"],
        ["nodes", "--resolution", "1.0"],
        ["neighbors", "--limit", "100"],
        ["topology", "--after-id", "0"],
        ["communities", "--symbol", "sample.Sample"],
    ] {
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "graph",
                "--operation",
                operation_args[0],
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .args(&operation_args[1..])
            .output()
            .expect("graph operation with irrelevant flag");
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("graph usage error json");

        assert_eq!(
            stdout["error"]["code"], "AGENT_USAGE",
            "{operation_args:?}: {stdout}"
        );
    }
}
