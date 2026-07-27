#[test]
fn repository_architecture_result_limits_are_truthful_and_bounded() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_architecture_boundary_targets(&fixture, 7);
    let request = |id: &str, results: usize| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "repository/query",
            "params": {
                "question": "Which Gradle ownership boundaries are crossed?",
                "intent": "architecture",
                "scope": {
                    "language": "kotlin",
                    "projection": "MODULE_DEPENDENCIES"
                },
                "limits": {"depth": 1, "results": results, "evidence": 1}
            }
        })
    };
    let high = rpc(
        &home,
        &config_home,
        &workspace,
        request("all-boundaries", 10),
    )
    .1;
    let repeated = rpc(
        &home,
        &config_home,
        &workspace,
        request("all-boundaries-repeated", 10),
    )
    .1;
    let target_modules = |response: &serde_json::Value| {
        response["result"]["findings"]
            .as_array()
            .expect("architecture findings")
            .iter()
            .map(|finding| {
                finding["trigger"]["targetModule"]
                    .as_str()
                    .expect("target module")
                    .to_string()
            })
            .collect::<Vec<_>>()
    };
    assert_eq!(
        (
            high["result"]["findings"].as_array().map(Vec::len),
            high["result"]["truncated"].as_bool(),
            target_modules(&high),
            target_modules(&repeated),
        ),
        (
            Some(7),
            Some(false),
            (0..7)
                .map(|index| format!("included{index}#:app{index}"))
                .collect::<Vec<_>>(),
            target_modules(&high),
        ),
        "{high:#}"
    );

    let low = rpc(
        &home,
        &config_home,
        &workspace,
        request("bounded-boundaries", 3),
    )
    .1;
    assert_eq!(
        (
            low["result"]["findings"].as_array().map(Vec::len),
            low["result"]["truncated"].as_bool(),
        ),
        (Some(3), Some(true)),
        "{low:#}"
    );

    let compact = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Which Gradle ownership boundaries are crossed?",
            "--intent",
            "architecture",
            "--projection",
            "module-dependencies",
            "--results",
            "3",
            "--evidence",
            "1",
        ])
        .output()
        .expect("compact bounded architecture");
    assert!(
        compact.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact.stdout),
        String::from_utf8_lossy(&compact.stderr)
    );
    let compact: serde_json::Value =
        toon_format::decode_default(String::from_utf8_lossy(&compact.stdout).trim())
            .expect("compact bounded architecture TOON");
    assert_eq!(
        (
            compact["result"]["cardinality"]["findings"]["returned"].as_u64(),
            compact["result"]["cardinality"]["findings"]["completeness"].as_str(),
            compact["result"]["truncated"].as_bool(),
        ),
        (Some(3), Some("LOWER_BOUND"), Some(true)),
        "{compact:#}"
    );
}
