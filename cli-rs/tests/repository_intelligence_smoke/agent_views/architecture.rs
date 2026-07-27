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
    let compact_output = kast(home, config_home)
        .args(architecture_args)
        .output()
        .expect("compact architecture agent repository");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr)
    );
    let compact_raw = String::from_utf8(compact_output.stdout).expect("compact architecture UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact architecture TOON");
    let compact_finding = &compact["result"]["findings"][0];
    assert!(
        compact_finding["rank"].is_number()
            && compact_finding["type"].is_string()
            && compact_finding["name"].is_string()
            && compact_finding["summary"].is_string()
            && compact_finding["projection"].is_string()
            && compact_finding["metric"].is_string()
            && compact_finding["graphGeneration"].is_number()
            && compact_finding["evidenceClass"].is_string(),
        "{compact:#}"
    );
    for deep_field in [
        "trigger",
        "representativeSymbols",
        "supportingSubgraph",
        "relationComposition",
        "derivation",
    ] {
        assert!(
            compact_finding.get(deep_field).is_none(),
            "compact finding retained {deep_field}: {compact:#}"
        );
    }
    assert_eq!(
        compact["result"]["findingEvidence"]["status"],
        "OMITTED_IN_COMPACT_VIEW",
        "{compact:#}"
    );
    assert_eq!(
        compact["result"]["findingEvidence"]["help"],
        "Rerun this command with --fields findings or --explain for full finding evidence.",
        "{compact:#}"
    );
    assert_eq!(compact_raw.matches("--fields findings").count(), 1);
    assert_eq!(compact_raw.matches("--explain").count(), 1);
    let compact_tokens = tiktoken_rs::cl100k_base()
        .expect("cl100k_base")
        .encode_with_special_tokens(&compact_raw)
        .len();
    assert!(
        compact_tokens <= 1_500,
        "compact architecture output used {compact_tokens} tokens:\n{compact_raw}"
    );

    for (view, canonical) in [
        (&["--verbose"][..], true),
        (&["--explain"][..], true),
        (&["--fields", "findings"][..], false),
    ] {
        let output = kast(home, config_home)
            .args(["--output", "json"])
            .args(architecture_args)
            .args(view)
            .output()
            .expect("detailed architecture agent repository");
        assert!(
            output.status.success(),
            "view={view:?} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        let architecture: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("architecture repository JSON");
        assert!(
            architecture["result"].get("findingEvidence").is_none(),
            "{architecture:#}"
        );
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
        assert!(finding["derivation"].is_object(), "{architecture:#}");
        if canonical {
            assert!(finding["relationTypes"].is_array(), "{architecture:#}");
            assert!(finding["scope"].is_object(), "{architecture:#}");
            assert!(
                finding["supportingSubgraph"]["edges"][0]["occurrences"].is_array(),
                "{architecture:#}"
            );
        }
    }
    let count = kast(home, config_home)
        .args(["--output", "json"])
        .args(architecture_args)
        .arg("--count")
        .output()
        .expect("count architecture view");
    assert!(count.status.success());
}
