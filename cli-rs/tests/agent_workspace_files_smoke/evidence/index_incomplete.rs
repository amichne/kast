#[test]
fn incomplete_index_evidence_counts_backend_only_source_as_unknown() {
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
    let index = create_workspace_index(&home, &workspace, "unknown-index", 0);
    index.seed_progress("app", "INDEXING", 0, 1);
    let backend_only = workspace.join("src/main/kotlin/sample/BackendOnly.kt");
    std::fs::create_dir_all(backend_only.parent().expect("source parent")).expect("source parent");
    std::fs::write(&backend_only, "package sample\nclass BackendOnly\n")
        .expect("backend-only source");
    let backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("unknown-index-count.sock"),
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
            "--backend",
            "headless",
            "--kind",
            "source",
            "--count",
        ])
        .output()
        .expect("unknown index count");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("unknown index count backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("unknown index count JSON");
    assert_eq!(
        grouped_cardinality(&stdout, "index", "UNKNOWN")["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 1}),
        "incomplete source-index evidence must remain UNKNOWN: {stdout:#}"
    );
}

#[test]
fn completed_source_stage_keeps_exact_cardinality_when_relationships_are_limited() {
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
    let index = create_workspace_index(&home, &workspace, "limited-relationships", 1);
    index
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET outcome_status = 'LIMITED',
                 limitations_json = '[\"UNRESOLVED_RELATIONSHIP\"]'
             WHERE stage = 'RELATIONSHIPS'",
            [],
        )
        .expect("limited relationship outcome");
    index.seed_progress("app", "FAILED", 1, 1);
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("limited-relationships.sock"),
        &source,
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
            "--count",
        ])
        .output()
        .expect("limited relationship count");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("limited relationship backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("limited relationship JSON");
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1}),
        "complete durable source stages must prove exact file cardinality: {stdout:#}"
    );
}
