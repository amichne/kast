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
        let metadata = std::fs::read_to_string(macos_plugin_workspace_metadata_path(workspace))
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
