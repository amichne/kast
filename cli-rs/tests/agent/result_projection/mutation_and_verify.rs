#[test]
fn mutation_default_exposes_state_files_edits_and_diagnostic_summary() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    write_gradle_marker(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let file = workspace.join("src/Added.kt");
    let binary = write_active_kast_for_test(&home, &config_home);
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![(
            "mutation/submit",
            json!({
                "type": "SUCCEEDED",
                "result": {
                    "type": "RENAME_RESULT",
                    "response": {
                        "ok": true,
                        "editCount": 1,
                        "affectedFiles": [file.display().to_string()],
                        "applyResult": {
                            "applied": [{
                                "filePath": file.display().to_string(),
                                "startOffset": 0,
                                "endOffset": 5,
                                "newText": "Renamed"
                            }],
                            "affectedFiles": [file.display().to_string()],
                            "createdFiles": [],
                            "deletedFiles": []
                        },
                        "diagnostics": {
                            "errorCount": 0,
                            "warningCount": 1
                        }
                    }
                },
                "deduplicated": false
            }),
        )],
    );
    let lease_id = acquire_projection_workspace_lease(&binary, &home, &config_home, &workspace);
    let output = kast_at(&binary, &home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "rename",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--symbol",
            "sample.Added",
            "--new-name",
            "Renamed",
            "--apply",
            "--idempotency-key",
            "issue-337-rename",
            "--lease-id",
            &lease_id,
        ])
        .output()
        .expect("mutation");
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    backend.join().expect("mutation backend");
    let raw = String::from_utf8(output.stdout).expect("utf8");
    let stdout: Value = serde_json::from_str(&raw).expect("mutation json");

    assert_eq!(stdout["result"]["type"], "KAST_AGENT_MUTATION_RESULT");
    assert_eq!(stdout["result"]["execution"]["outcome"], "SUCCEEDED");
    assert_eq!(stdout["result"]["execution"]["deduplicated"], false);
    assert_eq!(stdout["result"]["appliedEditCount"], 1);
    assert_eq!(
        stdout["result"]["files"],
        json!([file.display().to_string()])
    );
    assert_eq!(stdout["result"]["diagnostics"]["warning"], 1);
    assert!(stdout["result"].get("trace").is_none(), "{stdout}");
    assert_output_budget(&raw, MUTATION_LINE_BUDGET, MUTATION_TOKEN_BUDGET);
}

#[test]
fn verify_default_exposes_health_runtime_and_capability_evidence_without_steps() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    write_gradle_marker(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let runtime = json!({
        "state": "READY",
        "healthy": true,
        "active": true,
        "indexing": false,
        "backendName": "indexer",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "schemaVersion": api_schema_version()
    });
    let capabilities = json!({
        "backendName": "indexer",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "readCapabilities": ["WORKSPACE_FILES", "symbol/resolve", "symbol/references"],
        "mutationCapabilities": ["RENAME"],
        "limits": {
            "requestTimeoutMillis": 60000,
            "maxResults": 1000,
            "maxConcurrentRequests": 4
        },
        "explanation": "capability explanation ".repeat(200),
        "schemaVersion": api_schema_version()
    });
    let responses = vec![
        ("runtime/status", runtime.clone()),
        ("capabilities", capabilities.clone()),
    ];
    let backend = spawn_sequenced_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        responses
            .into_iter()
            .chain([
                ("health", json!({"ok": true, "status": "READY"})),
                ("runtime/status", runtime),
                ("capabilities", capabilities),
            ])
            .collect(),
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
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    backend.join().expect("verify backend");
    let raw = String::from_utf8(output.stdout).expect("utf8");
    let stdout: Value = serde_json::from_str(&raw).expect("verify json");

    assert_eq!(stdout["result"]["type"], "KAST_AGENT_VERIFY_RESULT");
    assert_eq!(stdout["result"]["health"]["ok"], true);
    assert_eq!(stdout["result"]["runtime"]["state"], "READY");
    assert_eq!(stdout["result"]["runtime"]["backendName"], "indexer");
    assert_eq!(stdout["result"]["capabilities"]["readCount"], 3);
    assert_eq!(stdout["result"]["capabilities"]["mutationCount"], 1);
    assert_eq!(stdout["result"]["capabilities"]["publicReadCount"], 1);
    assert_eq!(
        stdout["result"]["capabilities"]["publicRead"],
        json!([{
            "capability": "WORKSPACE_FILES",
            "command": "kast agent workspace-files"
        }])
    );
    assert!(stdout["result"].get("steps").is_none(), "{stdout}");
    assert_output_budget(&raw, VERIFY_LINE_BUDGET, VERIFY_TOKEN_BUDGET);
}

fn acquire_projection_workspace_lease(
    binary: &Path,
    home: &Path,
    config_home: &Path,
    workspace: &Path,
) -> String {
    let output = kast_at(binary, home, config_home)
        .args([
            "--output",
            "json",
            "agent",
            "lease",
            "acquire",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("acquire projection workspace lease");
    assert!(
        output.status.success(),
        "workspace lease acquisition should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let payload: Value = serde_json::from_slice(&output.stdout).expect("workspace lease JSON");
    payload["result"]["leaseId"]
        .as_str()
        .expect("workspace lease id")
        .to_string()
}

fn assert_output_budget(output: &str, line_budget: usize, token_budget: usize) {
    let value: Value = serde_json::from_str(output).expect("budget fixture json");
    let measured = serde_json::to_string_pretty(&value).expect("pretty budget fixture");
    let lines = measured.lines().count();
    let tokens = cl100k_tokens(&measured);
    assert!(
        lines <= line_budget,
        "output used {lines} lines; budget is {line_budget}"
    );
    assert!(
        tokens <= token_budget,
        "output used {tokens} cl100k_base tokens; budget is {token_budget}"
    );
}

fn write_gradle_marker(workspace: &Path) {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("Gradle workspace marker");
}

fn complete_refresh_for(file: &Path) -> Value {
    let file_path = file.display().to_string();
    json!({
        "refreshedFiles": [file_path],
        "removedFiles": [],
        "fullRefresh": false,
        "fileStatuses": [{
            "filePath": file_path,
            "fileSystemDiscovery": "DISCOVERED",
            "sourceModuleOwnership": "OWNED",
            "indexAdmission": "ADMITTED",
            "analysisAvailability": "AVAILABLE",
            "analysisStatus": {
                "filePath": file_path,
                "state": "ANALYZED"
            }
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "removedFileCount": 0,
        "attemptCount": 1,
        "elapsedMillis": 0,
        "schemaVersion": api_schema_version()
    })
}

fn cl100k_tokens(value: &str) -> usize {
    tiktoken_rs::cl100k_base()
        .expect("cl100k_base tokenizer")
        .encode_with_special_tokens(value)
        .len()
}
