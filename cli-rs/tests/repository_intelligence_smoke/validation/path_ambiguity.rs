#[test]
fn repository_path_preserves_overloaded_target_ambiguity() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute_batch(
            "INSERT INTO semantic_symbols
                 (id, stable_key, file_id, owner_id, kind, name, fq_name, signature, start_offset, end_offset, line)
             VALUES
                 (10, 'callable:resolveTarget.Int', 1, NULL, 'FUNCTION', 'resolveTarget',
                  'sample.resolveTarget', 'sample.resolveTarget|-||kotlin.Int|0', 500, 510, 50),
                 (11, 'callable:resolveTarget.String', 1, NULL, 'FUNCTION', 'resolveTarget',
                  'sample.resolveTarget', 'sample.resolveTarget|-||kotlin.String|0', 511, 520, 51);
             INSERT INTO semantic_edge_occurrences
                 (id, source_id, target_id, source_file_id, kind, context, resolved_target_id, start_offset, end_offset, line)
             VALUES
                 (80, 3, 10, 1, 'CALLS', 'CALL', 10, 500, 510, 50),
                 (81, 3, 11, 1, 'CALLS', 'CALL', 11, 511, 520, 51);",
        )
        .expect("direct calls to both overloaded targets");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "overloaded-path-target",
            "method": "repository/query",
            "params": {
                "question": "Trace outgoing CALLS from semanticGraphOperation to resolveTarget.",
                "intent": "path",
                "scope": {
                    "language": "kotlin",
                    "relations": ["CALLS"],
                    "direction": "OUTGOING"
                },
                "limits": {"depth": 6, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(response["result"]["status"], "AMBIGUOUS", "{response:#}");
    assert_eq!(
        response["result"]["nodes"].as_array().map(|nodes| {
            nodes
                .iter()
                .map(|node| node["canonicalKey"].as_str().expect("canonical key"))
                .collect::<Vec<_>>()
        }),
        Some(vec![
            "callable:resolveTarget.Int",
            "callable:resolveTarget.String"
        ]),
        "{response:#}"
    );
    assert_eq!(
        response["result"]["paths"].as_array().map(Vec::len),
        Some(0),
        "{response:#}"
    );
    assert_eq!(
        response["result"]["edges"].as_array().map(Vec::len),
        Some(0),
        "{response:#}"
    );
}
