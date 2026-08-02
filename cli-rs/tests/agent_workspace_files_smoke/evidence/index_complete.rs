#[test]
fn complete_index_evidence_publishes_backend_only_source_as_not_indexed() {
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
    let index = create_workspace_index(&home, &workspace, "not-indexed", 0);
    index.seed_progress("app", "COMPLETE", 0, 0);
    let backend_only = workspace.join("src/main/kotlin/sample/BackendOnly.kt");
    std::fs::create_dir_all(backend_only.parent().expect("source parent")).expect("source parent");
    std::fs::write(&backend_only, "package sample\nclass BackendOnly\n")
        .expect("backend-only source");
    let backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("not-indexed.sock"),
        &backend_only,
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--kind",
            "source",
            "--fields",
            "path,index,drift",
        ])
        .output()
        .expect("not-indexed discovery");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("not-indexed backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("not-indexed JSON");
    assert_eq!(
        stdout["result"]["files"],
        serde_json::json!([{
            "filePath": backend_only.display().to_string(),
            "relativePath": "src/main/kotlin/sample/BackendOnly.kt",
            "sourceIndex": "NOT_INDEXED",
            "drift": "FILESYSTEM_ONLY"
        }]),
        "NOT_INDEXED requires complete source-index evidence: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1})
    );

    let count_backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("not-indexed-count.sock"),
        &backend_only,
    );
    let count_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--kind",
            "source",
            "--count",
        ])
        .output()
        .expect("not-indexed count");
    assert!(
        count_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&count_output.stdout),
        String::from_utf8_lossy(&count_output.stderr),
    );
    count_backend.join().expect("not-indexed count backend");
    let count_stdout: serde_json::Value =
        serde_json::from_slice(&count_output.stdout).expect("not-indexed count JSON");
    assert_eq!(
        grouped_cardinality(&count_stdout, "index", "NOT_INDEXED")["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1}),
        "count projection must agree with the detailed index state: {count_stdout:#}"
    );
}
