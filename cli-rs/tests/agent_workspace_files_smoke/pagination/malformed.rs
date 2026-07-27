#[test]
fn malformed_issued_continuation_token_fails_closed() {
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
    let _index = create_workspace_index(&home, &workspace, "malformed-token", 500);
    let server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("malformed-token.sock"),
        None,
        Some("not-a-canonical-uuid-v4"),
    );

    let output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files JSON error");
    assert_eq!(
        stdout["error"]["code"], "AGENT_RESULT_INVALID",
        "{stdout:#}"
    );

    let requests = server.join().expect("malformed-token backend");
    assert!(
        requests.iter().any(|request| {
            request["method"] == "raw/workspace-files-continuation"
                && request["params"]["action"] == "ISSUE"
        }),
        "{requests:#?}"
    );
}
