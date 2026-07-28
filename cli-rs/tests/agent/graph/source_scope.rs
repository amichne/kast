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
