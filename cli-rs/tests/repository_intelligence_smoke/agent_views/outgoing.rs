fn assert_agent_outgoing_views(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    question: &str,
    args: &[&str],
) {
    let compact_output = published_semantic_command(home, config_home, workspace)
        .args(args.iter().copied())
        .output()
        .expect("compact agent repository");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr),
    );
    let compact_raw = String::from_utf8(compact_output.stdout).expect("compact UTF-8");
    let compact: serde_json::Value =
        toon_format::decode_default(compact_raw.trim()).expect("compact repository TOON");
    assert_eq!(compact["method"], "agent/repository", "{compact:#}");
    assert_eq!(
        compact["result"]["type"], "KAST_AGENT_REPOSITORY_RESULT",
        "{compact:#}"
    );
    assert_eq!(compact["result"]["status"], "ANSWERED", "{compact:#}");
    assert_eq!(compact["result"]["generation"], 41, "{compact:#}");
    assert_eq!(
        compact["result"]["coverage"]["complete"], true,
        "{compact:#}"
    );
    assert_eq!(compact["result"]["bounds"]["results"], 10, "{compact:#}");
    assert_eq!(
        compact["result"]["cardinality"]["relationships"]["returned"], 10,
        "{compact:#}"
    );
    assert_eq!(
        compact["result"]["cardinality"]["relationships"]["completeness"], "LOWER_BOUND",
        "{compact:#}"
    );
    assert_eq!(
        compact["result"]["relationships"].as_array().map(Vec::len),
        Some(10),
        "{compact:#}"
    );
    assert_eq!(compact["result"]["truncated"], true, "{compact:#}");
    assert!(compact.get("request").is_none(), "{compact:#}");
    assert!(compact.get("response").is_none(), "{compact:#}");
    assert!(
        compact["result"]["relationships"][0]["sourceKey"].is_string()
            && compact["result"]["relationships"][0]["targetKey"].is_string()
            && compact["result"]["relationships"][0]["kind"] == "CALLS"
            && compact["result"]["relationships"][0]["firstOccurrence"]["path"].is_string(),
        "{compact:#}"
    );
    let compact_tokens = tiktoken_rs::cl100k_base()
        .expect("cl100k_base")
        .encode_with_special_tokens(&compact_raw)
        .len();
    assert!(
        compact_tokens <= 1_500,
        "compact repository output used {compact_tokens} tokens:\n{compact_raw}"
    );

    let canonical_request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "repository/query",
        "params": {
            "question": question,
            "intent": "outgoing_impact",
            "scope": {
                "language": "kotlin",
                "relations": ["CALLS"],
                "maxDepth": 1
            },
            "limits": {"depth": 1, "results": 10, "evidence": 1}
        }
    });
    let canonical_output = rpc_output(home, config_home, workspace, "json", &canonical_request);
    assert!(canonical_output.status.success());
    let canonical: serde_json::Value =
        serde_json::from_slice(&canonical_output.stdout).expect("canonical repository JSON");
    assert!(
        compact_raw.len() * 2 < canonical_output.stdout.len(),
        "compact={} canonical={}",
        compact_raw.len(),
        canonical_output.stdout.len()
    );

    let verbose_output = published_semantic_command(home, config_home, workspace)
        .args(["--output", "json"])
        .args(args.iter().copied())
        .arg("--verbose")
        .output()
        .expect("verbose agent repository");
    assert!(
        verbose_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&verbose_output.stdout),
        String::from_utf8_lossy(&verbose_output.stderr),
    );
    let verbose: serde_json::Value =
        serde_json::from_slice(&verbose_output.stdout).expect("verbose repository JSON");
    assert_eq!(verbose["method"], "agent/repository", "{verbose:#}");
    assert_eq!(verbose["result"], canonical["result"], "{verbose:#}");

    let explain_output = published_semantic_command(home, config_home, workspace)
        .args(["--output", "json"])
        .args(args.iter().copied())
        .arg("--explain")
        .output()
        .expect("explain agent repository");
    assert!(explain_output.status.success());
    let explain: serde_json::Value =
        serde_json::from_slice(&explain_output.stdout).expect("explain repository JSON");
    assert_eq!(explain["result"], canonical["result"], "{explain:#}");

    let count_output = published_semantic_command(home, config_home, workspace)
        .args(["--output", "json"])
        .args(args.iter().copied())
        .arg("--count")
        .output()
        .expect("count agent repository");
    assert!(count_output.status.success());
    let count: serde_json::Value =
        serde_json::from_slice(&count_output.stdout).expect("count repository JSON");
    assert_eq!(
        count["result"]["type"], "KAST_AGENT_REPOSITORY_COUNT",
        "{count:#}"
    );
    assert_eq!(
        count["result"]["cardinality"]["relationships"]["completeness"], "LOWER_BOUND",
        "{count:#}"
    );
    let selected_output = published_semantic_command(home, config_home, workspace)
        .args(["--output", "json"])
        .args(args.iter().copied())
        .args(["--fields", "relationships"])
        .output()
        .expect("selected outgoing repository");
    assert!(selected_output.status.success());
    let selected: serde_json::Value =
        serde_json::from_slice(&selected_output.stdout).expect("selected outgoing JSON");
    assert!(
        selected["result"]["relationships"].is_array(),
        "{selected:#}"
    );
}
