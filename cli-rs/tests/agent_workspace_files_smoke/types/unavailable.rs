#[test]
fn unavailable_error_has_one_structured_recovery_action_and_toon_stdout_discipline() {
    let workspace = tempfile::tempdir().expect("workspace");
    let workspace = std::fs::canonicalize(workspace.path()).expect("canonical workspace");
    let workspace = workspace.to_str().expect("UTF-8 workspace");
    let output = run_workspace_files_with_output(
        "toon",
        &["--workspace-root", workspace, "--kind", "source"],
    );
    assert_eq!(output.status.code(), Some(1));
    assert!(
        output.stderr.is_empty(),
        "machine-readable failure must keep stderr empty: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let toon = std::str::from_utf8(&output.stdout).expect("TOON UTF-8");
    assert!(
        output.stdout.ends_with(b"\n") && !output.stdout.ends_with(b"\n\n"),
        "TOON stdout must end with exactly one newline: {toon:?}"
    );
    let document: serde_json::Value =
        toon_format::decode_default(toon).expect("workspace-files TOON");
    assert_eq!(document["error"]["code"], "SEMANTIC_WORKSPACE_UNSUPPORTED");
    assert!(
        document["error"]["details"]["semanticWorkspace"]
            .get("nextActions")
            .is_none(),
        "{document:#}"
    );
    assert_eq!(
        document["error"]["details"]["nextAction"],
        serde_json::json!({
            "kind": "VERIFY_WORKSPACE",
            "command": "kast",
            "arguments": ["agent", "verify", "--workspace-root", workspace],
            "mutatesGlobalInstallAuthority": false
        }),
        "{document:#}"
    );
}
