#[test]
fn repository_scope_is_strict_and_build_qualified() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    let coverage_request = |id: &str, module: &str| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "graph/coverage",
            "params": {
                "workspaceRoot": workspace,
                "scope": {
                    "language": "kotlin",
                    "module": module,
                    "sourceSet": "main"
                }
            }
        })
    };

    let (unique_status, unique) = rpc(
        &home,
        &config_home,
        &workspace,
        coverage_request("unique-short", "app"),
    );
    assert!(unique_status.success(), "{unique:#}");
    assert_eq!(unique["result"]["coverage"]["total"], 1);

    seed_included_build_app(&fixture);
    for (module, expected) in [(".#:app", ".#:app"), ("included#:app", "included#:app")] {
        let (status, response) = rpc(
            &home,
            &config_home,
            &workspace,
            coverage_request(module, module),
        );
        assert!(status.success(), "{module}: {response:#}");
        assert_eq!(response["result"]["coverage"]["total"], 1);
        assert_eq!(
            response["result"]["coverage"]["modules"][0]["name"],
            expected
        );
    }

    fixture
        .connection()
        .execute("DROP TABLE semantic_files", [])
        .expect("remove semantic execution authority");

    let repository_request = |id: &str| {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "repository/query",
            "params": {
                "workspaceRoot": workspace,
                "question": "Resolve semanticGraphOperation.",
                "intent": "resolve",
                "scope": {
                    "language": "kotlin",
                    "module": "app",
                    "sourceSet": "main"
                },
                "limits": {"depth": 1, "results": 10, "evidence": 2}
            }
        })
    };
    let (ambiguous_status, ambiguous) = rpc(
        &home,
        &config_home,
        &workspace,
        repository_request("ambiguous"),
    );
    assert!(!ambiguous_status.success(), "{ambiguous:#}");
    assert_eq!(
        ambiguous["code"], "AMBIGUOUS_REPOSITORY_SCOPE",
        "{ambiguous:#}"
    );
    let message = ambiguous["message"].as_str().expect("ambiguity message");
    let root_candidate = message.find(".#:app").expect("root candidate");
    let included_candidate = message
        .find("included#:app")
        .expect("included-build candidate");
    assert!(root_candidate < included_candidate, "{message}");

    let mut unknown_request = repository_request("unknown-request");
    unknown_request["params"]["queston"] = serde_json::json!("typo");
    let mut unknown_envelope = repository_request("unknown-envelope");
    unknown_envelope["trace"] = serde_json::json!(true);
    let mut unknown_scope = repository_request("unknown-scope");
    unknown_scope["params"]["scope"]["moduel"] = serde_json::json!("app");
    let mut unknown_limits = repository_request("unknown-limits");
    unknown_limits["params"]["limits"]["reslts"] = serde_json::json!(10);
    let mut unknown_coverage = coverage_request("unknown-coverage", ".#:app");
    unknown_coverage["params"]["afterPth"] = serde_json::json!("Source.kt");
    let mut unknown_coverage_scope = coverage_request("unknown-coverage-scope", ".#:app");
    unknown_coverage_scope["params"]["scope"]["relations"] = serde_json::json!(["CALLS"]);

    for (request, expected_code) in [
        (unknown_request, "INVALID_REPOSITORY_QUERY"),
        (unknown_envelope, "INVALID_REPOSITORY_QUERY"),
        (unknown_scope, "INVALID_REPOSITORY_QUERY"),
        (unknown_limits, "INVALID_REPOSITORY_QUERY"),
        (unknown_coverage, "INVALID_GRAPH_COVERAGE_REQUEST"),
        (unknown_coverage_scope, "INVALID_GRAPH_COVERAGE_REQUEST"),
    ] {
        let (status, response) = rpc(&home, &config_home, &workspace, request);
        assert!(!status.success(), "{response:#}");
        assert_eq!(response["code"], expected_code, "{response:#}");
        assert!(
            response["message"]
                .as_str()
                .is_some_and(|message| message.contains("unknown field")),
            "{response:#}"
        );
    }
}

#[test]
fn repository_scope_completes_while_unrelated_module_remains_pending() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    fixture
        .connection()
        .execute_batch(
            "DELETE FROM file_gradle_source_sets
                 WHERE filename = 'Source0001.kt';
             UPDATE file_gradle_projects
                 SET project_path = ':other'
                 WHERE filename = 'Source0001.kt';
             INSERT INTO file_gradle_source_sets(
                 prefix_id, filename, build_root, project_path, source_set_name
             ) VALUES (1, 'Source0001.kt', '.', ':other', 'main');
             DELETE FROM file_stage_outcomes
                 WHERE filename = 'Source0001.kt' AND stage = 'SEMANTIC_GRAPH';",
        )
        .expect("make unrelated module pending");
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    fixture.seed_progress("other", "INDEXING", 0, 1);

    let (_, scoped) = rpc(
        &home,
        &config_home,
        &workspace,
        graph_coverage_page_request("scoped-complete", None, 100),
    );
    let (_, unscoped) = rpc(
        &home,
        &config_home,
        &workspace,
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": "unscoped-partial",
            "method": "graph/coverage",
            "params": {"scope": {"language": "kotlin"}}
        }),
    );

    assert_eq!(scoped["result"]["coverage"]["complete"], true, "{scoped:#}");
    assert_eq!(scoped["result"]["coverage"]["total"], 1, "{scoped:#}");
    assert_eq!(
        unscoped["result"]["coverage"]["pending"],
        1,
        "{unscoped:#}"
    );
}
