mod support;

use serde_json::{Value, json};
use std::process::Output;
use std::time::{Duration, Instant};
use support::*;

#[test]
fn incomplete_semantic_analysis_fails_closed_in_every_output_format() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/Missing.kt");
    std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);

    let socket_path = workspace_socket_path(&workspace, temp.path());
    write_descriptor(&home, &workspace, &socket_path);
    let listener = bind_listener(&socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_refresh(&file),
        incomplete_diagnostics(&file),
        12,
    );

    let json_output = run_diagnostics(&home, &config_home, &workspace, &file, "json");
    let human_output = run_diagnostics(&home, &config_home, &workspace, &file, "human");
    let toon_output = run_diagnostics(&home, &config_home, &workspace, &file, "toon");
    let methods = backend.join().expect("fake diagnostics backend");

    assert_eq!(
        methods,
        [
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics"
        ]
        .repeat(3),
    );
    for (format, output, document) in [
        ("json", &json_output, decode_json(&json_output)),
        ("human", &human_output, decode_json(&human_output)),
        ("toon", &toon_output, decode_toon(&toon_output)),
    ] {
        assert!(
            !output.status.success(),
            "{format} diagnostics must fail closed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        assert_eq!(document["ok"], false, "{format}: {document:#}");
        assert_eq!(document["result"]["ok"], false, "{format}: {document:#}");
        assert_eq!(
            document["result"]["steps"][0]["name"], "workspace-refresh",
            "{format}: {document:#}",
        );
        assert_eq!(
            document["result"]["steps"][0]["ok"], true,
            "{format}: {document:#}",
        );
        assert_eq!(
            document["result"]["steps"][1]["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
            "{format}: {document:#}",
        );
        assert_semantic_counts(&document, "INCOMPLETE", 1, 0, 1, format);
    }
}

#[test]
fn incomplete_semantic_admission_stops_before_diagnostics() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/Pending.kt");
    std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
    std::fs::write(&file, "fun pending(): Int = 42\n").expect("scenario source");
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);

    let socket_path = workspace_socket_path(&workspace, temp.path());
    write_descriptor(&home, &workspace, &socket_path);
    let listener = bind_listener(&socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        incomplete_refresh(&file),
        complete_clean_diagnostics(&file),
        3,
    );

    let output = run_diagnostics(&home, &config_home, &workspace, &file, "json");
    let methods = backend.join().expect("fake diagnostics backend");
    let document = decode_json(&output);

    assert_eq!(
        methods,
        ["runtime/status", "capabilities", "raw/workspace-refresh"],
    );
    assert!(!output.status.success(), "{document:#}");
    assert_eq!(document["ok"], false, "{document:#}");
    assert_eq!(document["result"]["steps"].as_array().unwrap().len(), 1);
    assert_eq!(
        document["result"]["steps"][0]["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
        "{document:#}",
    );
    assert_semantic_counts(&document, "INCOMPLETE", 1, 0, 1, "json");
}

#[test]
fn ordinary_compiler_diagnostic_remains_a_successful_complete_analysis() {
    let (output, methods) = run_single_json_scenario(
        "Broken.kt",
        "fun broken(): Int = \"nope\"\n",
        complete_compiler_diagnostics,
    );
    let document = decode_json(&output);

    assert_eq!(
        methods,
        [
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics"
        ],
    );
    assert!(
        output.status.success(),
        "ordinary compiler diagnostics retain successful semantic analysis: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(document["ok"], true, "{document:#}");
    assert_eq!(document["result"]["steps"][1]["ok"], true, "{document:#}");
    assert_semantic_counts(&document, "COMPLETE", 1, 1, 0, "json");
}

#[test]
fn clean_file_remains_a_successful_complete_analysis() {
    let (output, methods) = run_single_json_scenario(
        "Clean.kt",
        "fun clean(): Int = 42\n",
        complete_clean_diagnostics,
    );
    let document = decode_json(&output);

    assert_eq!(
        methods,
        [
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics"
        ],
    );
    assert!(
        output.status.success(),
        "clean analysis should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(document["ok"], true, "{document:#}");
    assert_semantic_counts(&document, "COMPLETE", 1, 1, 0, "json");
}

#[test]
fn truncated_page_can_hide_analysis_failure_without_invalidating_evidence() {
    let (output, methods) = run_single_json_scenario(
        "Truncated.kt",
        "fun truncated(): Int = 42\n",
        incomplete_diagnostics_with_truncated_page,
    );
    let document = decode_json(&output);

    assert_eq!(
        methods,
        [
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics"
        ],
    );
    assert!(!output.status.success(), "{document:#}");
    assert_eq!(document["ok"], false, "{document:#}");
    assert_eq!(
        document["result"]["steps"][1]["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
        "{document:#}",
    );
    assert_semantic_counts(&document, "INCOMPLETE", 1, 1, 0, "json");
}

#[test]
fn untruncated_page_cannot_explain_incomplete_outcome() {
    assert_invalid_semantic_evidence(
        "Untruncated.kt",
        incomplete_diagnostics_with_untruncated_page,
    );
}

#[test]
fn absent_page_cannot_explain_incomplete_outcome() {
    assert_invalid_semantic_evidence("NoPage.kt", incomplete_diagnostics_without_page);
}

#[test]
fn malformed_page_cannot_explain_incomplete_outcome() {
    assert_invalid_semantic_evidence(
        "MalformedPage.kt",
        incomplete_diagnostics_with_malformed_page,
    );
}

#[test]
fn omitted_completeness_proof_fails_closed() {
    assert_invalid_semantic_evidence("Omitted.kt", omitted_completeness_proof);
}

#[test]
fn complete_outcome_with_a_skipped_file_fails_closed() {
    assert_invalid_semantic_evidence("Skipped.kt", complete_outcome_with_skipped_file);
}

#[test]
fn missing_file_status_ledger_fails_closed() {
    assert_invalid_semantic_evidence("MissingLedger.kt", missing_file_status_ledger);
}

#[test]
fn mismatched_file_status_ledger_fails_closed() {
    assert_invalid_semantic_evidence("MismatchedLedger.kt", mismatched_file_status_ledger);
}

#[test]
fn unknown_file_analysis_state_fails_closed() {
    assert_invalid_semantic_evidence("UnknownState.kt", unknown_file_analysis_state);
}

#[test]
fn malformed_diagnostic_code_fails_closed() {
    assert_invalid_semantic_evidence("MalformedCode.kt", malformed_diagnostic_code);
}

#[test]
fn malformed_diagnostic_structure_fails_closed() {
    assert_invalid_semantic_evidence("MalformedDiagnostic.kt", malformed_diagnostic_structure);
}

#[test]
fn malformed_completeness_evidence_fails_closed() {
    assert_invalid_semantic_evidence("Malformed.kt", malformed_completeness_evidence);
}

fn assert_invalid_semantic_evidence(file_name: &str, diagnostics: fn(&Path) -> Value) {
    let (output, methods) =
        run_single_json_scenario(file_name, "fun valid(): Int = 42\n", diagnostics);
    let document = decode_json(&output);

    assert_eq!(
        methods,
        [
            "runtime/status",
            "capabilities",
            "raw/workspace-refresh",
            "raw/diagnostics"
        ],
    );
    assert!(!output.status.success(), "{document:#}");
    assert_eq!(document["ok"], false, "{document:#}");
    assert_eq!(
        document["result"]["steps"][1]["error"]["code"], "SEMANTIC_ANALYSIS_INVALID",
        "{document:#}",
    );
}

fn run_single_json_scenario(
    file_name: &str,
    source: &str,
    diagnostics: fn(&Path) -> Value,
) -> (Output, Vec<String>) {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src").join(file_name);
    std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
    std::fs::write(&file, source).expect("scenario source");
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);

    let socket_path = workspace_socket_path(&workspace, temp.path());
    write_descriptor(&home, &workspace, &socket_path);
    let listener = bind_listener(&socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_refresh(&file),
        diagnostics(&file),
        4,
    );
    let output = run_diagnostics(&home, &config_home, &workspace, &file, "json");
    let methods = backend.join().expect("fake diagnostics backend");
    (output, methods)
}

fn run_diagnostics(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    file: &Path,
    output_format: &str,
) -> Output {
    kast(home, config_home)
        .args([
            "--output",
            output_format,
            "agent",
            "diagnostics",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--file-path",
            file.to_str().expect("file path"),
        ])
        .output()
        .expect("agent diagnostics")
}

fn write_gradle_marker(workspace: &Path) {
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"diagnostics-fixture\"\n",
    )
    .expect("settings");
}

fn decode_json(output: &Output) -> Value {
    serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
        panic!(
            "decode JSON output: {error}; stdout={}",
            String::from_utf8_lossy(&output.stdout),
        )
    })
}

fn decode_toon(output: &Output) -> Value {
    let text = std::str::from_utf8(&output.stdout).expect("TOON output is UTF-8");
    toon_format::decode_default(text.trim()).expect("decode TOON output")
}

fn assert_semantic_counts(
    document: &Value,
    outcome: &str,
    requested: u64,
    analyzed: u64,
    skipped: u64,
    format: &str,
) {
    let summary = &document["result"]["semanticAnalysis"];
    assert_eq!(
        summary["semanticOutcome"], outcome,
        "{format}: {document:#}"
    );
    assert_eq!(
        summary["requestedFileCount"], requested,
        "{format}: {document:#}"
    );
    assert_eq!(
        summary["analyzedFileCount"], analyzed,
        "{format}: {document:#}"
    );
    assert_eq!(
        summary["skippedFileCount"], skipped,
        "{format}: {document:#}"
    );
}

fn workspace_socket_path(workspace: &Path, _temp_root: &Path) -> PathBuf {
    #[cfg(target_os = "macos")]
    {
        let metadata = std::fs::read_to_string(workspace.join(".kast/setup/workspace.json"))
            .expect("plugin workspace metadata");
        let metadata: Value = serde_json::from_str(&metadata).expect("workspace metadata JSON");
        PathBuf::from(
            metadata["socketPath"]
                .as_str()
                .expect("metadata socketPath"),
        )
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = workspace;
        _temp_root.join("diagnostics.sock")
    }
}

fn write_descriptor(home: &Path, workspace: &Path, socket_path: &Path) {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&json!([{
            "workspaceRoot": workspace.display().to_string(),
            "backendName": "idea",
            "backendVersion": "diagnostics-test",
            "transport": "uds",
            "socketPath": socket_path.display().to_string(),
            "pid": std::process::id(),
            "schemaVersion": 3
        }]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor");
}

fn bind_listener(socket_path: &Path) -> UnixListener {
    if socket_path.exists() {
        std::fs::remove_file(socket_path).expect("remove stale test socket");
    }
    UnixListener::bind(socket_path).expect("bind fake diagnostics socket")
}

fn spawn_fake_backend(
    listener: UnixListener,
    workspace: PathBuf,
    refresh: Value,
    diagnostics: Value,
    expected_requests: usize,
) -> std::thread::JoinHandle<Vec<String>> {
    listener
        .set_nonblocking(true)
        .expect("nonblocking listener");
    thread::spawn(move || {
        let mut methods = Vec::with_capacity(expected_requests);
        let deadline = Instant::now() + Duration::from_secs(10);
        while methods.len() < expected_requests && Instant::now() < deadline {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(10));
                    continue;
                }
                Err(error) => panic!("accept fake diagnostics client: {error}"),
            };
            stream
                .set_nonblocking(false)
                .expect("blocking diagnostics stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone diagnostics stream"));
            let mut request_line = String::new();
            reader
                .read_line(&mut request_line)
                .expect("read diagnostics request");
            let request: Value =
                serde_json::from_str(&request_line).expect("diagnostics request JSON");
            let method = request["method"]
                .as_str()
                .expect("request method")
                .to_string();
            methods.push(method.clone());
            let result = match method.as_str() {
                "runtime/status" => json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "idea",
                    "backendVersion": "diagnostics-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "schemaVersion": 3
                }),
                "capabilities" => json!({
                    "backendName": "idea",
                    "backendVersion": "diagnostics-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "readCapabilities": ["DIAGNOSTICS"],
                    "mutationCapabilities": ["REFRESH_WORKSPACE"],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                }),
                "raw/workspace-refresh" => refresh.clone(),
                "raw/diagnostics" => diagnostics.clone(),
                other => panic!("unexpected fake diagnostics method: {other}"),
            };
            writeln!(
                stream,
                "{}",
                json!({"jsonrpc": "2.0", "id": request["id"], "result": result}),
            )
            .expect("write diagnostics response");
        }
        assert_eq!(
            methods.len(),
            expected_requests,
            "fake backend request timeout"
        );
        methods
    })
}

fn complete_refresh(file: &Path) -> Value {
    json!({
        "refreshedFiles": [file.display().to_string()],
        "removedFiles": [],
        "fullRefresh": false,
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "fileSystemDiscovery": "DISCOVERED",
            "sourceModuleOwnership": "OWNED",
            "indexAdmission": "ADMITTED",
            "analysisAvailability": "AVAILABLE",
            "analysisStatus": {
                "filePath": file.display().to_string(),
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
        "schemaVersion": 3
    })
}

fn incomplete_refresh(file: &Path) -> Value {
    json!({
        "refreshedFiles": [],
        "removedFiles": [],
        "fullRefresh": false,
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "fileSystemDiscovery": "DISCOVERED",
            "sourceModuleOwnership": "OWNED",
            "indexAdmission": "PENDING",
            "analysisAvailability": "PENDING",
            "analysisStatus": {
                "filePath": file.display().to_string(),
                "state": "PENDING_INDEX",
                "message": "IDEA is indexing"
            }
        }],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "removedFileCount": 0,
        "attemptCount": 3,
        "elapsedMillis": 50,
        "schemaVersion": 3
    })
}

fn incomplete_diagnostics(file: &Path) -> Value {
    json!({
        "diagnostics": [{
            "location": diagnostic_location(file),
            "severity": "ERROR",
            "message": "File not found after refresh",
            "code": "ANALYSIS_FAILURE"
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "MISSING_ON_DISK",
            "message": "File not found after refresh"
        }],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "schemaVersion": 3
    })
}

fn complete_compiler_diagnostics(file: &Path) -> Value {
    json!({
        "diagnostics": [{
            "location": diagnostic_location(file),
            "severity": "ERROR",
            "message": "Type mismatch",
            "code": "TYPE_MISMATCH"
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "schemaVersion": 3
    })
}

fn complete_clean_diagnostics(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "schemaVersion": 3
    })
}

fn incomplete_diagnostics_with_truncated_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(
        file,
        Some(json!({
            "truncated": true,
            "nextPageToken": "0"
        })),
    )
}

fn incomplete_diagnostics_with_untruncated_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(
        file,
        Some(json!({
            "truncated": false
        })),
    )
}

fn incomplete_diagnostics_without_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(file, None)
}

fn incomplete_diagnostics_with_malformed_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(
        file,
        Some(json!({
            "truncated": true,
            "nextPageToken": 0
        })),
    )
}

fn incomplete_diagnostics_with_page(file: &Path, page: Option<Value>) -> Value {
    let mut result = json!({
        "diagnostics": [{
            "location": diagnostic_location(file),
            "severity": "WARNING",
            "message": "Visible warning before hidden analysis failure",
            "code": "VISIBLE_WARNING"
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "schemaVersion": 3
    });
    if let Some(page) = page {
        result["page"] = page;
    }
    result
}

fn omitted_completeness_proof(_file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "schemaVersion": 3
    })
}

fn complete_outcome_with_skipped_file(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "MISSING_ON_DISK",
            "message": "File not found"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "schemaVersion": 3
    })
}

fn missing_file_status_ledger(_file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "schemaVersion": 3
    })
}

fn mismatched_file_status_ledger(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "schemaVersion": 3
    })
}

fn unknown_file_analysis_state(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "NOT_A_STATE"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "schemaVersion": 3
    })
}

fn malformed_diagnostic_code(file: &Path) -> Value {
    json!({
        "diagnostics": [{
            "location": diagnostic_location(file),
            "severity": "ERROR",
            "message": "Malformed code",
            "code": 42
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "schemaVersion": 3
    })
}

fn malformed_diagnostic_structure(file: &Path) -> Value {
    json!({
        "diagnostics": [{
            "severity": "ERROR",
            "message": "Missing location",
            "code": "TYPE_MISMATCH"
        }],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "schemaVersion": 3
    })
}

fn malformed_completeness_evidence(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 0,
        "schemaVersion": 3
    })
}

fn diagnostic_location(file: &Path) -> Value {
    json!({
        "filePath": file.display().to_string(),
        "startOffset": 0,
        "endOffset": 0,
        "startLine": 0,
        "startColumn": 0,
        "preview": ""
    })
}
