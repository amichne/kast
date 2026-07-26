mod support;

use rusqlite::params;
use sha2::{Digest, Sha256};
use support::workspace_files::WorkspaceIndexFixture;
use support::*;

fn coverage_fixture() -> (
    tempfile::TempDir,
    std::path::PathBuf,
    std::path::PathBuf,
    std::path::PathBuf,
    WorkspaceIndexFixture,
) {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let data = default_install_root(&home).join("state/data/workspaces");
    std::fs::create_dir_all(data.join("local")).expect("workspace data");
    std::fs::write(
        data.join("local-workspaces.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            workspace.display().to_string(): "repository-intelligence"
        }))
        .expect("workspace registry JSON"),
    )
    .expect("workspace registry");
    let mut sanitized = String::new();
    for character in workspace.display().to_string().chars() {
        if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
            sanitized.push(character);
        } else if !sanitized.ends_with('-') {
            sanitized.push('-');
        }
    }
    let sanitized = sanitized
        .trim_matches('-')
        .chars()
        .take(80)
        .collect::<String>();
    let database = data
        .join("local")
        .join(format!("{sanitized}--repository-intelligence"))
        .join("cache/source-index.db");
    let fixture = WorkspaceIndexFixture::at_database_path(&workspace, &database);
    fixture.seed_high_cardinality_sources(1);
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    let path = "src/main/kotlin/sample/Source0000.kt";
    let content = std::fs::read(workspace.join(path)).expect("Kotlin source");
    fixture
        .connection()
        .execute_batch(
            "CREATE TABLE semantic_files (
                id INTEGER PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                package_name TEXT,
                module_name TEXT,
                content_hash TEXT,
                refresh_status TEXT NOT NULL,
                diagnostics_json TEXT NOT NULL
            );",
        )
        .expect("semantic graph schema");
    fixture
        .connection()
        .execute(
            "INSERT INTO semantic_files(path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
             VALUES (?, 'sample', 'app.main', ?, 'REFRESHED', '[]')",
            params![path, hex::encode(Sha256::digest(content))],
        )
        .expect("semantic graph file");
    (temp, home, config_home, workspace, fixture)
}

fn rpc(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    request: serde_json::Value,
) -> (std::process::ExitStatus, serde_json::Value) {
    let output = kast(home, config_home)
        .args([
            "--output",
            "json",
            "rpc",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--request",
            &request.to_string(),
        ])
        .output()
        .expect("rpc");
    let response = serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
        panic!(
            "rpc JSON: {error}; stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        )
    });
    (output.status, response)
}

#[test]
fn rpc_exposes_generation_pinned_complete_graph_coverage() {
    let (_temp, home, config_home, workspace, _fixture) = coverage_fixture();
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": "coverage",
        "method": "graph/coverage",
        "params": {
            "scope": {"language": "kotlin", "module": "app", "sourceSet": "main"}
        }
    });

    let (status, response) = rpc(&home, &config_home, &workspace, request);

    assert!(status.success(), "{response:#}");
    assert_eq!(response["id"], "coverage");
    assert_eq!(response["result"]["generation"], 41);
    assert_eq!(response["result"]["inventoryGeneration"], 41);
    assert_eq!(response["result"]["graphGeneration"], 41);
    assert_eq!(response["result"]["coverage"]["total"], 1);
    assert_eq!(response["result"]["coverage"]["indexed"], 1);
    assert_eq!(response["result"]["coverage"]["excluded"], 0);
    assert_eq!(response["result"]["coverage"]["failed"], 0);
    assert_eq!(response["result"]["coverage"]["stale"], 0);
    assert_eq!(response["result"]["coverage"]["complete"], true);
    assert_eq!(
        response["result"]["coverage"]["eligibleForCompleteNegative"],
        true
    );
    assert_eq!(response["result"]["appliedFilters"]["module"], "app");
    assert_eq!(response["result"]["appliedFilters"]["sourceSet"], "main");
}

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

    let (status, deliberate_partial) = rpc(
        &home,
        &config_home,
        &workspace,
        request(serde_json::json!({
            "language": "kotlin",
            "fixture": "incomplete-coverage"
        })),
    );
    assert!(status.success(), "{deliberate_partial:#}");
    assert_eq!(deliberate_partial["result"]["status"], "QUALIFIED_EMPTY");
    assert_eq!(
        deliberate_partial["result"]["coverage"]["eligibleForCompleteNegative"],
        false
    );
}
