#[test]
fn repository_query_rejects_intent_irrelevant_fields_before_execution() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    fixture
        .connection()
        .execute("DROP TABLE semantic_files", [])
        .expect("remove semantic execution authority");
    let request = |id: &str, intent: &str| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "repository/query",
            "params": {
                "question": "Inspect the repository contract.",
                "intent": intent,
                "canonicalKey": null,
                "scope": {
                    "language": "kotlin",
                    "relations": [],
                    "direction": null,
                    "maxDepth": null,
                    "projection": null,
                    "metric": null,
                    "sources": []
                },
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };
    let cases = [
        (
            "canonical-key",
            "path",
            "/params/canonicalKey",
            serde_json::json!("callable:sample.target"),
            "canonicalKey",
        ),
        (
            "relations",
            "resolve",
            "/params/scope/relations",
            serde_json::json!(["CALLS"]),
            "relations",
        ),
        (
            "max-depth",
            "resolve",
            "/params/scope/maxDepth",
            serde_json::json!(1),
            "maxDepth",
        ),
        (
            "direction",
            "resolve",
            "/params/scope/direction",
            serde_json::json!("OUTGOING"),
            "direction",
        ),
        (
            "projection",
            "path",
            "/params/scope/projection",
            serde_json::json!("RUNTIME_CALLS"),
            "projection",
        ),
        (
            "metric",
            "path",
            "/params/scope/metric",
            serde_json::json!("BRIDGES"),
            "metric",
        ),
        (
            "sources",
            "path",
            "/params/scope/sources",
            serde_json::json!(["markdown"]),
            "sources",
        ),
        (
            "depth-bound",
            "path",
            "/params/scope/maxDepth",
            serde_json::json!(2),
            "maxDepth",
        ),
    ];

    for (id, intent, pointer, value, expected_field) in cases {
        let mut invalid = request(id, intent);
        *invalid.pointer_mut(pointer).expect("contract field") = value;
        let (status, response) = rpc(&home, &config_home, &workspace, invalid);

        assert!(!status.success(), "{id}: {response:#}");
        assert_eq!(
            response["code"], "INVALID_REPOSITORY_QUERY",
            "{id}: {response:#}"
        );
        assert!(
            response["message"]
                .as_str()
                .is_some_and(|message| message.contains(expected_field)),
            "{id}: {response:#}"
        );
    }

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        request("architecture-projection", "architecture"),
    );
    assert!(!status.success(), "{response:#}");
    assert_eq!(response["code"], "INVALID_REPOSITORY_QUERY", "{response:#}");
    assert!(
        response["message"]
            .as_str()
            .is_some_and(|message| message.contains("projection")),
        "{response:#}"
    );
}
