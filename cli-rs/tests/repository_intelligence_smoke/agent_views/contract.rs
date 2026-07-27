fn assert_agent_intent_failures(
    home: &std::path::Path,
    config_home: &std::path::Path,
    fixture: &WorkspaceIndexFixture,
    workspace_root: &str,
    args: &[&str],
) {
    assert!(
        !default_descriptor_dir(home).exists(),
        "local repository query must not discover or start a daemon"
    );

    fixture
        .connection()
        .execute("DROP TABLE semantic_symbols", [])
        .expect("remove traversal authority");
    let missing_projection = kast(home, config_home)
        .args([
            "--output",
            "json",
            "agent",
            "repository",
            "--workspace-root",
            workspace_root,
            "--question",
            "Find architecture.",
            "--intent",
            "architecture",
        ])
        .output()
        .expect("missing architecture projection");
    assert!(!missing_projection.status.success());
    let missing_projection: serde_json::Value =
        serde_json::from_slice(&missing_projection.stdout).expect("missing projection JSON");
    assert_eq!(
        missing_projection["error"]["code"], "INVALID_REPOSITORY_QUERY",
        "{missing_projection:#}"
    );
    let invalid = kast(home, config_home)
        .args(args.iter().copied())
        .args(["--depth", "7"])
        .output()
        .expect("invalid repository bounds");
    assert_eq!(invalid.status.code(), Some(2));
}

#[test]
fn agent_repository_query_preserves_all_intent_contracts() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_high_cardinality_outgoing_calls(&fixture);
    let workspace_root = workspace.to_str().expect("workspace");
    let question = "Show outgoing relationships from semanticGraphOperation.";
    let args = [
        "agent",
        "repository",
        "--workspace-root",
        workspace_root,
        "--question",
        question,
        "--intent",
        "outgoing-impact",
        "--language",
        "kotlin",
        "--relation",
        "calls",
        "--max-depth",
        "1",
        "--depth",
        "1",
        "--results",
        "10",
        "--evidence",
        "1",
    ];
    assert_agent_outgoing_views(&home, &config_home, &workspace, question, &args);
    assert_agent_path_views(&home, &config_home, workspace_root);
    assert_agent_architecture_views(&home, &config_home, workspace_root);
    assert_remaining_agent_intent_views(&home, &config_home, workspace_root);
    assert_agent_intent_failures(&home, &config_home, &fixture, workspace_root, &args);
}
