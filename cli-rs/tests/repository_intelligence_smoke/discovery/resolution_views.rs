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

fn seed_return_type_family_collision(fixture: &WorkspaceIndexFixture) {
    fixture
        .connection()
        .execute_batch(
            "DELETE FROM semantic_edge_occurrences;
             DELETE FROM semantic_symbols WHERE id NOT IN (1, 3, 4);
             UPDATE semantic_types
             SET stable_key = 'type:sample.Snapshot',
                 classifier = 'sample.Snapshot',
                 debug_text = 'Snapshot'
             WHERE id = 1;
             UPDATE semantic_symbols
             SET stable_key = 'class:Snapshot',
                 name = 'Snapshot',
                 fq_name = 'sample.Snapshot'
             WHERE id = 1;
             UPDATE semantic_symbols
             SET stable_key = 'class:RelationshipModelTest',
                 kind = 'CLASS',
                 name = 'RelationshipModelTest',
                 fq_name = 'sample.RelationshipModelTest',
                 signature = NULL
             WHERE id = 3;
             UPDATE semantic_symbols
             SET stable_key = 'callable:relation',
                 name = 'relation',
                 fq_name = 'sample.relation',
                 signature = 'sample.relation|-|||sample.Snapshot|0',
                 return_type_id = 1
             WHERE id = 4;",
        )
        .expect("function-family collision fixture");
}

fn family_query(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    id: &str,
    question: &str,
) -> serde_json::Value {
    rpc(
        home,
        config_home,
        workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "repository/query",
            "params": {
                "question": question,
                "intent": "resolve",
                "scope": {"language": "kotlin"},
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        }),
    )
    .1
}

fn assert_top_candidate(
    response: &serde_json::Value,
    expected_kind: &str,
    expected_name: &str,
    expected_reason: &str,
) {
    let candidate = &response["result"]["candidates"][0];
    let reason_sum = candidate["matchReasons"]
        .as_array()
        .expect("match reasons")
        .iter()
        .map(|reason| reason["score"].as_u64().expect("reason score"))
        .sum::<u64>();
    assert_eq!(
        serde_json::json!([
            response["result"]["status"],
            candidate["kind"],
            candidate["name"],
            candidate["matchScore"],
            candidate["matchReasons"]
                .as_array()
                .expect("match reasons")
                .iter()
                .any(|reason| reason["field"] == expected_reason),
        ]),
        serde_json::json!(["ANSWERED", expected_kind, expected_name, reason_sum, true]),
        "{response:#}"
    );
}

#[test]
fn ordered_family_intent_recognizes_singular_and_plural_vocabulary() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_return_type_family_collision(&fixture);

    for (id, question) in [
        (
            "function-family",
            "Find the function that builds the Snapshot model.",
        ),
        (
            "functions-family",
            "Find the functions that build the Snapshot models.",
        ),
    ] {
        let response = family_query(&home, &config_home, &workspace, id, question);
        assert_top_candidate(&response, "FUNCTION", "relation", "exactReturnType");
    }
}

#[test]
fn reverse_return_type_family_evidence_beats_lexical_type_name_overlap() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_return_type_family_collision(&fixture);

    for (id, question) in [
        (
            "model-family",
            "Find the model returned by the relation function.",
        ),
        (
            "models-family",
            "Find the models returned by the relation functions.",
        ),
    ] {
        let response = family_query(&home, &config_home, &workspace, id, question);
        assert_top_candidate(
            &response,
            "CLASS",
            "Snapshot",
            "exactReturningCallable",
        );
    }
}
