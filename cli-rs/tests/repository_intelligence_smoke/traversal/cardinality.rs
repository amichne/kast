#[test]
fn repository_traversal_continuation_is_admissible_at_high_cardinality() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_outgoing_calls(&fixture, 10_000..12_500);
    let mut continuation = serde_json::Value::Null;
    let mut seen = std::collections::BTreeSet::new();

    for page in 0..=6 {
        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": format!("high-cardinality-{page}"),
                "method": "repository/query",
                "params": {
                    "question": "Show outgoing relationships from semanticGraphOperation.",
                    "intent": "outgoing_impact",
                    "scope": {
                        "language": "kotlin",
                        "relations": ["CALLS"],
                        "maxDepth": 1
                    },
                    "limits": {"depth": 1, "results": 500, "evidence": 1},
                    "continuation": continuation
                }
            }),
        );
        assert!(status.success(), "page {page}: {response:#}");
        for identity in response["result"]["edges"]
            .as_array()
            .expect("repository relationships")
            .iter()
            .map(|edge| {
                (
                    edge["sourceKey"].as_str().expect("edge source").to_string(),
                    edge["targetKey"].as_str().expect("edge target").to_string(),
                    edge["kind"].as_str().expect("edge kind").to_string(),
                    edge["context"].as_str().expect("edge context").to_string(),
                )
            })
        {
            assert!(seen.insert(identity.clone()), "replayed {identity:?}");
        }
        let Some(token) = response["result"]["continuation"].as_str() else {
            assert_eq!(seen.len(), 2_502, "{response:#}");
            return;
        };
        assert!(
            token.len() <= 16_384,
            "page {page} emitted an inadmissible {} byte continuation",
            token.len()
        );
        continuation = serde_json::Value::String(token.to_string());
    }

    panic!("high-cardinality traversal did not terminate");
}
