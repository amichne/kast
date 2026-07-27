#[test]
fn structured_filters_are_conjunctive_and_never_match_legacy_labels() {
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
    let index = create_workspace_index(&home, &workspace, "structured-filters", 0);
    seed_structured_filter_evidence(&index);
    let server = spawn_structured_filter_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("structured-filters.sock"),
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
            "idea",
            "--module",
            "gradle:.#:app",
            "--source-set",
            "integrationTest",
            "--package",
            "named:sample.target",
            "--fields",
            "path,module,source-set,package,index",
        ])
        .output()
        .expect("structured filter query");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    server.join().expect("structured filter backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("structured filter JSON");
    assert_eq!(
        stdout["result"]["files"],
        serde_json::json!([{
            "filePath": workspace.join("src/main/kotlin/sample/Good.kt").display().to_string(),
            "relativePath": "src/main/kotlin/sample/Good.kt",
            "backendModules": ["fixture.main"],
            "indexedGradleProjects": [{"buildRoot": ".", "projectPath": ":app"}],
            "sourceSets": {
                "type": "PROVEN",
                "sourceSets": [{
                    "buildRoot": ".",
                    "projectPath": ":app",
                    "sourceSetName": "integrationTest"
                }]
            },
            "package": {"type": "PROVEN_NAMED", "name": "sample.target"},
            "sourceIndex": "INDEXED"
        }]),
        "each non-result fixture matches only a strict subset or legacy labels: {stdout:#}"
    );
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 1}),
        "{stdout:#}"
    );
    assert_eq!(stdout["result"]["coverage"]["filterEvidence"], "PARTIAL");
}
