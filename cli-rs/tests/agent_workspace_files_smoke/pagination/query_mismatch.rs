#[test]
fn public_continuations_reject_query_mismatch_and_stale_evidence_without_restarting() {
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
    let _index = create_workspace_index(&home, &workspace, "continuation-failures", 500);

    let seed_backend = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("continuation-seed.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440010"),
    );
    let seed_output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(
        seed_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&seed_output.stdout),
        String::from_utf8_lossy(&seed_output.stderr),
    );
    let seed_requests = seed_backend.join().expect("continuation seed backend");
    let seed_state = workspace_files_issue_state(&seed_requests);

    for (name, mut consumed_state, expected_code) in [
        (
            "query-mismatch",
            seed_state.clone(),
            "INVALID_WORKSPACE_FILES_PAGE_TOKEN",
        ),
        (
            "stale-evidence",
            seed_state.clone(),
            "STALE_WORKSPACE_FILES_PAGE",
        ),
    ] {
        if name == "query-mismatch" {
            consumed_state["identity"]["normalizedQuery"] =
                serde_json::Value::String("{\"filters\":{\"kind\":\"script\"}}".to_string());
        } else {
            consumed_state["compositionStampDigest"] =
                serde_json::Value::String("stale-composition".to_string());
        }
        let backend = spawn_paged_workspace_files_backend(
            &home,
            &config_home,
            &workspace,
            &temp.path().join(format!("{name}.sock")),
            Some(consumed_state),
            None,
        );
        let output = run_workspace_files_page(
            &home,
            &config_home,
            &workspace,
            Some("550e8400-e29b-41d4-a716-446655440010"),
        );
        assert_eq!(
            output.status.code(),
            Some(1),
            "case={name} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("continuation failure JSON");
        assert_eq!(
            stdout["error"]["code"], expected_code,
            "case={name} {stdout:#}"
        );
        assert!(stdout.get("result").is_none(), "case={name} {stdout:#}");
        let requests = backend.join().expect("continuation failure backend");
        assert_eq!(requests[2]["params"]["action"], "CONSUME");
        assert_eq!(requests[3]["method"], "raw/workspace-files");
        assert!(
            requests.iter().all(|request| {
                request["method"] != "raw/workspace-files-continuation"
                    || request["params"]["action"] != "ISSUE"
            }),
            "invalid or stale continuation must never restart or issue a replacement: {requests:#?}"
        );
    }
}
