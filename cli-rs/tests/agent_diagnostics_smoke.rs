mod support;

use serde_json::{Value, json};
use std::process::Output;
use std::time::{Duration, Instant};
use support::*;

#[test]
fn relative_file_paths_are_canonical_in_every_compact_json_view() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let first = workspace.join("src/First.kt");
    let second = workspace.join("src/with spaces/Second.kt");
    for file in [&first, &second] {
        std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
        std::fs::write(file, "class Example\n").expect("scenario source");
    }
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);

    let expected = [&first, &second].map(|file| {
        file.canonicalize()
            .expect("canonical source")
            .display()
            .to_string()
    });
    let socket_path = workspace_socket_path(&workspace, temp.path());
    write_descriptor(&home, &workspace, &socket_path);
    let listener = bind_listener(&socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_refresh_for(&expected),
        complete_clean_diagnostics_for(&expected),
        12,
    );
    let views: [&[&str]; 3] = [&[], &["--fields", "analysis"], &["--count"]];
    let outputs = views.map(|view| {
        run_diagnostics_arguments_with_view(
            &home,
            &config_home,
            &workspace,
            &["src/First.kt", "src/with spaces/Second.kt"],
            "json",
            view,
        )
    });
    for output in &outputs {
        assert!(
            output.status.success(),
            "relative diagnostics should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
    let requests = backend.join().expect("fake diagnostics backend");

    let refresh_requests = requests
        .iter()
        .filter(|request| request["method"] == "raw/workspace-refresh")
        .collect::<Vec<_>>();
    let diagnostics_requests = requests
        .iter()
        .filter(|request| request["method"] == "raw/diagnostics")
        .collect::<Vec<_>>();
    assert_eq!(refresh_requests.len(), 3, "requests={requests:#?}");
    assert_eq!(diagnostics_requests.len(), 3, "requests={requests:#?}");
    for request in refresh_requests {
        assert_eq!(
            request["params"]["filePaths"],
            json!(expected),
            "refresh request: {request:#}",
        );
    }
    for request in diagnostics_requests {
        assert_eq!(
            request["params"]["filePaths"],
            json!(expected),
            "diagnostics request: {request:#}",
        );
    }
    for output in outputs {
        let document = decode_json(&output);
        assert_eq!(document["result"]["filePaths"], json!(expected));
        assert_eq!(
            document["result"]["fileHashes"],
            json!(
                expected
                    .iter()
                    .map(|file_path| json!({"filePath": file_path, "hash": "a".repeat(64)}))
                    .collect::<Vec<_>>()
            ),
        );
    }
}

#[test]
fn canonical_relative_path_is_reported_in_every_output_format() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/with spaces/Report.kt");
    std::fs::create_dir_all(file.parent().expect("source parent")).expect("source dir");
    std::fs::write(&file, "class Report\n").expect("scenario source");
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    let expected = file
        .canonicalize()
        .expect("canonical source")
        .display()
        .to_string();

    let socket_path = workspace_socket_path(&workspace, temp.path());
    write_descriptor(&home, &workspace, &socket_path);
    let listener = bind_listener(&socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_refresh_for(std::slice::from_ref(&expected)),
        complete_clean_diagnostics_for(std::slice::from_ref(&expected)),
        12,
    );
    let outputs = ["json", "human", "toon"].map(|format| {
        run_diagnostics_arguments(
            &home,
            &config_home,
            &workspace,
            &["src/with spaces/Report.kt"],
            format,
        )
    });
    let requests = backend.join().expect("fake diagnostics backend");

    for (format, output) in ["json", "human", "toon"].into_iter().zip(&outputs) {
        let document = if format == "toon" {
            decode_toon(output)
        } else {
            decode_json(output)
        };
        assert!(output.status.success(), "{format}: {document:#}");
        assert_eq!(
            document["result"]["filePaths"],
            json!([expected]),
            "{format}: {document:#}",
        );
    }
    assert_eq!(
        request_methods(&requests),
        expected_diagnostics_methods().repeat(3)
    );
}

#[test]
fn deleted_relative_file_reaches_refresh_with_canonical_path() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let source_parent = workspace.join("src/deleted");
    std::fs::create_dir_all(&source_parent).expect("source parent");
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);
    let missing = source_parent
        .canonicalize()
        .expect("canonical source parent")
        .join("Removed.kt");

    let socket_path = workspace_socket_path(&workspace, temp.path());
    write_descriptor(&home, &workspace, &socket_path);
    let listener = bind_listener(&socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        complete_removed_refresh(&missing),
        incomplete_diagnostics(&missing),
        4,
    );
    let output = run_diagnostics_arguments(
        &home,
        &config_home,
        &workspace,
        &["src/deleted/Removed.kt"],
        "json",
    );
    let requests = backend.join().expect("fake diagnostics backend");
    let document = decode_json(&output);
    let expected = missing.display().to_string();

    assert!(!output.status.success(), "{document:#}");
    assert_eq!(
        document["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
        "{document:#}",
    );
    assert_eq!(
        requests[2]["params"]["filePaths"],
        json!([expected]),
        "refresh request: {:#}",
        requests[2],
    );
    assert_eq!(
        document["error"]["details"]["result"]["fileStatuses"][0]["filePath"],
        expected,
    );
}

#[test]
fn relative_escape_is_rejected_before_runtime_resolution() {
    assert_pre_dispatch_path_error("../Outside.kt", "AGENT_FILE_OUTSIDE_WORKSPACE");
}

#[test]
fn unsupported_file_kind_is_rejected_before_runtime_resolution() {
    assert_pre_dispatch_path_error("src/App.java", "AGENT_FILE_KIND_UNSUPPORTED");
}

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
        complete_refresh(&canonical_test_path(&file)),
        incomplete_diagnostics(&canonical_test_path(&file)),
        12,
    );

    let json_output = run_diagnostics(&home, &config_home, &workspace, &file, "json");
    let human_output = run_diagnostics(&home, &config_home, &workspace, &file, "human");
    let toon_output = run_diagnostics(&home, &config_home, &workspace, &file, "toon");
    let requests = backend.join().expect("fake diagnostics backend");

    assert_eq!(
        request_methods(&requests),
        expected_diagnostics_methods().repeat(3),
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
        assert!(document["result"].is_null(), "{format}: {document:#}");
        assert_eq!(
            document["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
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
    write_gradle_marker(&workspace);
    std::fs::create_dir_all(&home).expect("home");
    write_macos_plugin_workspace_metadata(&workspace);

    let socket_path = workspace_socket_path(&workspace, temp.path());
    write_descriptor(&home, &workspace, &socket_path);
    let listener = bind_listener(&socket_path);
    let backend = spawn_fake_backend(
        listener,
        workspace.clone(),
        incomplete_refresh(&canonical_test_path(&file)),
        complete_clean_diagnostics(&canonical_test_path(&file)),
        3,
    );

    let output = run_diagnostics(&home, &config_home, &workspace, &file, "json");
    let requests = backend.join().expect("fake diagnostics backend");
    let document = decode_json(&output);

    assert_eq!(
        request_methods(&requests),
        ["runtime/status", "capabilities", "raw/workspace-refresh"],
    );
    assert!(!output.status.success(), "{document:#}");
    assert_eq!(document["ok"], false, "{document:#}");
    assert!(document["result"].is_null(), "{document:#}");
    assert_eq!(
        document["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
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
    assert_eq!(document["result"]["ok"], true, "{document:#}");
    assert_eq!(
        document["result"]["severityCounts"]["error"], 1,
        "{document:#}"
    );
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
        document["error"]["code"], "SEMANTIC_ANALYSIS_INCOMPLETE",
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
        document["error"]["code"], "SEMANTIC_ANALYSIS_INVALID",
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
        complete_refresh(&canonical_test_path(&file)),
        diagnostics(&canonical_test_path(&file)),
        4,
    );
    let output = run_diagnostics(&home, &config_home, &workspace, &file, "json");
    let requests = backend.join().expect("fake diagnostics backend");
    (output, request_methods(&requests))
}

fn run_diagnostics(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    file: &Path,
    output_format: &str,
) -> Output {
    run_diagnostics_arguments(
        home,
        config_home,
        workspace,
        &[file.to_str().expect("file path")],
        output_format,
    )
}

fn run_diagnostics_arguments(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    file_paths: &[&str],
    output_format: &str,
) -> Output {
    run_diagnostics_arguments_with_view(
        home,
        config_home,
        workspace,
        file_paths,
        output_format,
        &[],
    )
}

fn run_diagnostics_arguments_with_view(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    file_paths: &[&str],
    output_format: &str,
    view_args: &[&str],
) -> Output {
    let mut command = kast(home, config_home);
    command.args([
        "--output",
        output_format,
        "agent",
        "diagnostics",
        "--backend=idea",
        "--workspace-root",
        workspace.to_str().expect("workspace path"),
    ]);
    for file_path in file_paths {
        command.args(["--file-path", file_path]);
    }
    command.args(view_args);
    command.output().expect("agent diagnostics")
}

fn assert_pre_dispatch_path_error(file_path: &str, expected_code: &str) {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let output = run_diagnostics_arguments(&home, &config_home, &workspace, &[file_path], "json");
    let document = decode_json(&output);

    assert!(!output.status.success(), "{document:#}");
    assert_eq!(document["error"]["code"], expected_code, "{document:#}");
}

fn request_methods(requests: &[Value]) -> Vec<String> {
    requests
        .iter()
        .map(|request| {
            request["method"]
                .as_str()
                .expect("request method")
                .to_string()
        })
        .collect()
}

fn canonical_test_path(path: &Path) -> PathBuf {
    if path.exists() {
        return path.canonicalize().expect("canonical test file");
    }
    path.parent()
        .expect("test file parent")
        .canonicalize()
        .expect("canonical test file parent")
        .join(path.file_name().expect("test file name"))
}

fn expected_diagnostics_methods() -> Vec<&'static str> {
    [
        "runtime/status",
        "capabilities",
        "raw/workspace-refresh",
        "raw/diagnostics",
    ]
    .to_vec()
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
    let summary = document
        .pointer("/result/analysis")
        .or_else(|| document.pointer("/error/details/semanticAnalysis"))
        .unwrap_or_else(|| panic!("{format}: semantic analysis summary missing: {document:#}"));
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
            "schemaVersion": 5
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
) -> std::thread::JoinHandle<Vec<Value>> {
    listener
        .set_nonblocking(true)
        .expect("nonblocking listener");
    thread::spawn(move || {
        let mut requests = Vec::with_capacity(expected_requests);
        let deadline = Instant::now() + Duration::from_secs(10);
        while requests.len() < expected_requests && Instant::now() < deadline {
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
            requests.push(request.clone());
            let result = match method.as_str() {
                "runtime/status" => json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": "idea",
                    "backendVersion": "diagnostics-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "schemaVersion": 5
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
                    "schemaVersion": 5
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
            requests.len(),
            expected_requests,
            "fake backend request timeout"
        );
        requests
    })
}

fn complete_refresh(file: &Path) -> Value {
    complete_refresh_for(&[file.display().to_string()])
}

fn complete_refresh_for(file_paths: &[String]) -> Value {
    json!({
        "refreshedFiles": file_paths,
        "removedFiles": [],
        "fullRefresh": false,
        "fileStatuses": file_paths
            .iter()
            .map(|file_path| json!({
                "filePath": file_path,
                "fileSystemDiscovery": "DISCOVERED",
                "sourceModuleOwnership": "OWNED",
                "indexAdmission": "ADMITTED",
                "analysisAvailability": "AVAILABLE",
                "analysisStatus": {
                    "filePath": file_path,
                    "state": "ANALYZED"
                }
            }))
            .collect::<Vec<_>>(),
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": file_paths.len(),
        "analyzedFileCount": file_paths.len(),
        "skippedFileCount": 0,
        "removedFileCount": 0,
        "attemptCount": 1,
        "elapsedMillis": 0,
        "schemaVersion": 5
    })
}

fn complete_removed_refresh(file: &Path) -> Value {
    let file_path = file.display().to_string();
    json!({
        "refreshedFiles": [],
        "removedFiles": [file_path],
        "fullRefresh": false,
        "fileStatuses": [{
            "filePath": file_path,
            "fileSystemDiscovery": "REMOVED",
            "sourceModuleOwnership": "NOT_APPLICABLE",
            "indexAdmission": "NOT_APPLICABLE",
            "analysisAvailability": "NOT_APPLICABLE"
        }],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 0,
        "analyzedFileCount": 0,
        "skippedFileCount": 0,
        "removedFileCount": 1,
        "attemptCount": 1,
        "elapsedMillis": 0,
        "schemaVersion": 5
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
        "schemaVersion": 5
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
        "fileHashes": [],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1},
        "schemaVersion": 5
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
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1},
        "schemaVersion": 5
    })
}

fn complete_clean_diagnostics(file: &Path) -> Value {
    complete_clean_diagnostics_for(&[file.display().to_string()])
}

fn complete_clean_diagnostics_for(file_paths: &[String]) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": file_paths
            .iter()
            .map(|file_path| json!({
                "filePath": file_path,
                "state": "ANALYZED"
            }))
            .collect::<Vec<_>>(),
        "fileHashes": file_paths
            .iter()
            .map(|file_path| diagnostic_file_hash_for_path(file_path))
            .collect::<Vec<_>>(),
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": file_paths.len(),
        "analyzedFileCount": file_paths.len(),
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn incomplete_diagnostics_with_truncated_page(file: &Path) -> Value {
    incomplete_diagnostics_with_page(
        file,
        Some(json!({
            "truncated": true,
            "nextPageToken": "00000000-0000-4000-8000-000000000337"
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
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 1, "info": 0, "total": 2},
        "cardinality": {"type": "EXACT", "totalCount": 2},
        "schemaVersion": 5
    });
    if let Some(page) = page {
        result["page"] = page;
    }
    result
}

fn omitted_completeness_proof(_file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
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
        "fileHashes": [],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn missing_file_status_ledger(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn mismatched_file_status_ledger(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "INCOMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 1,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
    })
}

fn unknown_file_analysis_state(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "NOT_A_STATE"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
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
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1},
        "schemaVersion": 5
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
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 1,
        "skippedFileCount": 0,
        "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
        "cardinality": {"type": "EXACT", "totalCount": 1},
        "schemaVersion": 5
    })
}

fn malformed_completeness_evidence(file: &Path) -> Value {
    json!({
        "diagnostics": [],
        "fileStatuses": [{
            "filePath": file.display().to_string(),
            "state": "ANALYZED"
        }],
        "fileHashes": [diagnostic_file_hash(file)],
        "semanticOutcome": "COMPLETE",
        "requestedFileCount": 1,
        "analyzedFileCount": 0,
        "skippedFileCount": 0,
        "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
        "cardinality": {"type": "EXACT", "totalCount": 0},
        "schemaVersion": 5
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

fn diagnostic_file_hash(file: &Path) -> Value {
    diagnostic_file_hash_for_path(&file.display().to_string())
}

fn diagnostic_file_hash_for_path(file_path: &str) -> Value {
    json!({
        "filePath": file_path,
        "hash": "a".repeat(64)
    })
}
