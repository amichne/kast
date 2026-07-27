fn assert_resolution_and_architecture_views(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
) {
    let (_, discovery) = rpc(
        home,
        config_home,
        workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "discovery",
            "method": "repository/query",
            "params": {
                "question": "Find the function that builds a semantic graph snapshot.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(discovery["result"]["status"], "ANSWERED", "{discovery:#}");
    assert_eq!(discovery["result"]["queryPlan"]["discovery"], "LEXICAL");
    assert_eq!(
        discovery["result"]["candidates"][0]["name"],
        "buildSemanticGraphSnapshot"
    );
    assert!(
        discovery["result"]["candidates"][0]["matchReasons"]
            .as_array()
            .is_some_and(|reasons| !reasons.is_empty())
    );

    let canonical_key = discovery["result"]["candidates"][0]["canonicalKey"]
        .as_str()
        .expect("discovery candidate has canonical identity");
    let (_, exact_key) = rpc(
        home,
        config_home,
        workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "exact-key",
            "method": "repository/query",
            "params": {
                "question": "This prose must not affect exact-key lookup.",
                "intent": "resolve",
                "canonicalKey": canonical_key,
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(exact_key["result"]["status"], "ANSWERED", "{exact_key:#}");
    assert_eq!(exact_key["result"]["queryPlan"]["discovery"], "EXACT_KEY");
    assert_eq!(
        exact_key["result"]["selectedIdentity"],
        serde_json::Value::String(canonical_key.to_string())
    );
    assert_eq!(
        exact_key["result"]["candidates"].as_array().map(Vec::len),
        Some(1)
    );

    let (_, ambiguous) = rpc(
        home,
        config_home,
        workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "ambiguous",
            "method": "repository/query",
            "params": {
                "question": "Resolve parse.",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(ambiguous["result"]["status"], "AMBIGUOUS", "{ambiguous:#}");
    assert!(ambiguous["result"]["selectedIdentity"].is_null());
    assert!(
        ambiguous["result"]["candidates"]
            .as_array()
            .is_some_and(|candidates| candidates.len() == 2)
    );

    let (_, architecture) = rpc(
        home,
        config_home,
        workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "architecture",
            "method": "repository/query",
            "params": {
                "question": "Which internal declarations are incoming runtime call hubs?",
                "intent": "architecture",
                "scope": {
                    "language": "kotlin",
                    "projection": "RUNTIME_CALLS",
                    "direction": "INCOMING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(
        architecture["result"]["status"], "ANSWERED",
        "{architecture:#}"
    );
    assert_eq!(
        architecture["result"]["findings"][0]["type"],
        "HIGH_CENTRALITY_INTERNAL_IMPLEMENTATION"
    );
    assert_eq!(
        architecture["result"]["findings"][0]["projection"],
        "RUNTIME_CALLS"
    );
    assert!(
        architecture["result"]["findings"][0]["supportingSubgraph"]["edges"]
            .as_array()
            .is_some_and(|edges| !edges.is_empty())
    );
}
