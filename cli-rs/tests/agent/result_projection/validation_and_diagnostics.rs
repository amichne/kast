#[test]
fn symbol_rejects_unknown_or_incompatible_fields_before_runtime_io() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Target",
            "--fields",
            "diagnostics",
        ])
        .output()
        .expect("invalid symbol fields");

    assert_eq!(output.status.code(), Some(2));
    let stdout: Value = serde_json::from_slice(&output.stdout).expect("usage json");
    assert_eq!(stdout["code"], "CLI_USAGE", "{stdout}");
}

#[test]
fn every_family_rejects_cross_family_fields_and_conflicting_count_modes() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let cases = [
        vec!["agent", "verify", "--fields", "identity"],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.App",
            "--fields",
            "identity",
        ],
        vec![
            "agent",
            "diagnostics",
            "--file-path",
            "/workspace/App.kt",
            "--fields",
            "identity",
        ],
        vec![
            "agent",
            "rename",
            "--symbol",
            "sample.App",
            "--new-name",
            "Renamed",
            "--fields",
            "identity",
        ],
        vec![
            "agent",
            "symbol",
            "--query",
            "sample.App",
            "--fields",
            "identity",
            "--count",
        ],
    ];

    for args in cases {
        let output = kast(&home, &config_home)
            .args(["--output", "json"])
            .args(args)
            .output()
            .expect("invalid projection arguments");
        assert_eq!(output.status.code(), Some(2));
        let stdout: Value = serde_json::from_slice(&output.stdout).expect("usage json");
        assert_eq!(stdout["code"], "CLI_USAGE", "{stdout}");
    }
}

#[test]
fn diagnostics_default_keeps_completeness_and_actionable_records_without_steps() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    write_gradle_marker(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let file = workspace.join("src/App.kt");
    let socket_path = temp.path().join("idea.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            ("raw/workspace-refresh", complete_refresh_for(&file)),
            (
                "raw/diagnostics",
                json!({
                    "diagnostics": [{
                        "location": {
                            "filePath": file.display().to_string(),
                            "startOffset": 11,
                            "endOffset": 15,
                            "startLine": 2,
                            "startColumn": 5,
                            "preview": "boom"
                        },
                        "severity": "ERROR",
                        "message": "Unresolved reference",
                        "code": "UNRESOLVED_REFERENCE",
                        "rankingTrace": "diagnostic trace ".repeat(200)
                    }],
                    "fileStatuses": [{
                        "filePath": file.display().to_string(),
                        "state": "ANALYZED"
                    }],
                    "fileHashes": [{
                        "filePath": file.display().to_string(),
                        "hash": "a".repeat(64)
                    }],
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 1,
                    "analyzedFileCount": 1,
                    "skippedFileCount": 0,
                    "severityCounts": {"error": 1, "warning": 0, "info": 0, "total": 1},
                    "cardinality": {"type": "EXACT", "totalCount": 1}
                }),
            ),
        ],
    );
    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "diagnostics",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--file-path",
            file.to_str().expect("file"),
        ])
        .output()
        .expect("diagnostics");
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    backend.join().expect("diagnostics backend");
    let raw = String::from_utf8(output.stdout).expect("utf8");
    let stdout: Value = serde_json::from_str(&raw).expect("diagnostics json");

    assert_eq!(stdout["result"]["type"], "KAST_AGENT_DIAGNOSTICS_RESULT");
    assert_eq!(stdout["result"]["analysis"]["requestedFileCount"], 1);
    assert_eq!(stdout["result"]["analysis"]["analyzedFileCount"], 1);
    assert_eq!(stdout["result"]["analysis"]["skippedFileCount"], 0);
    assert_eq!(stdout["result"]["severityCounts"]["error"], 1);
    assert_eq!(
        stdout["result"]["fileHashes"][0]["filePath"],
        file.display().to_string()
    );
    assert_eq!(stdout["result"]["fileHashes"][0]["hash"], "a".repeat(64));
    assert_eq!(
        stdout["result"]["diagnostics"][0]["code"],
        "UNRESOLVED_REFERENCE"
    );
    assert!(stdout["result"].get("steps").is_none(), "{stdout}");
    assert_output_budget(&raw, DIAGNOSTICS_LINE_BUDGET, DIAGNOSTICS_TOKEN_BUDGET);
}

#[test]
fn diagnostics_default_bounds_real_high_cardinality_records_and_requests() {
    const TOTAL_DIAGNOSTICS: usize = 500;
    const COMPACT_DIAGNOSTICS: usize = 8;
    const PAGE_TOKEN: &str = "00000000-0000-4000-8000-000000000337";
    const NEXT_PAGE_TOKEN: &str = "00000000-0000-4000-8000-000000000338";

    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    write_gradle_marker(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let file = workspace.join("src/App.kt");
    let socket_path = temp.path().join("idea.sock");
    let diagnostics = (0..TOTAL_DIAGNOSTICS)
        .map(|index| {
            json!({
                "location": {
                    "filePath": file.display().to_string(),
                    "startOffset": index,
                    "endOffset": index + 1,
                    "startLine": index + 1,
                    "startColumn": 1,
                    "preview": format!("{} {index}", "oversized diagnostic preview ".repeat(100))
                },
                "severity": if index == 0 { "ERROR" } else { "WARNING" },
                "message": format!("{} {index}", "oversized diagnostic message ".repeat(100)),
                "code": if index == 0 { "COMPILER_ERROR" } else { "COMPILER_WARNING" }
            })
        })
        .collect::<Vec<_>>();
    let backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![(
            "raw/diagnostics",
            json!({
                "diagnostics": diagnostics,
                "fileStatuses": [{
                    "filePath": file.display().to_string(),
                    "state": "ANALYZED"
                }],
                "fileHashes": [{
                    "filePath": file.display().to_string(),
                    "hash": "a".repeat(64)
                }],
                "semanticOutcome": "COMPLETE",
                "requestedFileCount": 1,
                "analyzedFileCount": 1,
                "skippedFileCount": 0,
                "severityCounts": {
                    "error": 1,
                    "warning": TOTAL_DIAGNOSTICS - 1,
                    "info": 0,
                    "total": TOTAL_DIAGNOSTICS
                },
                "cardinality": {
                    "type": "EXACT",
                    "totalCount": TOTAL_DIAGNOSTICS
                },
                "page": {
                    "truncated": true,
                    "nextPageToken": NEXT_PAGE_TOKEN
                }
            }),
        )],
    );
    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "diagnostics",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--file-path",
            file.to_str().expect("file"),
            "--page-token",
            PAGE_TOKEN,
        ])
        .output()
        .expect("diagnostics");
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
    let requests = backend.join().expect("diagnostics backend");
    assert!(
        requests
            .iter()
            .all(|request| request["method"] != "raw/workspace-refresh"),
        "continuation requests must not refresh and invalidate the server-held snapshot: {requests:?}"
    );
    let request = requests
        .iter()
        .find(|request| request["method"] == "raw/diagnostics")
        .expect("diagnostics request");
    assert_eq!(request["params"]["maxResults"], COMPACT_DIAGNOSTICS);
    assert_eq!(request["params"]["pageToken"], PAGE_TOKEN);

    let raw = String::from_utf8(output.stdout).expect("utf8");
    let stdout: Value = serde_json::from_str(&raw).expect("diagnostics json");
    let projected = stdout["result"]["diagnostics"]
        .as_array()
        .expect("projected diagnostics");
    assert_eq!(projected.len(), COMPACT_DIAGNOSTICS);
    assert_eq!(stdout["result"]["severityCounts"]["error"], 1);
    assert_eq!(
        stdout["result"]["severityCounts"]["warning"],
        TOTAL_DIAGNOSTICS - 1
    );
    assert_eq!(stdout["result"]["cardinality"]["type"], "EXACT");
    assert_eq!(
        stdout["result"]["cardinality"]["totalCount"],
        TOTAL_DIAGNOSTICS
    );
    assert_eq!(
        stdout["result"]["cardinality"]["returnedCount"],
        COMPACT_DIAGNOSTICS
    );
    assert!(
        stdout["result"]["cardinality"]["truncated"]
            .as_bool()
            .expect("truncated")
    );
    assert!(projected.iter().all(|diagnostic| {
        diagnostic["message"]
            .as_str()
            .expect("message")
            .chars()
            .count()
            <= 256
            && diagnostic["location"]["preview"]
                .as_str()
                .expect("preview")
                .chars()
                .count()
                <= 160
            && diagnostic["messageTruncated"] == true
            && diagnostic["location"]["previewTruncated"] == true
            && diagnostic["message"]
                .as_str()
                .expect("message")
                .ends_with('…')
            && diagnostic["location"]["preview"]
                .as_str()
                .expect("preview")
                .ends_with('…')
    }));
    assert_eq!(projected[0]["code"], "COMPILER_ERROR");
    assert_output_budget(&raw, DIAGNOSTICS_LINE_BUDGET, DIAGNOSTICS_TOKEN_BUDGET);
}
