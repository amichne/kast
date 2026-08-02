#[test]
fn high_cardinality_default_compact_page_stays_within_agent_budget() {
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
    let _index = create_workspace_index(&home, &workspace, "compact-budget", 500);
    let server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("compact-budget.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440003"),
    );

    let output = kast(&home, &config_home)
        .args([
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--kind",
            "source",
        ])
        .output()
        .expect("compact workspace-files page");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    server.join().expect("compact budget backend");
    let raw = String::from_utf8(output.stdout).expect("compact UTF-8");
    let stdout: serde_json::Value =
        toon_format::decode_default(&raw).expect("compact default TOON");
    assert_eq!(stdout["result"]["returnedCount"], 20);
    assert_eq!(stdout["result"]["files"].as_array().map(Vec::len), Some(1));
    let group = &stdout["result"]["files"][0];
    assert_eq!(group["backendModules"], serde_json::json!(["fixture.main"]));
    assert_eq!(
        group["indexedGradleProjects"],
        serde_json::json!([{"buildRoot": ".", "projectPath": ":app"}])
    );
    assert_eq!(
        group["sourceSets"],
        serde_json::json!({
            "type": "PROVEN",
            "sourceSets": [{
                "buildRoot": ".",
                "projectPath": ":app",
                "sourceSetName": "main"
            }]
        })
    );
    assert_eq!(group["kind"], "KOTLIN_SOURCE");
    assert_eq!(
        group["package"],
        serde_json::json!({"type": "PROVEN_NAMED", "name": "sample"})
    );
    assert_eq!(group["sourceIndex"], "INDEXED");
    assert_eq!(group["drift"], "NONE");
    assert_eq!(group["dirty"], "UNKNOWN");
    assert_eq!(group["paths"].as_array().map(Vec::len), Some(20));
    assert_eq!(
        group["paths"][0],
        serde_json::json!({
            "filePath": workspace.join("src/main/kotlin/sample/Source0000.kt").display().to_string(),
            "relativePath": "src/main/kotlin/sample/Source0000.kt"
        }),
        "compact projection must retain routing and coherence evidence: {stdout:#}"
    );
    let lines = raw.lines().count();
    let tokens = tiktoken_rs::cl100k_base()
        .expect("cl100k tokenizer")
        .encode_with_special_tokens(&raw)
        .len();
    assert!(
        lines <= 120,
        "compact page used {lines} lines and {tokens} cl100k tokens; budgets are 120/1500"
    );
    assert!(
        tokens <= 1_500,
        "compact page used {tokens} cl100k tokens; budget is 1500"
    );
}
