#[path = "../support/mod.rs"]
mod support;

use serde_json::{Value, json};
use support::*;

#[test]
fn agent_graph_refresh_routes_selected_files_through_compiler_graph() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("src/Sample.kt");
    let removed = workspace.join("src/Removed.kt");
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source directory");
    std::fs::write(&source, "package sample\nclass Sample\n").expect("source");
    let handle = spawn_scripted_idea_backend(
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

#[test]
fn agent_graph_refresh_requires_ready_before_semantic_graph_rpc() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source = workspace.join("Sample.kt");
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    std::fs::write(&source, "package sample\nclass Sample\n").expect("source");
    let handle = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            (
                "runtime/status",
                json!({
                    "state": "INDEXING",
                    "healthy": true,
                    "active": true,
                    "indexing": true,
                    "backendName": "idea",
                    "backendVersion": "scripted-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "schemaVersion": 5
                }),
            ),
            (
                "capabilities",
                json!({
                    "backendName": "idea",
                    "backendVersion": "scripted-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "readCapabilities": ["raw/semantic-graph"],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 5
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
    assert_eq!(stdout["error"]["code"], "RUNTIME_INDEXING", "{stdout}");
    let requests = handle.join().expect("indexing backend");
    assert_eq!(
        requests
            .iter()
            .map(|request| request["method"].as_str())
            .collect::<Vec<_>>(),
        vec![Some("runtime/status"), Some("capabilities")]
    );
}

#[test]
fn agent_graph_source_scope_selects_exclusively_and_widens_incrementally() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let app_main = workspace.join("app/src/main/App.kt");
    let app_test = workspace.join("app/src/test/AppTest.kt");
    let lib_main = workspace.join("lib/src/main/Lib.kt");
    let socket_path = temp.path().join("idea.sock");
    for source in [&app_main, &app_test, &lib_main] {
        std::fs::create_dir_all(source.parent().expect("source parent")).expect("source directory");
        std::fs::write(source, "package sample\nclass Sample\n").expect("source");
    }
    let _index = seed_graph_source_scope_index(&workspace);
    let handle = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            (
                "raw/semantic-graph",
                json!({
                    "generation": 8,
                    "scopeFingerprint": "a".repeat(64),
                    "coverage": {
                        "files": [],
                        "omittedExternalTargetCount": 1
                    },
                    "symbolCount": 1,
                    "edgeOccurrenceCount": 0
                }),
            ),
            (
                "raw/semantic-graph",
                json!({
                    "generation": 8,
                    "scopeFingerprint": "a".repeat(64),
                    "coverage": {
                        "files": [],
                        "omittedExternalTargetCount": 0
                    },
                    "symbolCount": 2,
                    "edgeOccurrenceCount": 0
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
            "--module",
            "gradle:.#:app",
            "--source-set",
            "main",
            "--exclusive",
        ])
        .output()
        .expect("scoped graph refresh");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let widened = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "graph",
            "--operation",
            "refresh",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--module",
            "gradle:.#:lib",
            "--source-set",
            "main",
        ])
        .output()
        .expect("additive graph refresh");
    assert!(
        widened.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&widened.stdout),
        String::from_utf8_lossy(&widened.stderr)
    );

    let requests = handle.join().expect("scripted backend");
    let refreshes = requests
        .iter()
        .filter(|request| request["method"] == "raw/semantic-graph")
        .collect::<Vec<_>>();
    assert_eq!(
        refreshes[0]["params"]["filePaths"],
        json!([app_main.canonicalize().expect("canonical app main")])
    );
    assert_eq!(refreshes[0]["params"]["expectedGeneration"], 41);
    assert_eq!(
        refreshes[0]["params"]["removedFilePaths"],
        json!([workspace
            .canonicalize()
            .expect("canonical workspace")
            .join("lib/src/main/Lib.kt")])
    );
    assert_eq!(
        refreshes[1]["params"]["filePaths"],
        json!([
            app_main.canonicalize().expect("canonical app main"),
            lib_main.canonicalize().expect("canonical lib main"),
        ])
    );
    assert_eq!(refreshes[1]["params"]["expectedGeneration"], 41);
    assert_eq!(refreshes[1]["params"]["removedFilePaths"], json!([]));
}

fn seed_graph_source_scope_index(
    workspace: &std::path::Path,
) -> workspace_files::WorkspaceIndexFixture {
    let database = workspace_database_path_for_test(workspace);
    let index = workspace_files::WorkspaceIndexFixture::at_database_path(workspace, &database);
    for (prefix, directory, filename) in [
        (2, "app/src/main", "App.kt"),
        (3, "app/src/test", "AppTest.kt"),
        (4, "lib/src/main", "Lib.kt"),
        (5, "build-logic/src/main", "Unowned.kt"),
    ] {
        index.insert_manifest_file(prefix, directory, filename, false);
        index
            .connection()
            .execute(
                "INSERT INTO file_metadata(
                    prefix_id, filename, package_state, package_unproven_reason
                 ) VALUES (?, ?, 'UNPROVEN', 'NOT_SCANNED')",
                rusqlite::params![prefix, filename],
            )
            .expect("file metadata");
    }
    index.insert_project_evidence(2, "App.kt", ".", ":app", "main");
    index.insert_project_evidence(3, "AppTest.kt", ".", ":app", "test");
    index.insert_project_evidence(4, "Lib.kt", ".", ":lib", "main");
    index.seed_progress(":app", "COMPLETE", 2, 2);
    index.seed_progress(":lib", "COMPLETE", 1, 1);
    index
        .connection()
        .execute_batch(
            r#"
            CREATE TABLE semantic_files(
                id INTEGER PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                refresh_status TEXT NOT NULL
            );
            INSERT INTO semantic_files VALUES
                (1, 'app/src/main/App.kt', 'REFRESHED'),
                (2, 'lib/src/main/Lib.kt', 'REFRESHED');
            "#,
        )
        .expect("source scope schema");
    index
}
