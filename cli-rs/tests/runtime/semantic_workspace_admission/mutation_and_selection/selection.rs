#[test]
fn automatic_selection_quarantines_idea_and_uses_headless() {
    let fixture = tempfile::tempdir().expect("ambiguity fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let idea_socket = fixture.path().join("idea.sock");
    let headless_socket = fixture.path().join("headless.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    let idea_listener = bind_semantic_listener(&idea_socket);
    let headless_listener = bind_semantic_listener(&headless_socket);
    write_runtime_descriptors(
        &home,
        &[
            (&workspace, &idea_socket, "idea"),
            (&workspace, &headless_socket, "headless"),
        ],
    );
    let idea = ObservedSemanticBackend::spawn(idea_listener, workspace.clone(), "idea");
    let headless =
        ObservedSemanticBackend::spawn(headless_listener, workspace.clone(), "headless");

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

    let idea_methods = idea.finish();
    let headless_methods = headless.finish();
    assert!(
        verify.status.success(),
        "automatic headless admission must succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&verify.stdout),
        String::from_utf8_lossy(&verify.stderr)
    );
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(
        output["result"]["semanticWorkspace"]["backendName"],
        "headless"
    );
    assert!(
        idea_methods.is_empty(),
        "automatic admission must not observe IDEA: {idea_methods:?}"
    );
    assert!(
        !headless_methods.is_empty(),
        "automatic admission must observe headless"
    );
}

#[test]
fn automatic_selection_rejects_two_ready_headless_runtimes() {
    let fixture = tempfile::tempdir().expect("headless conflict fixture");
    let workspace = fixture.path().join("workspace");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let first_socket = fixture.path().join("headless-first.sock");
    let second_socket = fixture.path().join("headless-second.sock");
    write_gradle_workspace(&workspace);
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::create_dir_all(&home).expect("home");
    let first_listener = bind_semantic_listener(&first_socket);
    let second_listener = bind_semantic_listener(&second_socket);
    write_runtime_descriptors(
        &home,
        &[
            (&workspace, &first_socket, "headless"),
            (&workspace, &second_socket, "headless"),
        ],
    );
    let first = ObservedSemanticBackend::spawn(first_listener, workspace.clone(), "headless");
    let second = ObservedSemanticBackend::spawn(second_listener, workspace.clone(), "headless");

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
    assert!(!verify.status.success(), "headless conflict must fail");
    let output: serde_json::Value = serde_json::from_slice(&verify.stdout).expect("verify JSON");
    assert_eq!(output["error"]["code"], "HEADLESS_RUNTIME_CONFLICT");
}
