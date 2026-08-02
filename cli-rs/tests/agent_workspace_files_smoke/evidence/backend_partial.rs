#[test]
fn backend_module_filter_is_partial_when_backend_page_is_incomplete() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("Gradle settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = create_workspace_index(&home, &workspace, "partial-backend-owner", 1);
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let source_root = workspace.join("src/main/kotlin");
    let module = |files: serde_json::Value, returned_file_count: usize| {
        serde_json::json!({
            "snapshotToken": "snapshot-partial-backend-owner",
            "modules": [{
                "name": "fixture.main",
                "sourceRoots": [source_root.display().to_string()],
                "contentRoots": [workspace.display().to_string()],
                "dependencyModuleNames": [],
                "files": files,
                "returnedFileCount": returned_file_count,
                "filesTruncated": false,
                "fileCount": 2,
                "nextPageToken": null
            }],
            "schemaVersion": 5
        })
    };
    let validation = serde_json::json!({
        "snapshotToken": "snapshot-partial-backend-owner",
        "modules": [],
        "schemaVersion": 5
    });
    let backend = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("partial-backend-owner.sock"),
        vec![
            ("raw/workspace-files", module(serde_json::json!([]), 0)),
            (
                "raw/workspace-files",
                module(serde_json::json!([source.display().to_string()]), 1),
            ),
            ("raw/workspace-files", validation.clone()),
            ("raw/workspace-files", validation),
        ],
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "headless",
            "--kind",
            "source",
            "--module",
            "backend:fixture.main",
        ])
        .output()
        .expect("partial backend owner filter");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("partial backend owner backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("partial backend owner JSON");
    assert_eq!(
        stdout["result"]["coverage"]["filterEvidence"], "PARTIAL",
        "partial backend coverage cannot prove a negative module match: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 0}),
        "partial backend coverage cannot produce exact zero: {stdout:#}"
    );
}
