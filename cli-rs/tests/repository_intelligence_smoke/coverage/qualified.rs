mod coverage {
    mod negative {
        use super::super::*;

        #[test]
        fn repository_missing_semantic_tables_rejects_instead_of_empty() {
            let (_temp, home, config_home, workspace, _fixture) = coverage_fixture();
            let (status, response) = rpc(
                &home,
                &config_home,
                &workspace,
                serde_json::json!({
                    "jsonrpc": "2.0",
                    "id": "missing-semantic-tables",
                    "method": "repository/query",
                    "params": {
                        "question": "Does DefinitelyMissing exist?",
                        "intent": "resolve",
                        "scope": {"language": "kotlin"},
                        "limits": {"depth": 1, "results": 10, "evidence": 2}
                    }
                }),
            );

            assert!(!status.success(), "{response:#}");
            assert_eq!(response["code"], "REPOSITORY_INDEX_INVALID", "{response:#}");
            assert_eq!(
                response["details"]["remedy"],
                "Run `kast developer runtime up --workspace-root \"$PWD\" --backend idea --accept-indexing`, then rebuild compiler graph evidence with `kast agent graph --workspace-root \"$PWD\" --operation refresh --file-path <path-to-kotlin-file>`.",
                "{response:#}"
            );
        }
    }
}

#[test]
fn repository_incomplete_coverage_returns_qualified_positive_answer() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "UPDATE file_manifest SET content_hash = ? WHERE filename = 'Source0001.kt'",
            params!["e".repeat(64)],
        )
        .expect("advance unrelated persisted source hash");

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
    assert!(response["result"]["qualification"].is_string(), "{response:#}");
    assert_eq!(
        response["result"]["nodes"][0]["canonicalKey"],
        "callable:semanticGraphOperation",
        "{response:#}"
    );

    let agent_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Resolve semanticGraphOperation.",
            "--intent",
            "resolve",
            "--canonical-key",
            "callable:semanticGraphOperation",
        ])
        .output()
        .expect("incomplete-coverage agent result");
    assert!(
        agent_output.status.success(),
        "{}",
        String::from_utf8_lossy(&agent_output.stdout)
    );
    let agent_response: serde_json::Value =
        serde_json::from_slice(&agent_output.stdout).expect("agent response JSON");
    assert_eq!(
        agent_response["result"]["status"],
        "ANSWERED",
        "{agent_response:#}"
    );
    assert!(
        agent_response["result"]["qualification"].is_string(),
        "{agent_response:#}"
    );
    assert_eq!(
        agent_response["result"]["cardinality"]["identities"]["completeness"],
        "LOWER_BOUND",
        "{agent_response:#}"
    );
}

#[test]
fn limited_semantic_outcome_preserves_positive_facts_with_qualified_coverage() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET outcome_status = 'LIMITED',
                 limitations_json = '[\"UNRESOLVED_RELATIONSHIP\"]'
             WHERE stage = 'SEMANTIC_GRAPH' AND filename = 'Source0000.kt'",
            [],
        )
        .expect("limit semantic graph outcome");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "limited-positive",
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
    assert_eq!(response["result"]["coverage"]["limited"], 1, "{response:#}");
    assert!(response["result"]["qualification"].is_string(), "{response:#}");
    assert_eq!(
        response["result"]["nodes"][0]["canonicalKey"],
        "callable:semanticGraphOperation",
        "{response:#}"
    );
}

#[test]
fn limited_semantic_outcome_cannot_produce_exact_empty() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET outcome_status = 'LIMITED',
                 limitations_json = '[\"UNRESOLVED_RELATIONSHIP\"]'
             WHERE stage = 'SEMANTIC_GRAPH' AND filename = 'Source0000.kt'",
            [],
        )
        .expect("limit semantic graph outcome");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "limited-negative",
            "method": "repository/query",
            "params": {
                "question": "Does DefinitelyMissing exist?",
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(response["result"]["status"], "QUALIFIED_EMPTY", "{response:#}");
}

#[test]
fn external_relationship_boundary_is_qualified_instead_of_pending() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    let failure_id = uuid::Uuid::new_v4().hyphenated().to_string();
    fixture
        .connection()
        .execute_batch(&format!(
            "DELETE FROM file_stage_outcomes
             WHERE stage = 'SEMANTIC_GRAPH' AND filename = 'Source0000.kt';
             UPDATE file_stage_outcomes
             SET outcome_status = 'EXTERNAL_BOUNDARY',
                 limitations_json = '[]',
                 failure_id = '{failure_id}',
                 failure_code = 'PSI_UNAVAILABLE',
                 failure_message = 'PSI is unavailable'
             WHERE stage = 'RELATIONSHIPS' AND filename = 'Source0000.kt';"
        ))
        .expect("external relationship boundary");

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("external-boundary", None, 100),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(response["result"]["coverage"]["pending"], 0, "{response:#}");
    assert_eq!(response["result"]["coverage"]["limited"], 1, "{response:#}");
    assert_eq!(response["result"]["files"][0]["state"], "LIMITED", "{response:#}");
    assert_eq!(
        response["result"]["files"][0]["reasonCode"],
        "SEMANTIC_GRAPH_EXTERNAL_BOUNDARY",
        "{response:#}"
    );
}
