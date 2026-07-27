#[test]
fn repository_context_empty_preserves_unresolved_references() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let question = "Resolve MissingContextSymbol context.";

    let compact_output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            question,
            "--intent",
            "context-relationship",
            "--language",
            "kotlin",
            "--source",
            "markdown",
            "--results",
            "10",
            "--evidence",
            "1",
        ])
        .output()
        .expect("compact empty context repository");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr)
    );
    let compact_raw =
        String::from_utf8(compact_output.stdout).expect("compact empty context UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact empty context TOON");
    assert_eq!(
        serde_json::json!({
            "status": compact["result"]["status"],
            "unresolvedReferences": compact["result"]["context"]["unresolvedReferences"]
        }),
        serde_json::json!({
            "status": "EMPTY",
            "unresolvedReferences": ["MissingContextSymbol"]
        }),
        "{compact:#}"
    );

    let selected_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            question,
            "--intent",
            "context-relationship",
            "--language",
            "kotlin",
            "--source",
            "markdown",
            "--results",
            "10",
            "--evidence",
            "1",
            "--fields",
            "context",
        ])
        .output()
        .expect("selected empty context repository");
    assert!(
        selected_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&selected_output.stdout),
        String::from_utf8_lossy(&selected_output.stderr)
    );
    let selected: serde_json::Value =
        serde_json::from_slice(&selected_output.stdout).expect("selected empty context JSON");
    assert_eq!(
        selected["result"]["context"]["unresolvedReferences"],
        serde_json::json!(["MissingContextSymbol"]),
        "{selected:#}"
    );
}
