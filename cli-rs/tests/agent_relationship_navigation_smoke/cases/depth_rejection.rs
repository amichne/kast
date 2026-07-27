#[test]
fn call_relationships_fail_closed_on_over_depth_backend_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let socket = temp.path().join("idea-over-depth.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/callers",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": relation_identity(
                    "sample.Service.run",
                    "FUNCTION",
                    &std::fs::canonicalize(&declaration_file).expect("canonical source"),
                    15,
                ),
                "records": [{
                    "relation": "CALLER",
                    "relatedSymbol": relation_identity(
                        "sample.Second.call",
                        "FUNCTION",
                        &workspace.join("Second.kt"),
                        40,
                    ),
                    "callSite": relation_location(&workspace.join("Second.kt"), 50),
                    "depth": 2,
                    "containingSymbol": {"type": "TOP_LEVEL"}
                }],
                "page": exact_relation_page(1),
                "schemaVersion": 5
            }),
        )],
    );
    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "callers",
            "--symbol",
            "sample.Service.run",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "function",
            "--depth",
            "1",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("over-depth call relationship");
    assert_eq!(output.status.code(), Some(1));
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("over-depth json");
    assert_eq!(stdout["error"]["code"], "AGENT_RESULT_INVALID");
    backend.join().expect("over-depth backend");
}
