#[test]
fn rpc_exposes_generation_pinned_complete_graph_coverage() {
    let (_temp, home, config_home, workspace, _fixture) = coverage_fixture();
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "coverage",
        "method": "graph/coverage",
        "params": {
            "scope": {"language": "kotlin", "module": "app", "sourceSet": "main"}
        }
    });

    let (status, response) = rpc(&home, &config_home, &workspace, request);

    assert!(status.success(), "{response:#}");
    assert_eq!(response["id"], "coverage");
    assert_eq!(response["result"]["generation"], 41);
    assert_eq!(response["result"]["inventoryGeneration"], 41);
    assert_eq!(response["result"]["graphGeneration"], 41);
    assert_eq!(response["result"]["coverage"]["total"], 1);
    assert_eq!(response["result"]["coverage"]["indexed"], 1);
    assert_eq!(response["result"]["coverage"]["excluded"], 0);
    assert_eq!(response["result"]["coverage"]["failed"], 0);
    assert_eq!(response["result"]["coverage"]["stale"], 0);
    assert_eq!(response["result"]["coverage"]["complete"], true);
    assert_eq!(
        response["result"]["coverage"]["eligibleForCompleteNegative"],
        true
    );
    assert_eq!(response["result"]["appliedFilters"]["module"], "app");
    assert_eq!(response["result"]["appliedFilters"]["sourceSet"], "main");
}
