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
                "Run `kast developer runtime up --workspace-root \"$PWD\" --backend headless --accept-indexing`, then rebuild compiler graph evidence with `kast agent graph --workspace-root \"$PWD\" --operation refresh --file-path <path-to-kotlin-file>`.",
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
            "UPDATE file_stage_outcomes
             SET stage_version = 'semantic-graph-old'
             WHERE stage = 'SEMANTIC_GRAPH' AND filename = 'Source0001.kt'",
            [],
        )
        .expect("stale unrelated semantic graph version");

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
fn external_relationship_boundary_does_not_change_graph_coverage() {
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
    assert_eq!(response["result"]["coverage"]["pending"], 1, "{response:#}");
    assert_eq!(response["result"]["coverage"]["limited"], 0, "{response:#}");
    assert_eq!(response["result"]["files"][0]["state"], "PENDING", "{response:#}");
    assert_eq!(
        response["result"]["files"][0]["reasonCode"],
        "SEMANTIC_GRAPH_MISSING",
        "{response:#}"
    );
}

#[test]
fn critical_graph_stale_file_rejects_read_admission() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    write_critical_paths(&fixture, "src/main/kotlin/sample/Source0001.kt");
    fixture
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET stage_version = 'semantic-graph-old'
             WHERE stage = 'SEMANTIC_GRAPH' AND filename = 'Source0001.kt'",
            [],
        )
        .expect("stale critical semantic graph version");

    let (status, response) = graph_summary(&home, &config_home, &workspace);

    assert!(!status.success(), "{response:#}");
    assert_eq!(
        response["error"], "GRAPH_EVIDENCE_INCOMPLETE",
        "{response:#}"
    );
}

#[test]
fn critical_graph_unmatched_pattern_rejects_read_admission() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    seed_repository_graph(&fixture);
    write_critical_paths(&fixture, "src/required/**");

    let (status, response) = graph_summary(&home, &config_home, &workspace);

    assert!(!status.success(), "{response:#}");
    assert_eq!(
        response["error"], "GRAPH_EVIDENCE_INCOMPLETE",
        "{response:#}"
    );
}

#[test]
fn critical_graph_noncritical_stale_file_remains_qualified() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture_with_file_count(2);
    seed_repository_graph(&fixture);
    fixture
        .connection()
        .execute(
            "UPDATE file_stage_outcomes
             SET stage_version = 'semantic-graph-old'
             WHERE stage = 'SEMANTIC_GRAPH' AND filename = 'Source0001.kt'",
            [],
        )
        .expect("stale noncritical semantic graph version");

    let (status, response) = graph_summary(&home, &config_home, &workspace);

    assert!(status.success(), "{response:#}");
    assert_eq!(response["qualification"], "QUALIFIED", "{response:#}");
}

fn write_critical_paths(fixture: &WorkspaceIndexFixture, pattern: &str) {
    let config_path = fixture
        .database_path()
        .parent()
        .and_then(std::path::Path::parent)
        .expect("workspace data directory")
        .join("config.toml");
    std::fs::write(
        config_path,
        format!("[indexing]\ncriticalPaths = [\"{pattern}\"]\n"),
    )
    .expect("critical path config");
}

fn graph_summary(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
) -> (std::process::ExitStatus, serde_json::Value) {
    use std::os::unix::process::CommandExt;

    let mut command = std::process::Command::new(env!("CARGO_BIN_EXE_kast"));
    command.arg0("kast");
    let output = command
        .current_dir(workspace)
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home)
        .args(["graph", "summary"])
        .output()
        .expect("graph summary");
    let response = toon_format::decode_default(
        std::str::from_utf8(&output.stdout)
            .expect("graph summary UTF-8")
            .trim(),
    )
    .unwrap_or_else(|error| {
        panic!(
            "graph summary TOON: {error}; stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        )
    });
    (output.status, response)
}
