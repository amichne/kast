#[test]
fn agent_graph_scope_rejects_malformed_and_orphaned_gradle_ownership() {
    let cases = [
        (
            "malformed project",
            false,
            "INSERT INTO file_gradle_projects(
                 prefix_id, filename, build_root, project_path
             ) VALUES (6, 'AppUnknown.kt', '.', 'not-a-gradle-path')",
            vec!["--module", "gradle:.#:app"],
        ),
        (
            "malformed source set",
            true,
            "INSERT INTO file_gradle_source_sets(
                 prefix_id, filename, build_root, project_path, source_set_name
             ) VALUES (6, 'AppUnknown.kt', '.', ':app', '')",
            vec![
                "--module",
                "gradle:.#:app",
                "--source-set",
                "main",
            ],
        ),
        (
            "orphan source set",
            false,
            "INSERT INTO file_gradle_source_sets(
                 prefix_id, filename, build_root, project_path, source_set_name
             ) VALUES (6, 'AppUnknown.kt', '.', ':app', 'main')",
            vec!["--source-set", "main"],
        ),
    ];

    for (label, project_proven, ownership_sql, selectors) in cases {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let index = seed_graph_source_scope_index(&workspace);
        seed_unproven_graph_source(&index, &workspace, project_proven);
        index
            .connection()
            .execute_batch(&format!("PRAGMA foreign_keys=OFF; {ownership_sql};"))
            .unwrap_or_else(|error| panic!("{label}: {error}"));

        let mut command = kast(&home, &config_home);
        command.args([
            "--output",
            "json",
            "agent",
            "graph",
            "--operation",
            "refresh",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--exclusive",
        ]);
        command.args(selectors);
        let output = command
            .output()
            .unwrap_or_else(|error| panic!("{label}: {error}"));
        let stdout: serde_json::Value = serde_json::from_slice(&output.stdout)
            .unwrap_or_else(|error| panic!("{label}: {error}"));

        assert!(!output.status.success(), "{label}: {stdout:#}");
        assert_eq!(
            stdout["error"]["code"],
            "GRAPH_SOURCE_SCOPE_UNPROVEN",
            "{label}: {stdout:#}"
        );
    }
}

#[test]
fn agent_graph_scope_blocks_selected_pending_updates_but_ignores_proven_nonmembers() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let app_main = workspace.join("app/src/main/App.kt");
    std::fs::create_dir_all(app_main.parent().expect("source parent")).expect("source directory");
    std::fs::write(&app_main, "package sample\nclass App\n").expect("source");
    let index = seed_graph_source_scope_index(&workspace);
    index.seed_pending_update_at(4, "Lib.kt", false);
    let handle = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("headless.sock"),
        vec![(
            "raw/semantic-graph",
            json!({
                "generation": 8,
                "scopeFingerprint": "a".repeat(64),
                "coverage": {
                    "files": [],
                    "omittedExternalTargetCount": 0
                },
                "symbolCount": 1,
                "edgeOccurrenceCount": 0
            }),
        )],
    );

    let unrelated = kast(&home, &config_home)
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
        ])
        .output()
        .expect("scoped graph refresh");
    assert!(
        unrelated.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&unrelated.stdout),
        String::from_utf8_lossy(&unrelated.stderr)
    );
    handle.join().expect("scripted backend");

    let connection = index.connection();
    connection
        .execute(
            "UPDATE pending_updates SET applied = 1 WHERE filename = 'Lib.kt'",
            [],
        )
        .expect("apply unrelated update");
    drop(connection);
    index.seed_pending_update_at(2, "App.kt", false);
    let selected = kast(&home, &config_home)
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
        ])
        .output()
        .expect("selected pending graph refresh");
    let stdout: serde_json::Value =
        serde_json::from_slice(&selected.stdout).expect("scope error JSON");
    assert!(!selected.status.success(), "{stdout:#}");
    assert_eq!(
        stdout["error"]["code"],
        "GRAPH_SOURCE_SCOPE_INCOMPLETE",
        "{stdout:#}"
    );
}
