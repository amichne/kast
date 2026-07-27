#[test]
fn public_continuations_return_five_hundred_files_as_200_200_100_without_gaps() {
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
    let _index = create_workspace_index(&home, &workspace, "paged-inventory", 500);

    let first_server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("page-1.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440001"),
    );
    let first_output = run_workspace_files_page(&home, &config_home, &workspace, None);
    assert!(
        first_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&first_output.stdout),
        String::from_utf8_lossy(&first_output.stderr),
    );
    let first: serde_json::Value =
        serde_json::from_slice(&first_output.stdout).expect("first workspace-files page");
    let first_requests = first_server.join().expect("first workspace-files backend");
    let first_state = workspace_files_issue_state(&first_requests);

    let second_server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("page-2.sock"),
        Some(first_state),
        Some("550e8400-e29b-41d4-a716-446655440002"),
    );
    let second_output = run_workspace_files_page(
        &home,
        &config_home,
        &workspace,
        Some("550e8400-e29b-41d4-a716-446655440001"),
    );
    assert!(
        second_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&second_output.stdout),
        String::from_utf8_lossy(&second_output.stderr),
    );
    let second: serde_json::Value =
        serde_json::from_slice(&second_output.stdout).expect("second workspace-files page");
    let second_requests = second_server
        .join()
        .expect("second workspace-files backend");
    assert_eq!(
        second_requests
            .iter()
            .map(|request| request["method"].as_str().expect("request method"))
            .take(4)
            .collect::<Vec<_>>(),
        vec![
            "runtime/status",
            "capabilities",
            "raw/workspace-files-continuation",
            "raw/workspace-files",
        ],
        "public continuation must be consumed before any inventory collection"
    );
    assert_eq!(second_requests[2]["params"]["action"], "CONSUME");
    let second_state = workspace_files_issue_state(&second_requests);

    let third_server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("page-3.sock"),
        Some(second_state),
        None,
    );
    let third_output = run_workspace_files_page(
        &home,
        &config_home,
        &workspace,
        Some("550e8400-e29b-41d4-a716-446655440002"),
    );
    assert!(
        third_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&third_output.stdout),
        String::from_utf8_lossy(&third_output.stderr),
    );
    let third: serde_json::Value =
        serde_json::from_slice(&third_output.stdout).expect("third workspace-files page");
    third_server.join().expect("third workspace-files backend");

    let pages = [&first, &second, &third];
    assert_eq!(
        pages.map(|page| page["result"]["returnedCount"].as_u64()),
        [Some(200), Some(200), Some(100)]
    );
    assert_eq!(
        first["result"]["nextPageToken"],
        "550e8400-e29b-41d4-a716-446655440001"
    );
    assert_eq!(
        second["result"]["nextPageToken"],
        "550e8400-e29b-41d4-a716-446655440002"
    );
    assert!(third["result"].get("nextPageToken").is_none(), "{third:#}");
    for page in pages {
        assert_eq!(page["result"]["cardinality"]["type"], "EXACT", "{page:#}");
        assert_eq!(page["result"]["cardinality"]["totalCount"], 500, "{page:#}");
        assert_eq!(
            page["result"]["returnedCount"].as_u64(),
            page["result"]["files"]
                .as_array()
                .map(|files| files.len() as u64),
            "{page:#}"
        );
    }
    let relative_paths = pages
        .into_iter()
        .flat_map(|page| {
            page["result"]["files"]
                .as_array()
                .expect("workspace files")
                .iter()
                .map(|file| {
                    file["relativePath"]
                        .as_str()
                        .expect("relative path")
                        .to_string()
                })
        })
        .collect::<Vec<_>>();
    assert_eq!(relative_paths.len(), 500);
    assert!(relative_paths.windows(2).all(|pair| pair[0] < pair[1]));
    assert_eq!(
        relative_paths.first().map(String::as_str),
        Some("src/main/kotlin/sample/Source0000.kt")
    );
    assert_eq!(
        relative_paths.last().map(String::as_str),
        Some("src/main/kotlin/sample/Source0499.kt")
    );
}
