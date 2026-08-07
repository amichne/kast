fn coverage_fixture() -> (
    tempfile::TempDir,
    std::path::PathBuf,
    std::path::PathBuf,
    std::path::PathBuf,
    WorkspaceIndexFixture,
) {
    coverage_fixture_with_file_count(1)
}

fn coverage_fixture_with_file_count(
    file_count: usize,
) -> (
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
    let git = std::process::Command::new("git")
        .args(["init", "--quiet"])
        .current_dir(&workspace)
        .status()
        .expect("initialize fixture Git repository");
    assert!(git.success(), "initialize fixture Git repository");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let data = default_install_root(&home).join("state/data/workspaces");
    let workspace_key = hex::encode(Sha256::digest(workspace.to_string_lossy().as_bytes()));
    let database = data
        .join(workspace_key)
        .join("cache/source-index.db");
    let fixture = WorkspaceIndexFixture::at_database_path(&workspace, &database);
    fixture.seed_high_cardinality_sources(file_count);
    let file_count = i64::try_from(file_count).expect("fixture file count");
    fixture.seed_progress("app", "COMPLETE", file_count, file_count);
    let connection = fixture.connection();
    connection
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
    for index in 0..file_count {
        let path = format!("src/main/kotlin/sample/Source{index:04}.kt");
        let content = std::fs::read(workspace.join(&path)).expect("Kotlin source");
        connection
            .execute(
            "INSERT INTO semantic_files(path, package_name, module_name, content_hash, refresh_status, diagnostics_json)
             VALUES (?, 'sample', 'app.main', ?, 'REFRESHED', '[]')",
            params![path, hex::encode(Sha256::digest(content))],
        )
        .expect("semantic graph file");
    }
    fixture.synchronize_semantic_graph_scope_fingerprints();
    (temp, home, config_home, workspace, fixture)
}

fn rpc(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    request: serde_json::Value,
) -> (std::process::ExitStatus, serde_json::Value) {
    let output = rpc_output(home, config_home, workspace, "json", &request);
    let response = serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
        panic!(
            "rpc JSON: {error}; stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        )
    });
    (output.status, response)
}

fn rpc_output(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    output_format: &str,
    request: &serde_json::Value,
) -> std::process::Output {
    publish_workspace_database_for_test(workspace);
    let socket = home.join("published-semantic-read.sock");
    let _ = std::fs::remove_file(&socket);
    let backend =
        spawn_open_published_semantic_read_backend(home, config_home, workspace, &socket);
    let output = kast(home, config_home)
        .args([
            "--output",
            output_format,
            "rpc",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--request",
            &request.to_string(),
        ])
        .output()
        .expect("rpc");
    backend.finish();
    output
}

fn graph_coverage_page_request(
    id: &str,
    continuation: Option<&str>,
    limit: usize,
) -> serde_json::Value {
    let mut params = serde_json::json!({
        "scope": {"language": "kotlin", "module": "app", "sourceSet": "main"},
        "limit": limit
    });
    if let Some(continuation) = continuation {
        params["continuation"] = serde_json::json!(continuation);
    }
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "method": "graph/coverage",
        "params": params
    })
}

struct AgentRepositoryTraversalRequest<'a> {
    question: &'a str,
    results: usize,
    module: Option<&'a str>,
    source_set: Option<&'a str>,
    continuation: Option<&'a str>,
    verbose: bool,
}

impl<'a> AgentRepositoryTraversalRequest<'a> {
    fn new(question: &'a str) -> Self {
        Self {
            question,
            results: 10,
            module: None,
            source_set: None,
            continuation: None,
            verbose: false,
        }
    }
}

fn agent_repository_traversal_page(
    home: &std::path::Path,
    config_home: &std::path::Path,
    workspace: &std::path::Path,
    request: AgentRepositoryTraversalRequest<'_>,
) -> (std::process::ExitStatus, serde_json::Value) {
    let mut args = vec![
        "agent".to_string(),
        "repository".to_string(),
        "--workspace-root".to_string(),
        workspace.to_str().expect("workspace").to_string(),
        "--question".to_string(),
        request.question.to_string(),
        "--intent".to_string(),
        "outgoing-impact".to_string(),
        "--language".to_string(),
        "kotlin".to_string(),
        "--relation".to_string(),
        "calls".to_string(),
        "--max-depth".to_string(),
        "2".to_string(),
        "--depth".to_string(),
        "2".to_string(),
        "--results".to_string(),
        request.results.to_string(),
        "--evidence".to_string(),
        "5".to_string(),
    ];
    if let Some(module) = request.module {
        args.extend(["--module".to_string(), module.to_string()]);
    }
    if let Some(source_set) = request.source_set {
        args.extend(["--source-set".to_string(), source_set.to_string()]);
    }
    if let Some(continuation) = request.continuation {
        args.extend(["--continuation".to_string(), continuation.to_string()]);
    }
    if request.verbose {
        args.push("--verbose".to_string());
    }
    let output = published_semantic_command(home, config_home, workspace)
        .args(args)
        .output()
        .expect("agent repository traversal page");
    let raw = String::from_utf8(output.stdout).expect("agent repository traversal UTF-8");
    let response = toon_format::decode_default(raw.trim()).unwrap_or_else(|error| {
        panic!(
            "agent repository traversal TOON: {error}; stdout={raw} stderr={}",
            String::from_utf8_lossy(&output.stderr)
        )
    });
    (output.status, response)
}

fn repository_relationship_identities(
    response: &serde_json::Value,
) -> std::collections::BTreeSet<(String, String, String, String)> {
    response["result"]["relationships"]
        .as_array()
        .expect("repository relationships")
        .iter()
        .map(|relationship| {
            (
                relationship["sourceKey"]
                    .as_str()
                    .expect("relationship source")
                    .to_string(),
                relationship["targetKey"]
                    .as_str()
                    .expect("relationship target")
                    .to_string(),
                relationship["kind"]
                    .as_str()
                    .expect("relationship kind")
                    .to_string(),
                relationship["context"]
                    .as_str()
                    .expect("relationship context")
                    .to_string(),
            )
        })
        .collect()
}

fn repository_path_page_request(
    id: &str,
    continuation: serde_json::Value,
    evidence_limit: usize,
) -> serde_json::Value {
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "method": "repository/query",
        "params": {
            "question": "Trace outgoing CALLS from semanticGraphOperation to SemanticGraphSha256.parse.",
            "intent": "path",
            "scope": {
                "language": "kotlin",
                "module": null,
                "sourceSet": null,
                "relations": ["CALLS"],
                "direction": "OUTGOING",
                "maxDepth": null
            },
            "limits": {"depth": 6, "results": 10, "evidence": evidence_limit},
            "evidenceContinuation": continuation
        }
    })
}
