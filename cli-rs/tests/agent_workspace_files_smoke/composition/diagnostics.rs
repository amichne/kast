#[test]
fn discovered_file_path_composes_with_diagnostics_and_exact_symbol_lookup() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("Gradle settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = create_workspace_index(&home, &workspace, "direct-composition", 1);
    let owned_file = workspace.join("src/main/kotlin/sample/Source0000.kt");
    let unowned_file = workspace.join("src/main/kotlin/sample/Unowned.kt");
    std::fs::write(&unowned_file, "package sample\nclass Unowned\n").expect("unowned source");

    let discovery_backend = spawn_single_owned_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("composition-discovery.sock"),
        &owned_file,
    );
    let discovery_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "headless",
            "--kind",
            "source",
            "--fields",
            "path",
        ])
        .output()
        .expect("workspace discovery");
    assert!(
        discovery_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&discovery_output.stdout),
        String::from_utf8_lossy(&discovery_output.stderr),
    );
    discovery_backend.join().expect("discovery backend");
    let discovery: serde_json::Value =
        serde_json::from_slice(&discovery_output.stdout).expect("discovery JSON");
    assert_eq!(
        discovery["result"]["files"].as_array().map(Vec::len),
        Some(1),
        "an existing but unowned .kt file must not enter semantic discovery: {discovery:#}"
    );
    let discovered_file_path = discovery["result"]["files"][0]["filePath"]
        .as_str()
        .expect("discovered filePath")
        .to_string();
    assert_eq!(discovered_file_path, owned_file.display().to_string());
    assert_ne!(discovered_file_path, unowned_file.display().to_string());

    let diagnostics_backend = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("composition-diagnostics.sock"),
        vec![
            (
                "raw/workspace-refresh",
                serde_json::json!({
                    "refreshedFiles": [discovered_file_path],
                    "removedFiles": [],
                    "fullRefresh": false,
                    "fileStatuses": [{
                        "filePath": discovered_file_path,
                        "fileSystemDiscovery": "DISCOVERED",
                        "sourceModuleOwnership": "OWNED",
                        "indexAdmission": "ADMITTED",
                        "analysisAvailability": "AVAILABLE",
                        "analysisStatus": {"filePath": discovered_file_path, "state": "ANALYZED"}
                    }],
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 1,
                    "analyzedFileCount": 1,
                    "skippedFileCount": 0,
                    "removedFileCount": 0,
                    "attemptCount": 1,
                    "elapsedMillis": 0,
                    "schemaVersion": 5
                }),
            ),
            (
                "raw/diagnostics",
                serde_json::json!({
                    "diagnostics": [],
                    "fileStatuses": [{"filePath": discovered_file_path, "state": "ANALYZED"}],
                    "fileHashes": [{
                        "filePath": discovered_file_path,
                        "hash": "a".repeat(64)
                    }],
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 1,
                    "analyzedFileCount": 1,
                    "skippedFileCount": 0,
                    "severityCounts": {"error": 0, "warning": 0, "info": 0, "total": 0},
                    "cardinality": {"type": "EXACT", "totalCount": 0}
                }),
            ),
        ],
    );
    let diagnostics_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "diagnostics",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "headless",
            "--file-path",
            &discovered_file_path,
        ])
        .output()
        .expect("composed diagnostics");
    assert!(
        diagnostics_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&diagnostics_output.stdout),
        String::from_utf8_lossy(&diagnostics_output.stderr),
    );
    let diagnostics_requests = diagnostics_backend.join().expect("diagnostics backend");
    assert_eq!(
        diagnostics_requests
            .iter()
            .find(|request| request["method"] == "raw/diagnostics")
            .expect("diagnostics request")["params"]["filePaths"],
        serde_json::json!([discovered_file_path])
    );

    let symbol_backend = spawn_scripted_headless_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("composition-symbol.sock"),
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "symbol": {
                    "fqName": "sample.Source0000",
                    "kind": "CLASS",
                    "location": {
                        "filePath": discovered_file_path,
                        "startOffset": 15,
                        "endOffset": 25,
                        "startLine": 2,
                        "startColumn": 7,
                        "preview": "Source0000"
                    }
                }
            }),
        )],
    );
    let symbol_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Source0000",
            "--file-hint",
            &discovered_file_path,
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--backend",
            "headless",
        ])
        .output()
        .expect("composed exact symbol lookup");
    assert!(
        symbol_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&symbol_output.stdout),
        String::from_utf8_lossy(&symbol_output.stderr),
    );
    let symbol: serde_json::Value =
        serde_json::from_slice(&symbol_output.stdout).expect("symbol JSON");
    assert_eq!(symbol["result"]["mode"], "exact", "{symbol:#}");
    assert_eq!(symbol["result"]["outcome"], "RESOLVED", "{symbol:#}");
    let symbol_requests = symbol_backend.join().expect("symbol backend");
    assert_eq!(
        symbol_requests[2]["params"]["fileHint"], discovered_file_path,
        "exact lookup must consume the discovery filePath verbatim"
    );
}
