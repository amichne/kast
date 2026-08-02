#[test]
fn gradle_module_filter_is_partial_when_candidate_ownership_is_unknown() {
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
    let index = create_workspace_index(&home, &workspace, "unknown-gradle-owner", 0);
    index.seed_progress("app", "INDEXING", 0, 1);
    let backend_only = workspace.join("src/main/kotlin/sample/BackendOnly.kt");
    std::fs::create_dir_all(backend_only.parent().expect("source parent")).expect("source parent");
    std::fs::write(&backend_only, "package sample\nclass BackendOnly\n")
        .expect("backend-only source");
    let backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("unknown-gradle-owner.sock"),
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
            "--module",
            "gradle:.#:app",
        ])
        .output()
        .expect("unknown Gradle owner filter");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("unknown Gradle owner backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("unknown Gradle owner JSON");
    assert_eq!(
        stdout["result"]["coverage"]["filterEvidence"], "PARTIAL",
        "unknown indexed Gradle ownership cannot prove a negative match: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 0}),
        "unknown indexed Gradle ownership cannot produce exact zero: {stdout:#}"
    );
}
