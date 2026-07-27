#[test]
fn repository_negative_answers_follow_coverage_state() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
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
fn repository_incomplete_coverage_rejects_positive_answer() {
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

    assert!(!status.success(), "{response:#}");
    assert_eq!(
        response["code"], "REPOSITORY_COVERAGE_INCOMPLETE",
        "{response:#}"
    );
    assert_eq!(
        response["details"]["coverageLimitations"],
        "SEMANTIC_GRAPH_FILES_STALE",
        "{response:#}"
    );
    assert_eq!(
        response["details"]["remedy"],
        "Run `kast developer runtime up --workspace-root \"$PWD\" --backend idea --accept-indexing`; wait until `kast ready --for kotlin` succeeds; refresh each stale or missing Kotlin file with `kast agent graph --workspace-root \"$PWD\" --operation refresh --file-path <path-to-kotlin-file>`; then retry the repository query.",
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
        !agent_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&agent_output.stdout),
        String::from_utf8_lossy(&agent_output.stderr)
    );
    let agent_error: serde_json::Value =
        serde_json::from_slice(&agent_output.stdout).expect("agent error JSON");
    assert_eq!(
        agent_error["error"]["code"], "REPOSITORY_COVERAGE_INCOMPLETE",
        "{agent_error:#}"
    );
}
