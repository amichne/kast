#[test]
fn mixed_count_keeps_the_script_group_exact_when_source_inventory_is_partial() {
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
    let index = create_workspace_index(&home, &workspace, "mixed-count", 1);
    index.seed_progress("app", "INDEXING", 1, 2);
    let script = workspace.join("src/main/kotlin/sample/Script.kts");
    std::fs::write(&script, "println(\"fixture\")\n").expect("Kotlin script");
    let server = spawn_small_mixed_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("mixed-count.sock"),
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--count",
        ])
        .output()
        .expect("mixed workspace-file count");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    server.join().expect("mixed count backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("mixed count JSON");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_WORKSPACE_FILES_COUNT");
    assert_eq!(stdout["result"]["cardinality"]["type"], "KNOWN_MINIMUM");
    assert_eq!(stdout["result"]["cardinality"]["knownMinimumCount"], 2);
    assert_eq!(stdout["result"]["returnedCount"], 0);
    assert!(stdout["result"].get("files").is_none(), "{stdout:#}");
    assert_eq!(
        grouped_cardinality(&stdout, "kind", "KOTLIN_SOURCE")["cardinality"]["type"],
        "KNOWN_MINIMUM"
    );
    assert_eq!(
        grouped_cardinality(&stdout, "kind", "KOTLIN_SCRIPT")["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1})
    );
    assert_eq!(
        grouped_cardinality(&stdout, "index", "NOT_APPLICABLE")["cardinality"],
        serde_json::json!({"type": "EXACT", "totalCount": 1})
    );
}
