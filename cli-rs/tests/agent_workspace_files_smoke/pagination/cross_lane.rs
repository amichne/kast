#[test]
fn consumed_continuation_rejects_cross_lane_instability() {
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
    let _index = create_workspace_index(&home, &workspace, "unstable-continuation", 500);

    let seed_backend = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("unstable-continuation-seed.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440012"),
    );
    let seed_output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(seed_output.status.success());
    let seed_state = workspace_files_issue_state(
        &seed_backend
            .join()
            .expect("unstable-continuation seed backend"),
    );

    let mut responses = workspace_files_session_responses(&workspace);
    responses.push((
        "raw/workspace-files-continuation",
        serde_json::json!({"type": "CONSUMED", "state": seed_state}),
    ));
    append_paged_workspace_files_collection(&mut responses, &workspace, "snapshot-moved");
    append_paged_workspace_files_collection(&mut responses, &workspace, "snapshot-moved");
    let backend = spawn_sequenced_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("unstable-continuation.sock"),
        responses,
    );

    let output = run_workspace_files_page(
        &home,
        &config_home,
        &workspace,
        Some("550e8400-e29b-41d4-a716-446655440012"),
    );
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("unstable continuation JSON");
    assert_eq!(
        stdout["error"]["code"], "STALE_WORKSPACE_FILES_PAGE",
        "{stdout:#}"
    );
    backend.join().expect("unstable-continuation backend");
}
