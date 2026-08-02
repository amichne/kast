fn indexed_label_source_hash(workspace: &std::path::Path) -> String {
    hex::encode(Sha256::digest(
        std::fs::read(workspace.join("src/main/kotlin/sample/Source0000.kt"))
            .expect("compiler-indexed source"),
    ))
}

fn write_label_index(workspace: &std::path::Path, artifact: serde_json::Value) {
    std::fs::write(
        workspace.join("repository-labels.json"),
        serde_json::to_vec_pretty(&artifact).expect("label index JSON"),
    )
    .expect("label index");
}

fn label_artifact(entries: serde_json::Value) -> serde_json::Value {
    serde_json::json!({
        "type": "KAST_REPOSITORY_LABEL_INDEX",
        "schemaVersion": 1,
        "entries": entries
    })
}

fn label_query(id: &str, question: &str) -> serde_json::Value {
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "method": "repository/query",
        "params": {
            "question": question,
            "labelIndex": "repository-labels.json",
            "intent": "resolve",
            "scope": {"language": "kotlin"},
            "limits": {"depth": 1, "results": 10, "evidence": 2}
        }
    })
}

#[test]
fn labels_retrieve_only_compiler_resolved_candidates() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    write_label_index(
        &workspace,
        label_artifact(serde_json::json!([{
                "canonicalKey": "callable:buildSemanticGraphSnapshot",
                "contentHash": indexed_label_source_hash(&workspace),
                "labels": ["repository topology assembler"]
        }])),
    );

    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        label_query(
            "label-retrieval",
            "Resolve the repository topology assembler.",
        ),
    );

    assert_eq!(
        serde_json::json!({
            "success": status.success(),
            "status": response["result"]["status"],
            "selectedIdentity": response["result"]["selectedIdentity"],
            "discovery": response["result"]["queryPlan"]["discovery"],
            "candidateLookup": response["result"]["queryPlan"]["candidateLookup"],
            "matchReason": response["result"]["candidates"][0]["matchReasons"][0]["field"],
            "evidenceClass": response["result"]["nodes"][0]["evidenceClass"]
        }),
        serde_json::json!({
            "success": true,
            "status": "ANSWERED",
            "selectedIdentity": "callable:buildSemanticGraphSnapshot",
            "discovery": "LEXICAL_WITH_PRECOMPUTED_LABELS",
            "candidateLookup": "compiler-symbol ranking with retrieval-only precomputed labels",
            "matchReason": "precomputedLabel",
            "evidenceClass": "compiler"
        }),
        "{response:#}"
    );

    let output = kast(&home, &config_home)
        .args([
            "agent",
            "repository",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--question",
            "Resolve the repository topology assembler.",
            "--label-index",
            "repository-labels.json",
            "--intent",
            "resolve",
        ])
        .output()
        .expect("label-assisted agent query");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let agent: serde_json::Value = toon_format::decode_default(
        std::str::from_utf8(&output.stdout)
            .expect("agent UTF-8")
            .trim(),
    )
    .expect("agent TOON");
    assert_eq!(
        serde_json::json!({
            "discovery": agent["result"]["discovery"],
            "selectedIdentity": agent["result"]["selectedIdentity"]
        }),
        serde_json::json!({
            "discovery": "LEXICAL_WITH_PRECOMPUTED_LABELS",
            "selectedIdentity": "callable:buildSemanticGraphSnapshot"
        }),
        "{agent:#}"
    );

    let (status, baseline) = rpc(
        &home,
        &config_home,
        &workspace,
        label_query(
            "unrelated-label",
            "Resolve SemanticGraphSha256 exactly.",
        ),
    );
    assert!(status.success(), "{baseline:#}");
    assert_eq!(
        baseline["result"]["selectedIdentity"],
        "class:SemanticGraphSha256",
        "{baseline:#}"
    );
}

#[test]
fn label_index_fails_loudly_for_malformed_or_incompatible_artifacts() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let hash = indexed_label_source_hash(&workspace);
    let entry = serde_json::json!({
        "canonicalKey": "callable:buildSemanticGraphSnapshot",
        "contentHash": hash,
        "labels": ["topology"]
    });
    let cases = [
        (serde_json::json!({"schemaVersion": 1}), "INVALID_REPOSITORY_LABEL_INDEX"),
        (
            serde_json::json!({
                "type": "KAST_REPOSITORY_LABEL_INDEX",
                "schemaVersion": 2,
                "entries": [entry.clone()]
            }),
            "UNSUPPORTED_REPOSITORY_LABEL_INDEX",
        ),
        (
            serde_json::json!({
                "type": "x".repeat(4_096),
                "schemaVersion": 1,
                "entries": [entry.clone()]
            }),
            "INVALID_REPOSITORY_LABEL_INDEX",
        ),
        (
            label_artifact(serde_json::json!([entry.clone(), entry.clone()])),
            "INVALID_REPOSITORY_LABEL_INDEX",
        ),
        (
            label_artifact(serde_json::json!([{
                "canonicalKey": "callable:buildSemanticGraphSnapshot",
                "contentHash": indexed_label_source_hash(&workspace),
                "labels": ["topology"],
                "path": "forged.kt"
            }])),
            "INVALID_REPOSITORY_LABEL_INDEX",
        ),
    ];
    for (index, (artifact, expected)) in cases.into_iter().enumerate() {
        write_label_index(&workspace, artifact);
        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            label_query(&format!("invalid-{index}"), "Find topology."),
        );
        assert!(!status.success(), "{response:#}");
        assert_eq!(response["code"], expected, "{response:#}");
        assert!(
            serde_json::to_string(&response).expect("error JSON").len() < 2_000,
            "error output was not bounded: {response:#}"
        );
    }

    std::fs::write(workspace.join("repository-labels.json"), b"{not-json")
        .expect("malformed label index");
    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        label_query("malformed-json", "Find topology."),
    );
    assert!(!status.success(), "{response:#}");
    assert_eq!(
        response["code"], "INVALID_REPOSITORY_LABEL_INDEX",
        "{response:#}"
    );
}

#[test]
fn label_index_rejects_missing_or_changed_compiler_identity() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let indexed_hash = indexed_label_source_hash(&workspace);
    let cases = [
        (
            "callable:missing",
            indexed_hash.clone(),
            "canonical identity is absent",
        ),
        (
            "callable:buildSemanticGraphSnapshot",
            "0".repeat(64),
            "content hash changed",
        ),
    ];
    for (canonical_key, content_hash, expected_cause) in cases {
        write_label_index(
            &workspace,
            label_artifact(serde_json::json!([{
                "canonicalKey": canonical_key,
                "contentHash": content_hash,
                "labels": ["topology"]
            }])),
        );
        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            label_query(canonical_key, "Find topology."),
        );
        assert!(!status.success(), "{response:#}");
        assert_eq!(
            response["code"], "REPOSITORY_LABEL_INDEX_STALE",
            "{response:#}"
        );
        assert_eq!(response["details"]["canonicalKey"], canonical_key);
        assert!(
            response["details"]["cause"]
                .as_str()
                .is_some_and(|cause| cause.contains(expected_cause)),
            "{response:#}"
        );
        assert!(response["details"]["remedy"].is_string(), "{response:#}");
    }

    write_label_index(
        &workspace,
        label_artifact(serde_json::json!([{
            "canonicalKey": "callable:buildSemanticGraphSnapshot",
            "contentHash": indexed_hash,
            "labels": ["topology"]
        }])),
    );
    fixture
        .connection()
        .execute("UPDATE semantic_files SET content_hash = NULL", [])
        .expect("remove compiler content hash");
    let (status, response) = rpc(
        &home,
        &config_home,
        &workspace,
        label_query("invalid-compiler-hash", "Find topology."),
    );
    assert!(!status.success(), "{response:#}");
    assert_eq!(response["code"], "REPOSITORY_INDEX_INVALID", "{response:#}");
}

#[test]
fn label_index_is_resolve_only_and_scope_independent() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute_batch(
            "UPDATE file_metadata
                 SET module_path = 'indexer.other.main'
                 WHERE filename = 'Source0001.kt';
             DELETE FROM file_gradle_source_sets
                 WHERE filename = 'Source0001.kt';
             UPDATE file_gradle_projects
                 SET project_path = ':other'
                 WHERE filename = 'Source0001.kt';
             INSERT INTO file_gradle_source_sets
                 (prefix_id, filename, build_root, project_path, source_set_name)
                 VALUES (1, 'Source0001.kt', '.', ':other', 'main');
             INSERT INTO semantic_symbols
                 (id, stable_key, file_id, kind, name, fq_name, start_offset, end_offset, line)
                 VALUES
                 (10, 'callable:outsideScope', 2, 'FUNCTION', 'outsideScope',
                  'other.outsideScope', 0, 10, 1);",
        )
        .expect("second compiler scope");
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    fixture.seed_progress("other", "COMPLETE", 1, 1);
    let outside_hash = hex::encode(Sha256::digest(
        std::fs::read(workspace.join("src/main/kotlin/sample/Source0001.kt"))
            .expect("outside-scope source"),
    ));
    write_label_index(
        &workspace,
        label_artifact(serde_json::json!([
            {
                "canonicalKey": "callable:buildSemanticGraphSnapshot",
                "contentHash": indexed_label_source_hash(&workspace),
                "labels": ["topology"]
            },
            {
                "canonicalKey": "callable:outsideScope",
                "contentHash": outside_hash,
                "labels": ["outside scope sentinel"]
            }
        ])),
    );
    let mut scoped = label_query("scoped", "Find topology.");
    scoped["params"]["scope"]["module"] = serde_json::json!("app");
    let (status, response) = rpc(&home, &config_home, &workspace, scoped);
    assert!(status.success(), "{response:#}");
    assert_eq!(
        response["result"]["selectedIdentity"],
        "callable:buildSemanticGraphSnapshot",
        "{response:#}"
    );
    assert_eq!(
        response["result"]["queryPlan"]["discovery"],
        "LEXICAL_WITH_PRECOMPUTED_LABELS"
    );

    let mut regex = label_query("regex-label", "topology");
    regex["params"]["querySyntax"] = serde_json::json!("regex");
    let mut exact = label_query("exact-label", "Find topology.");
    exact["params"]["canonicalKey"] =
        serde_json::json!("callable:buildSemanticGraphSnapshot");
    let mut path = label_query("path-label", "Find topology.");
    path["params"]["intent"] = serde_json::json!("path");
    for request in [regex, exact, path] {
        let (status, response) = rpc(&home, &config_home, &workspace, request);
        assert!(!status.success(), "{response:#}");
        assert_eq!(response["code"], "INVALID_REPOSITORY_QUERY", "{response:#}");
        assert!(
            response["message"]
                .as_str()
                .is_some_and(|message| message.contains("labelIndex")),
            "{response:#}"
        );
    }
}
