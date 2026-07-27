#[test]
fn workspace_root_must_be_canonicalized_and_admitted() {
    let fixture = tempfile::tempdir().expect("workspace fixture");
    let unresolved = fixture.path().join("missing");
    let output = run_workspace_files(&[
        "--workspace-root",
        unresolved.to_str().expect("UTF-8 unresolved root"),
    ]);
    assert_eq!(output.status.code(), Some(1));
    let document: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace admission JSON");
    assert_eq!(document["error"]["code"], "AGENT_WORKSPACE_INVALID");
    assert!(
        document["error"]["details"].get("admittedQuery").is_none(),
        "{document:#}"
    );
}
