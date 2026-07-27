fn assert_agent_architecture_views(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace_root: &str,
) {
    let architecture_args = [
        "agent",
        "repository",
        "--workspace-root",
        workspace_root,
        "--question",
        "Which runtime call cycles cross package boundaries?",
        "--intent",
        "architecture",
        "--projection",
        "runtime-calls",
        "--metric",
        "scc",
        "--results",
        "10",
        "--evidence",
        "1",
    ];
    for view in [None, Some("--verbose"), Some("--explain")] {
        let mut command = kast(home, config_home);
        command.args(["--output", "json"]).args(architecture_args);
        if let Some(view) = view {
            command.arg(view);
        }
        let output = command.output().expect("architecture agent repository");
        assert!(
            output.status.success(),
            "view={view:?} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        let architecture: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("architecture repository JSON");
        let finding = &architecture["result"]["findings"][0];
        assert!(finding["trigger"].is_object(), "{architecture:#}");
        assert!(
            finding["representativeSymbols"].is_array(),
            "{architecture:#}"
        );
        assert!(
            finding["supportingSubgraph"]["edges"].is_array(),
            "{architecture:#}"
        );
        assert!(
            finding["relationComposition"].is_object(),
            "{architecture:#}"
        );
    }
    for view in [["--fields", "findings"].as_slice(), ["--count"].as_slice()] {
        let output = kast(home, config_home)
            .args(["--output", "json"])
            .args(architecture_args)
            .args(view)
            .output()
            .expect("bounded architecture view");
        assert!(
            output.status.success(),
            "view={view:?} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }
}
