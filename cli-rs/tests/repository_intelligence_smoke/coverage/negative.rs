#[test]
fn repository_negative_answers_follow_coverage_state() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    let request = |scope: serde_json::Value| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "negative",
            "method": "repository/query",
            "params": {
                "question": "Does DefinitelyMissing exist?",
                "intent": "resolve",
                "scope": scope,
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };

    let (status, complete) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{complete:#}");
    assert_eq!(complete["result"]["status"], "EMPTY");
    assert_eq!(complete["result"]["coverage"]["complete"], true);

    std::fs::write(
        workspace.join("src/main/kotlin/sample/Source0000.kt"),
        "package sample\nclass Changed\n",
    )
    .expect("stale source");
    let (status, stale) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{stale:#}");
    assert_eq!(stale["result"]["status"], "QUALIFIED_EMPTY");
    assert_eq!(stale["result"]["coverage"]["stale"], 1);
    assert!(stale["result"]["qualification"].is_string());

    fixture
        .connection()
        .execute("DELETE FROM semantic_files", [])
        .expect("remove semantic graph file");
    let (status, failed) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{failed:#}");
    assert_eq!(failed["result"]["coverage"]["failed"], 1);
    assert_eq!(failed["result"]["coverage"]["complete"], false);
    let (_, failed_coverage) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "failed-coverage",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert_eq!(
        failed_coverage["result"]["files"][0]["diagnostics"][0]["code"],
        "SEMANTIC_GRAPH_MISSING"
    );

    fixture
        .connection()
        .execute("DELETE FROM file_metadata", [])
        .expect("remove compilation ownership evidence");
    let (status, excluded) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({"language": "kotlin"})),
    );
    assert!(status.success(), "{excluded:#}");
    assert_eq!(excluded["result"]["coverage"]["excluded"], 1);
    assert_eq!(excluded["result"]["coverage"]["failed"], 0);
    assert_eq!(excluded["result"]["coverage"]["eligibilityProven"], false);
    assert_eq!(excluded["result"]["coverage"]["complete"], false);
    let (_, excluded_coverage) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "excluded-coverage",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );
    assert_eq!(
        excluded_coverage["result"]["files"][0]["reasonCode"],
        "SOURCE_INDEX_METADATA_UNAVAILABLE"
    );
}

#[test]
fn repository_partial_coverage_qualifies_positive_answers_truthfully() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    std::fs::write(
        workspace.join("src/main/kotlin/sample/Source0001.kt"),
        "package sample\nclass ChangedAfterIndexing\n",
    )
    .expect("stale unrelated source");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "partial-positive",
            "method": "repository/query",
            "params": {
                "question": "Resolve semanticGraphOperation.",
                "intent": "resolve",
                "canonicalKey": "callable:semanticGraphOperation",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(response["result"]["status"], "ANSWERED", "{response:#}");
    assert_eq!(response["result"]["coverage"]["stale"], 1, "{response:#}");
    let qualification = response["result"]["qualification"]
        .as_str()
        .expect("incomplete coverage qualification");
    assert!(
        !qualification.contains("No matching declaration"),
        "{response:#}"
    );
    assert!(
        qualification.contains("coverage is incomplete"),
        "{response:#}"
    );
}

#[test]
fn repository_human_partial_result_preserves_certainty() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    std::fs::write(
        workspace.join("src/main/kotlin/sample/Source0001.kt"),
        "package sample\nclass ChangedAfterIndexing\n",
    )
    .expect("stale unrelated source");
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "human-partial-positive",
        "method": "repository/query",
        "params": {
            "question": "Resolve semanticGraphOperation.",
            "intent": "resolve",
            "canonicalKey": "callable:semanticGraphOperation",
            "scope": {"language": "kotlin"},
            "limits": {"depth": 1, "results": 10, "evidence": 2}
        }
    });

    let output = rpc_output(&home, &config_home, &workspace, "human", &request);
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let human = String::from_utf8(output.stdout).expect("human repository output");
    for expected in [
        "- Coverage complete: false",
        "- Coverage total: 2",
        "- Coverage indexed: 1",
        "- Coverage stale: 1",
        "- Truncated: false",
        "- Traversal continuation available: false",
        "- Evidence continuation available: false",
        "This result is limited to the indexed portion of this scope because coverage is incomplete.",
    ] {
        assert!(human.contains(expected), "missing {expected:?}\n{human}");
    }
}
