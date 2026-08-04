#[test]
fn stable_partial_inventory_can_continue_known_matches() {
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
    let index = create_workspace_index(&home, &workspace, "stable-partial", 500);
    index.seed_progress("app", "INDEXING", 499, 500);
    let backend = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("stable-partial.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440011"),
    );

    let output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("stable partial JSON");
    assert_eq!(stdout["result"]["cardinality"]["type"], "KNOWN_MINIMUM");
    assert_eq!(stdout["result"]["returnedCount"], 200);
    assert_eq!(
        stdout["result"]["nextPageToken"],
        "550e8400-e29b-41d4-a716-446655440011"
    );
    assert!(
        stdout["result"]["limitations"]
            .as_array()
            .expect("limitations")
            .iter()
            .any(|limitation| limitation["code"] == "SOURCE_INDEX_PROGRESS_INCOMPLETE"),
        "{stdout:#}"
    );
    let requests = backend.join().expect("stable partial backend");
    assert!(requests.iter().any(|request| {
        request["method"] == "raw/workspace-files-continuation"
            && request["params"]["action"] == "ISSUE"
    }));
}
