#[test]
fn automatic_selection_rejects_two_ready_indexers() {
    let fixture = tempfile::tempdir().expect("indexer conflict fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let first_socket = fixture.path().join("indexer-first.sock");
    let second_socket = fixture.path().join("indexer-second.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    let first_listener = bind_semantic_listener(&first_socket);
    let second_listener = bind_semantic_listener(&second_socket);
    write_runtime_descriptors(
        &home,
        &[
            (&workspace, &first_socket, "indexer"),
            (&workspace, &second_socket, "indexer"),
        ],
    );
    let first = ObservedSemanticBackend::spawn(first_listener, workspace.clone(), "indexer");
    let second = ObservedSemanticBackend::spawn(second_listener, workspace.clone(), "indexer");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("agent verify");

    let _first_methods = first.finish();
    let _second_methods = second.finish();
    assert!(!verify.status.success(), "indexer conflict must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "INDEXER_CONFLICT");
}
