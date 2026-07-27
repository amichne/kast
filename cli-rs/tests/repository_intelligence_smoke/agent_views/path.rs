fn assert_agent_path_views(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace_root: &str,
) {
    let path_args = [
        "agent",
        "repository",
        "--workspace-root",
        workspace_root,
        "--question",
        "Trace outgoing CALLS from semanticGraphOperation to SemanticGraphSha256.parse.",
        "--intent",
        "path",
        "--relation",
        "calls",
        "--direction",
        "outgoing",
        "--depth",
        "6",
        "--results",
        "10",
        "--evidence",
        "1",
    ];
    let path_output = kast(home, config_home)
        .args(["--output", "json"])
        .args(path_args)
        .output()
        .expect("path agent repository");
    assert!(
        path_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&path_output.stdout),
        String::from_utf8_lossy(&path_output.stderr)
    );
    let path: serde_json::Value =
        serde_json::from_slice(&path_output.stdout).expect("path repository JSON");
    assert_eq!(
        path["result"]["paths"]
            .as_array()
            .and_then(|paths| paths.last())
            .and_then(|path| path.get("canonicalKeys")),
        Some(&serde_json::json!([
            "callable:semanticGraphOperation",
            "callable:buildSemanticGraphSnapshot",
            "callable:SemanticGraphSha256.parse"
        ])),
        "{path:#}"
    );
    assert!(
        path["result"]["relationships"]
            .as_array()
            .is_some_and(|relationships| relationships.iter().all(|relationship| {
                relationship["firstOccurrence"]["path"].is_string()
                    || relationship["derivation"]["rule"].is_string()
            })),
        "{path:#}"
    );
    let selected_path_output = kast(home, config_home)
        .args(["--output", "json"])
        .args(path_args)
        .args(["--fields", "paths,relationships"])
        .output()
        .expect("selected path agent repository");
    assert!(selected_path_output.status.success());
    let selected_path: serde_json::Value = serde_json::from_slice(&selected_path_output.stdout)
        .expect("selected path repository JSON");
    assert_eq!(
        selected_path["result"]["type"], "KAST_AGENT_REPOSITORY_SELECTION",
        "{selected_path:#}"
    );
    assert!(
        selected_path["result"]["paths"].is_array(),
        "{selected_path:#}"
    );
    for view in ["--verbose", "--explain", "--count"] {
        let output = kast(home, config_home)
            .args(["--output", "json"])
            .args(path_args)
            .arg(view)
            .output()
            .expect("path repository view");
        assert!(
            output.status.success(),
            "view={view} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }
}
