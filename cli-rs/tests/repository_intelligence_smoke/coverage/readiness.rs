fn ready_runtime(workspace: &std::path::Path) -> serde_json::Value {
    serde_json::json!({
        "state": "READY",
        "healthy": true,
        "active": true,
        "indexing": false,
        "backendName": "idea",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "sourceModuleNames": ["app"],
        "referenceIndexReady": true,
        "schemaVersion": 5
    })
}

fn semantic_graph_capabilities(workspace: &std::path::Path) -> serde_json::Value {
    serde_json::json!({
        "backendName": "idea",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "readCapabilities": ["SEMANTIC_GRAPH"],
        "mutationCapabilities": [],
        "limits": {
            "requestTimeoutMillis": 60000,
            "maxResults": 1000,
            "maxConcurrentRequests": 4
        },
        "schemaVersion": 5
    })
}

fn remove_semantic_graph_coverage(fixture: &WorkspaceIndexFixture) {
    fixture
        .connection()
        .execute(
            "DELETE FROM file_stage_outcomes WHERE stage = 'SEMANTIC_GRAPH'",
            [],
        )
        .expect("remove semantic graph coverage");
}

#[test]
fn status_separates_runtime_readiness_from_incomplete_semantic_graph_coverage() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    remove_semantic_graph_coverage(&fixture);
    let socket_path = workspace.join("status.sock");
    let backend = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            ("runtime/status", ready_runtime(&workspace)),
            ("capabilities", semantic_graph_capabilities(&workspace)),
        ],
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("status");
    backend.join().expect("status backend");
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("status JSON");

    assert_eq!(result["selected"]["ready"], true, "{result}");
    assert_eq!(
        result["selected"]["runtimeStatus"]["state"],
        "READY",
        "{result}"
    );
    assert_eq!(
        result["selected"]["runtimeStatus"]["healthy"],
        true,
        "{result}"
    );
    assert_eq!(result["semanticGraph"]["state"], "INCOMPLETE", "{result}");
    assert_eq!(result["semanticGraph"]["total"], 1, "{result}");
    assert_eq!(result["semanticGraph"]["pending"], 1, "{result}");
    assert_eq!(result["semanticGraph"]["stale"], 0, "{result}");
}

#[test]
fn status_omits_semantic_graph_coverage_while_runtime_is_indexing() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    std::fs::remove_file(fixture.database_path()).expect("remove graph database");
    let socket_path = workspace.join("status-indexing.sock");
    let backend = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            (
                "runtime/status",
                serde_json::json!({
                    "state": "INDEXING",
                    "healthy": true,
                    "active": true,
                    "indexing": true,
                    "backendName": "idea",
                    "backendVersion": "scripted-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "sourceModuleNames": ["app"],
                    "referenceIndexReady": false,
                    "schemaVersion": 5
                }),
            ),
            ("capabilities", semantic_graph_capabilities(&workspace)),
        ],
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("indexing status");
    backend.join().expect("indexing status backend");
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("indexing status JSON");

    assert_eq!(result["selected"]["ready"], false, "{result}");
    assert_eq!(
        result["selected"]["runtimeStatus"]["state"],
        "INDEXING",
        "{result}"
    );
    assert!(
        result.get("semanticGraph").is_none(),
        "indexing status must not scan graph coverage: {result}"
    );
}

#[test]
fn verify_fails_incomplete_semantic_graph_coverage_without_discarding_runtime_evidence() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    remove_semantic_graph_coverage(&fixture);
    let socket_path = workspace.join("verify.sock");
    let runtime = ready_runtime(&workspace);
    let capabilities = semantic_graph_capabilities(&workspace);
    let backend = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            ("runtime/status", runtime.clone()),
            ("capabilities", capabilities.clone()),
            ("health", serde_json::json!({"ok": true, "status": "READY"})),
            ("runtime/status", runtime),
            ("capabilities", capabilities),
        ],
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("verify");
    backend.join().expect("verify backend");
    assert!(
        !output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("verify JSON");

    assert_eq!(result["ok"], false, "{result}");
    assert_eq!(result["result"]["runtime"]["state"], "READY", "{result}");
    assert_eq!(
        result["result"]["runtime"]["healthy"],
        true,
        "{result}"
    );
    assert_eq!(
        result["result"]["semanticGraph"]["state"],
        "INCOMPLETE",
        "{result}"
    );
    assert_eq!(result["result"]["semanticGraph"]["pending"], 1, "{result}");
    assert_eq!(
        result["error"]["code"],
        "SEMANTIC_GRAPH_COVERAGE_INCOMPLETE",
        "{result}"
    );
    assert!(
        result["error"]["message"]
            .as_str()
            .is_some_and(|message| message.contains("--file-path <path-to-kotlin-file>")),
        "{result}"
    );
}

#[test]
fn verify_verbose_reports_execution_level_semantic_graph_issue() {
    let (_temp, home, config_home, workspace, fixture) = coverage_fixture();
    remove_semantic_graph_coverage(&fixture);
    let socket_path = workspace.join("verify-verbose.sock");
    let runtime = ready_runtime(&workspace);
    let capabilities = semantic_graph_capabilities(&workspace);
    let backend = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            ("runtime/status", runtime.clone()),
            ("capabilities", capabilities.clone()),
            ("health", serde_json::json!({"ok": true, "status": "READY"})),
            ("runtime/status", runtime),
            ("capabilities", capabilities),
        ],
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--verbose",
        ])
        .output()
        .expect("verbose verify");
    backend.join().expect("verbose verify backend");
    assert!(
        !output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("verbose verify JSON");

    assert_eq!(result["ok"], false, "{result}");
    assert_eq!(result["result"]["ok"], false, "{result}");
    assert_eq!(
        result["result"]["issues"][0]["code"],
        "SEMANTIC_GRAPH_COVERAGE_INCOMPLETE",
        "{result}"
    );
    assert_eq!(
        result["result"]["semanticGraph"]["state"],
        "INCOMPLETE",
        "{result}"
    );
    assert_eq!(
        result["error"]["code"],
        "SEMANTIC_GRAPH_COVERAGE_INCOMPLETE",
        "{result}"
    );
    assert_eq!(
        result["error"]["details"]["issues"][0]["code"],
        "SEMANTIC_GRAPH_COVERAGE_INCOMPLETE",
        "{result}"
    );
}
