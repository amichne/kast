#[test]
fn label_index_paths_and_size_are_bounded_before_reading() {
    let (temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let mut traversal = label_query("traversal", "Find topology.");
    traversal["params"]["labelIndex"] = serde_json::json!("../outside.json");
    let (status, response) = rpc(&home, &config_home, &workspace, traversal);
    assert!(!status.success(), "{response:#}");
    assert_eq!(
        response["code"], "INVALID_REPOSITORY_LABEL_INDEX",
        "{response:#}"
    );

    std::fs::create_dir(workspace.join("labels-dir")).expect("label directory");
    let mut directory = label_query("directory", "Find topology.");
    directory["params"]["labelIndex"] = serde_json::json!("labels-dir");
    let (status, response) = rpc(&home, &config_home, &workspace, directory);
    assert!(!status.success(), "{response:#}");
    assert_eq!(
        response["code"], "REPOSITORY_LABEL_INDEX_UNAVAILABLE",
        "{response:#}"
    );

    std::fs::write(
        workspace.join("repository-labels.json"),
        vec![b'x'; 8 * 1024 * 1024 + 1],
    )
    .expect("oversized label index");
    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        label_query("oversized", "Find topology."),
    );
    assert!(!status.success(), "{response:#}");
    assert_eq!(
        response["code"], "INVALID_REPOSITORY_LABEL_INDEX",
        "{response:#}"
    );

    #[cfg(unix)]
    {
        let outside = temp.path().join("outside-labels.json");
        std::fs::write(&outside, b"{}").expect("outside label index");
        std::os::unix::fs::symlink(&outside, workspace.join("escaped-labels.json"))
            .expect("escaping symlink");
        let mut escaped = label_query("escaped", "Find topology.");
        escaped["params"]["labelIndex"] = serde_json::json!("escaped-labels.json");
        let (status, response) = rpc(&home, &config_home, &workspace, escaped);
        assert!(!status.success(), "{response:#}");
        assert_eq!(
            response["code"], "REPOSITORY_LABEL_INDEX_UNAVAILABLE",
            "{response:#}"
        );
    }

    let output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Find topology.",
            "--label-index",
            "../escape.json",
            "--intent",
            "resolve",
        ])
        .output()
        .expect("invalid label index CLI");
    assert!(!output.status.success());
    let diagnostic = format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(
        diagnostic.contains("cannot escape the workspace"),
        "output={diagnostic}"
    );
}
