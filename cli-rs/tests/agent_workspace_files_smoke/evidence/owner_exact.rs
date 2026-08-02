#[test]
fn gradle_module_filter_is_exact_when_complete_index_proves_candidates_have_no_owner() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin/sample")).expect("workspace sources");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("Gradle settings");
    let source = workspace.join("src/main/kotlin/sample/Source0000.kt");
    std::fs::write(&source, "package sample\nclass Source0000\n").expect("Kotlin source");
    let script = workspace.join("src/main/kotlin/sample/Script.kts");
    std::fs::write(&script, "println(\"fixture\")\n").expect("Kotlin script");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = create_workspace_index(&home, &workspace, "proven-no-gradle-owner", 0);
    index.seed_progress("app", "COMPLETE", 0, 0);
    let backend = spawn_small_mixed_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("proven-no-gradle-owner.sock"),
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--module",
            "gradle:.#:app",
        ])
        .output()
        .expect("proven absent Gradle owner filter");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    backend.join().expect("proven absent Gradle owner backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("proven absent Gradle owner JSON");
    assert_eq!(
        stdout["result"]["coverage"]["filterEvidence"], "COMPLETE",
        "complete index and script non-applicability prove both negative matches: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 0}),
        "proven negative Gradle ownership must retain exact zero: {stdout:#}"
    );
}
