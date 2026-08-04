fn assert_remaining_agent_intent_views(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace_root: &str,
) {
    for (intent_args, selected_field) in [
        (
            vec![
                "--question",
                "Resolve SemanticGraphSha256.parse exactly.",
                "--intent",
                "resolve",
                "--canonical-key",
                "callable:SemanticGraphSha256.parse",
            ],
            "identities",
        ),
        (
            vec![
                "--question",
                "Show incoming callers of semanticGraphOperation.",
                "--intent",
                "incoming-impact",
                "--relation",
                "calls",
                "--depth",
                "1",
            ],
            "relationships",
        ),
        (
            vec![
                "--question",
                "Which repository files document SemanticGraphSha256?",
                "--intent",
                "context-relationship",
                "--source",
                "gradle",
                "--results",
                "10",
            ],
            "context",
        ),
    ] {
        for view in [None, Some("--verbose"), Some("--explain"), Some("--count")] {
            let mut command = published_semantic_command(
                home,
                config_home,
                std::path::Path::new(workspace_root),
            );
            command
                .args([
                    "--output",
                    "json",
                    "agent",
                    "repository",
                    "--workspace-root",
                ])
                .arg(workspace_root)
                .args(&intent_args);
            if let Some(view) = view {
                command.arg(view);
            }
            let output = command.output().expect("remaining repository intent");
            assert!(
                output.status.success(),
                "view={view:?} stdout={} stderr={}",
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr)
            );
            let response: serde_json::Value =
                serde_json::from_slice(&output.stdout).expect("remaining repository intent JSON");
            assert_eq!(response["method"], "agent/repository", "{response:#}");
        }
        let selected = published_semantic_command(
            home,
            config_home,
            std::path::Path::new(workspace_root),
        )
            .args([
                "--output",
                "json",
                "agent",
                "repository",
                "--workspace-root",
            ])
            .arg(workspace_root)
            .args(&intent_args)
            .args(["--fields", selected_field])
            .output()
            .expect("selected repository intent");
        assert!(
            selected.status.success(),
            "field={selected_field} stdout={} stderr={}",
            String::from_utf8_lossy(&selected.stdout),
            String::from_utf8_lossy(&selected.stderr)
        );
    }
}
