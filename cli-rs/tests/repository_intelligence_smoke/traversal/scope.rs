#[test]
fn repository_traversal_is_scope_closed_and_snapshot_bound() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_out_of_scope_repository_target(&fixture);

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "scope-closed",
            "method": "repository/query",
            "params": {
                "question": "Show outgoing calls from semanticGraphOperation.",
                "intent": "outgoing_impact",
                "scope": {
                    "language": "kotlin",
                    "module": "app",
                    "sourceSet": "main",
                    "relations": ["CALLS"],
                    "maxDepth": 1
                },
                "limits": {"depth": 1, "results": 50, "evidence": 1}
            }
        }),
    );

    assert!(status.success(), "{response:#}");
    assert_eq!(response["result"]["generation"], 41, "{response:#}");
    assert_eq!(
        response["result"]["inventoryGeneration"], 41,
        "{response:#}"
    );
    assert_eq!(response["result"]["graphGeneration"], 41, "{response:#}");
    assert_eq!(response["result"]["coverage"]["total"], 1, "{response:#}");
    assert!(
        response["result"]["nodes"]
            .as_array()
            .is_some_and(|nodes| nodes.iter().all(|node| {
                node["path"]
                    .as_str()
                    .is_some_and(|path| path.starts_with("src/main/kotlin/sample/"))
            })),
        "{response:#}"
    );
    assert!(
        response["result"]["edges"]
            .as_array()
            .is_some_and(|edges| edges.iter().all(|edge| {
                edge["sourceKey"] != "callable:outsideScope"
                    && edge["targetKey"] != "callable:outsideScope"
            })),
        "{response:#}"
    );
}

#[test]
fn repository_traversal_continuation_resumes_without_replay_or_drift() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    seed_high_cardinality_outgoing_calls(&fixture);
    let question = "Show outgoing relationships from semanticGraphOperation.";

    let (first_status, first_page) = agent_repository_traversal_page(
        &home,
        &config_home,
        &workspace,
        AgentRepositoryTraversalRequest::new(question),
    );
    assert!(first_status.success(), "{first_page:#}");
    assert_eq!(first_page["result"]["truncated"], true, "{first_page:#}");
    let first_continuation = first_page["result"]["continuation"]
        .as_str()
        .expect("truncated relationship page continuation")
        .to_string();

    for (label, changed_question, results, module, source_set, verbose) in [
        (
            "query",
            "Show outgoing CALLS relationships from semanticGraphOperation.",
            10,
            None,
            None,
            false,
        ),
        ("scope", question, 10, Some("app"), Some("main"), false),
        ("limit", question, 11, None, None, true),
    ] {
        let (status, response) = agent_repository_traversal_page(
            &home,
            &config_home,
            &workspace,
            AgentRepositoryTraversalRequest {
                question: changed_question,
                results,
                module,
                source_set,
                continuation: Some(&first_continuation),
                verbose,
            },
        );
        assert!(!status.success(), "{label}: {response:#}");
        assert_eq!(
            response["error"]["code"], "INVALID_REPOSITORY_CONTINUATION",
            "{label}: {response:#}"
        );
    }

    let mut forged = first_continuation.as_bytes().to_vec();
    let final_byte = forged.last_mut().expect("continuation signature");
    *final_byte = if *final_byte == b'0' { b'1' } else { b'0' };
    let forged = String::from_utf8(forged).expect("ASCII continuation");
    let (forged_status, forged_response) = agent_repository_traversal_page(
        &home,
        &config_home,
        &workspace,
        AgentRepositoryTraversalRequest {
            continuation: Some(&forged),
            ..AgentRepositoryTraversalRequest::new(question)
        },
    );
    assert!(!forged_status.success(), "{forged_response:#}");
    assert_eq!(
        forged_response["error"]["code"], "INVALID_REPOSITORY_CONTINUATION",
        "{forged_response:#}"
    );

    let source_path = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let source = std::fs::read(&source_path).expect("indexed Kotlin source");
    std::fs::write(&source_path, b"changed after traversal page")
        .expect("change coverage composition");
    let (changed_status, changed_response) = agent_repository_traversal_page(
        &home,
        &config_home,
        &workspace,
        AgentRepositoryTraversalRequest {
            continuation: Some(&first_continuation),
            ..AgentRepositoryTraversalRequest::new(question)
        },
    );
    assert!(!changed_status.success(), "{changed_response:#}");
    assert_eq!(
        changed_response["error"]["code"], "STALE_REPOSITORY_CONTINUATION",
        "{changed_response:#}"
    );
    std::fs::write(&source_path, source).expect("restore indexed Kotlin source");

    let mut expected = std::collections::BTreeSet::from([
        (
            "callable:semanticGraphOperation".to_string(),
            "callable:buildSemanticGraphSnapshot".to_string(),
            "CALLS".to_string(),
            "CALL".to_string(),
        ),
        (
            "callable:semanticGraphOperation".to_string(),
            "callable:cycleTarget".to_string(),
            "CALLS".to_string(),
            "CALL".to_string(),
        ),
        (
            "callable:cycleTarget".to_string(),
            "callable:semanticGraphOperation".to_string(),
            "CALLS".to_string(),
            "CALL".to_string(),
        ),
        (
            "callable:buildSemanticGraphSnapshot".to_string(),
            "callable:SemanticGraphSha256.parse".to_string(),
            "CALLS".to_string(),
            "CALL".to_string(),
        ),
    ]);
    expected.extend((100..200).map(|id| {
        (
            "callable:semanticGraphOperation".to_string(),
            format!("callable:target{id}"),
            "CALLS".to_string(),
            "CALL".to_string(),
        )
    }));
    let mut seen = repository_relationship_identities(&first_page);
    let mut continuation = Some(first_continuation.clone());
    for _ in 1..=expected.len() {
        let Some(token) = continuation.take() else {
            break;
        };
        let (status, page) = agent_repository_traversal_page(
            &home,
            &config_home,
            &workspace,
            AgentRepositoryTraversalRequest {
                continuation: Some(&token),
                ..AgentRepositoryTraversalRequest::new(question)
            },
        );
        assert!(status.success(), "{page:#}");
        for identity in repository_relationship_identities(&page) {
            assert!(seen.insert(identity.clone()), "replayed {identity:?}");
        }
        continuation = page["result"]["continuation"].as_str().map(str::to_string);
        assert_eq!(
            page["result"]["truncated"].as_bool(),
            Some(continuation.is_some()),
            "{page:#}"
        );
    }
    assert!(continuation.is_none(), "traversal did not terminate");
    assert_eq!(seen, expected);

    fixture
        .connection()
        .execute("UPDATE schema_version SET generation = 42", [])
        .expect("advance graph generation");
    let (stale_status, stale_response) = agent_repository_traversal_page(
        &home,
        &config_home,
        &workspace,
        AgentRepositoryTraversalRequest {
            continuation: Some(&first_continuation),
            ..AgentRepositoryTraversalRequest::new(question)
        },
    );
    assert!(!stale_status.success(), "{stale_response:#}");
    assert_eq!(
        stale_response["error"]["code"], "STALE_REPOSITORY_CONTINUATION",
        "{stale_response:#}"
    );

    let help = kast(&home, &config_home)
        .args(["agent", "repository", "--help"])
        .output()
        .expect("agent repository help");
    assert!(help.status.success());
    assert!(
        String::from_utf8_lossy(&help.stdout).contains("--continuation"),
        "{}",
        String::from_utf8_lossy(&help.stdout)
    );
}

#[test]
fn combined_traversal_evidence_limits_response_nodes() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let request = |id: &str,
                   continuation: Option<&str>,
                   evidence_continuation: Option<&str>| {
        let mut params = serde_json::json!({
            "question": "Show outgoing calls from semanticGraphOperation.",
            "intent": "outgoing_impact",
            "scope": {
                "language": "kotlin",
                "relations": ["CALLS"],
                "maxDepth": 2
            },
            "limits": {"depth": 2, "results": 1, "evidence": 1}
        });
        if let Some(continuation) = continuation {
            params["continuation"] = serde_json::json!(continuation);
        }
        if let Some(evidence_continuation) = evidence_continuation {
            params["evidenceContinuation"] = serde_json::json!(evidence_continuation);
        }
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "repository/query",
            "params": params
        })
    };

    let (_, first) = rpc(
        &home,
        &config_home,
        &workspace,
        request("later-evidence-1", None, None),
    );
    let first_continuation = first["result"]["continuation"]
        .as_str()
        .expect("first traversal continuation");
    let (_, second) = rpc(
        &home,
        &config_home,
        &workspace,
        request("later-evidence-2", Some(first_continuation), None),
    );
    let second_continuation = second["result"]["continuation"]
        .as_str()
        .expect("second traversal continuation");
    let (_, third) = rpc(
        &home,
        &config_home,
        &workspace,
        request("later-evidence-3", Some(second_continuation), None),
    );
    let traversal_continuation = third["result"]["continuation"]
        .as_str()
        .expect("later traversal continuation");
    let edge = &third["result"]["edges"][0];
    assert_eq!(
        (
            &edge["sourceKey"],
            &edge["targetKey"],
            &edge["occurrences"][0]["id"]
        ),
        (
            &serde_json::json!("callable:buildSemanticGraphSnapshot"),
            &serde_json::json!("callable:SemanticGraphSha256.parse"),
            &serde_json::json!(3)
        ),
        "{third:#}"
    );
    let evidence_continuation = edge["evidenceContinuation"]
        .as_str()
        .expect("later edge evidence continuation");

    let (status, evidence_page) = rpc(
        &home,
        &config_home,
        &workspace,
        request(
            "later-evidence-page",
            Some(traversal_continuation),
            Some(evidence_continuation),
        ),
    );

    assert!(status.success(), "{evidence_page:#}");
    let node_keys = evidence_page["result"]["nodes"]
        .as_array()
        .expect("repository nodes")
        .iter()
        .map(|node| {
            node["canonicalKey"]
                .as_str()
                .expect("repository node canonical key")
        })
        .collect::<std::collections::BTreeSet<_>>();
    assert_eq!(
        node_keys,
        std::collections::BTreeSet::from([
            "callable:semanticGraphOperation",
            "callable:buildSemanticGraphSnapshot",
            "callable:SemanticGraphSha256.parse",
        ]),
        "{evidence_page:#}"
    );
    assert_eq!(
        evidence_page["result"]["edges"][0]["occurrences"][0]["id"],
        4,
        "{evidence_page:#}"
    );
    assert_eq!(
        evidence_page["result"]["continuation"],
        traversal_continuation,
        "{evidence_page:#}"
    );
}
