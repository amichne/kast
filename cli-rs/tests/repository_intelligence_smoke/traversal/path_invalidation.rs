fn assert_path_invalidation(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    fixture: &WorkspaceIndexFixture,
    continuation: serde_json::Value,
) {
    let (_, wrong_direction) = rpc(
        home,
        config_home,
        workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "wrong-direction",
            "method": "repository/query",
            "params": {
                "question": "List outgoing CALLS made by SemanticGraphSha256.parse.",
                "intent": "outgoing_impact",
                "scope": {"language": "kotlin", "relations": ["CALLS"], "maxDepth": 1},
                "limits": {"depth": 6, "results": 10, "evidence": 5}
            }
        }),
    );
    assert_eq!(
        wrong_direction["result"]["status"], "EMPTY",
        "{wrong_direction:#}"
    );

    fixture
        .connection()
        .execute("DROP TABLE semantic_symbols", [])
        .expect("remove graph traversal authority");
    let (pre_traversal_status, pre_traversal) = rpc(
        home,
        config_home,
        workspace,
        repository_path_page_request("pre-traversal-rejection", continuation.clone(), 10),
    );
    assert!(!pre_traversal_status.success(), "{pre_traversal:#}");
    assert_eq!(
        pre_traversal["code"], "INVALID_REPOSITORY_CONTINUATION",
        "{pre_traversal:#}"
    );

    fixture
        .connection()
        .execute("UPDATE schema_version SET generation = 42", [])
        .expect("advance graph generation");
    let (stale_status, stale) = rpc(
        home,
        config_home,
        workspace,
        repository_path_page_request("stale", continuation, 1),
    );
    assert!(!stale_status.success(), "{stale:#}");
    assert_eq!(stale["code"], "STALE_REPOSITORY_CONTINUATION", "{stale:#}");
}
