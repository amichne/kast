#[test]
fn repository_relationship_preserves_expect_actual_edges() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_expect_actual_relationship(&fixture);

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "expect-actual-relationship",
            "method": "repository/query",
            "params": {
                "question": "Show outgoing EXPECT_ACTUAL relationships from PlatformClock.",
                "intent": "outgoing_impact",
                "scope": {
                    "language": "kotlin",
                    "relations": ["EXPECT_ACTUAL"],
                    "maxDepth": 1
                },
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(
        serde_json::json!({
            "status": response["result"]["status"],
            "nodes": response["result"]["nodes"]
                .as_array()
                .expect("relationship nodes")
                .iter()
                .map(|node| node["canonicalKey"].clone())
                .collect::<Vec<_>>(),
            "relationships": response["result"]["edges"]
                .as_array()
                .expect("relationship edges")
                .iter()
                .map(|edge| serde_json::json!({
                    "sourceKey": edge["sourceKey"],
                    "targetKey": edge["targetKey"],
                    "kind": edge["kind"],
                    "evidenceClass": edge["evidenceClass"]
                }))
                .collect::<Vec<_>>()
        }),
        serde_json::json!({
            "status": "ANSWERED",
            "nodes": [
                "class:actual:PlatformClock",
                "class:expect:CommonClock"
            ],
            "relationships": [{
                "sourceKey": "class:actual:PlatformClock",
                "targetKey": "class:expect:CommonClock",
                "kind": "EXPECT_ACTUAL",
                "evidenceClass": "compiler"
            }]
        }),
        "{response:#}"
    );
}
