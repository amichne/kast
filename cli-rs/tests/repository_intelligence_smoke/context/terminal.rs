#[test]
fn repository_terminal_context_resolution_skips_content_reads() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    std::fs::create_dir_all(workspace.join("docs")).expect("context fixture directory");
    std::fs::write(workspace.join("docs/unrelated.md"), [0xff, 0xfe])
        .expect("malformed context fixture");

    let output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Resolve parse context.",
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
        .expect("terminal context repository");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let raw = String::from_utf8(output.stdout).expect("terminal context UTF-8");
    let response: serde_json::Value =
        toon_format::decode_default(raw.trim()).expect("terminal context TOON");
    assert_eq!(
        serde_json::json!({
            "status": response["result"]["status"],
            "candidates": response["result"]["context"]["ambiguousReferences"][0]["candidates"]
                .as_array()
                .expect("ambiguity candidates")
                .iter()
                .map(|candidate| candidate["canonicalKey"].clone())
                .collect::<Vec<_>>()
        }),
        serde_json::json!({
            "status": "AMBIGUOUS",
            "candidates": [
                "callable:SemanticGraphSha256.parse",
                "callable:other.parse"
            ]
        }),
        "{response:#}"
    );
}
