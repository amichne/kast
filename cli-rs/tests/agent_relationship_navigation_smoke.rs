mod support;

use support::metrics::{seed_high_cardinality_impact, seed_source_index};
use support::{kast, spawn_scripted_idea_backend};

fn exact_selector() -> [&'static str; 6] {
    [
        "--symbol",
        "sample.Service.run",
        "--declaration-file",
        "src/main/kotlin/sample/Service.kt",
        "--declaration-start-offset",
        "42",
    ]
}

fn help_lists_command(stdout: &str, command: &str) -> bool {
    stdout
        .lines()
        .any(|line| line.trim_start().starts_with(command))
}

fn relation_identity(
    fq_name: &str,
    kind: &str,
    file: &std::path::Path,
    start_offset: u64,
) -> serde_json::Value {
    serde_json::json!({
        "fqName": fq_name,
        "kind": kind,
        "declarationFile": file,
        "declarationStartOffset": start_offset
    })
}

fn relation_location(file: &std::path::Path, start_offset: u64) -> serde_json::Value {
    serde_json::json!({
        "filePath": file,
        "startOffset": start_offset,
        "endOffset": start_offset + 1
    })
}

fn exact_relation_page(total_count: usize) -> serde_json::Value {
    serde_json::json!({
        "cardinality": {"type": "EXACT", "totalCount": total_count},
        "returnedCount": total_count,
        "visitedCandidateCount": total_count,
        "truncated": false
    })
}

fn call_relation_record(
    relation: &str,
    index: usize,
    workspace: &std::path::Path,
) -> serde_json::Value {
    let file = workspace.join(format!("Caller{index}.kt"));
    serde_json::json!({
        "relation": relation,
        "relatedSymbol": relation_identity(
            &format!("sample.Caller{index}.call"),
            "FUNCTION",
            &file,
            index as u64,
        ),
        "callSite": relation_location(&file, index as u64 + 10),
        "depth": 1,
        "containingSymbol": {"type": "TOP_LEVEL"}
    })
}

#[test]
fn standalone_relationship_commands_are_public() {
    let temp = tempfile::tempdir().expect("tempdir");
    let output = kast(&temp.path().join("home"), &temp.path().join("config"))
        .args(["agent", "--help"])
        .output()
        .expect("agent help");

    assert!(output.status.success());
    let stdout = String::from_utf8_lossy(&output.stdout);
    for command in [
        "references",
        "callers",
        "callees",
        "implementations",
        "hierarchy",
    ] {
        assert!(
            help_lists_command(&stdout, command),
            "agent help should show {command}: {stdout}",
        );
    }
}

#[test]
fn one_shot_symbol_relationship_flags_are_retired() {
    for retired_flag in [
        "--references",
        "--reference-page-token",
        "--callers",
        "--caller-depth",
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(["agent", "symbol", "--query", "Service", retired_flag])
            .output()
            .expect("retired symbol flag");

        assert_eq!(
            output.status.code(),
            Some(2),
            "flag={retired_flag} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}

#[test]
fn relationship_commands_accept_exact_identity_selectors() {
    for (command, command_args) in [
        ("callers", vec!["--depth", "2"]),
        ("callees", vec!["--depth", "2"]),
        ("implementations", Vec::new()),
        ("hierarchy", vec!["--direction", "both", "--depth", "2"]),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace = temp.path().join("workspace");
        let declaration_file = workspace.join("src/main/kotlin/sample/Service.kt");
        std::fs::create_dir_all(declaration_file.parent().expect("declaration parent"))
            .expect("declaration directory");
        std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
        let mut invocation = vec!["--output", "json", "agent", command];
        invocation.extend(exact_selector());
        invocation.extend(command_args);
        invocation.extend(["--limit", "17", "--fields", "subject,page"]);
        invocation.extend([
            "--workspace-root",
            workspace.to_str().expect("workspace root"),
        ]);

        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(invocation)
            .output()
            .expect("typed relationship command");

        assert_eq!(
            output.status.code(),
            Some(1),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("relationship error json");
        assert!(
            stdout["error"]["code"].is_string(),
            "command={command} output={stdout}"
        );
    }
}

#[test]
fn relationship_types_reject_invalid_values_before_runtime_io() {
    for (command, extra_args) in [
        ("references", vec!["--limit", "0"]),
        ("references", vec!["--limit", "201"]),
        ("references", vec!["--page-token", "not-a-token"]),
        ("callers", vec!["--depth", "0"]),
        ("callees", vec!["--depth", "9"]),
        ("hierarchy", vec!["--direction", "sideways"]),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut invocation = vec!["agent", command];
        invocation.extend(exact_selector());
        invocation.extend(extra_args);

        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args(invocation)
            .output()
            .expect("invalid relationship command");

        assert_eq!(
            output.status.code(),
            Some(2),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}

#[test]
fn impact_requires_the_reusable_exact_selector_and_bounded_controls() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");

    for args in [
        vec!["agent", "impact", "--symbol", "sample.Service"],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--limit",
            "0",
        ],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--limit",
            "201",
        ],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--depth",
            "0",
        ],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--depth",
            "9",
        ],
        vec![
            "agent",
            "impact",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "Service.kt",
            "--declaration-start-offset",
            "15",
            "--page-token",
            "not-an-impact-token",
        ],
    ] {
        let output = kast(&home, &config)
            .args(args)
            .output()
            .expect("invalid impact command");
        assert_eq!(
            output.status.code(),
            Some(2),
            "stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}

#[test]
fn impact_pages_are_query_bound_and_do_not_overlap() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    seed_source_index(&workspace);
    seed_high_cardinality_impact(&workspace, "lib.Foo", 500);
    let declaration_file =
        std::fs::canonicalize(workspace.join("lib/Foo.kt")).expect("impact declaration");
    let resolved = serde_json::json!({
        "symbol": {
            "fqName": "lib.Foo",
            "kind": "CLASS",
            "location": {
                "filePath": declaration_file,
                "startOffset": 1,
                "endOffset": 2
            }
        }
    });
    let run_page = |index: usize, page_token: Option<&str>| {
        let socket = temp.path().join(format!("impact-page-{index}.sock"));
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![("raw/resolve", resolved.clone())],
        );
        let mut args = vec![
            "--output",
            "json",
            "agent",
            "impact",
            "--symbol",
            "lib.Foo",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "class",
            "--depth",
            "3",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ];
        if let Some(token) = page_token {
            args.extend(["--page-token", token]);
        }
        let output = kast(&home, &config)
            .args(args)
            .output()
            .expect("impact page");
        let requests = backend.join().expect("impact backend");
        assert_eq!(
            requests.last().expect("resolve request")["method"],
            "raw/resolve"
        );
        assert_eq!(
            requests.last().expect("resolve request")["params"]["position"]["offset"],
            1
        );
        assert!(
            output.status.success(),
            "stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        serde_json::from_slice::<serde_json::Value>(&output.stdout).expect("impact page json")
    };

    let first = run_page(1, None);
    let token = first["result"]["nextPageToken"]
        .as_str()
        .expect("first impact page token")
        .to_string();
    let second = run_page(2, Some(&token));
    let first_paths = first["result"]["nodes"]
        .as_array()
        .expect("first nodes")
        .iter()
        .map(|node| node["sourcePath"].as_str().expect("first path"))
        .collect::<std::collections::BTreeSet<_>>();
    let second_paths = second["result"]["nodes"]
        .as_array()
        .expect("second nodes")
        .iter()
        .map(|node| node["sourcePath"].as_str().expect("second path"))
        .collect::<std::collections::BTreeSet<_>>();
    assert!(first_paths.is_disjoint(&second_paths));
    assert_eq!(second["result"]["query"]["offset"], 4);

    let mismatch = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "impact",
            "--symbol",
            "lib.Target",
            "--declaration-file",
            workspace
                .join("lib/Target.kt")
                .to_str()
                .expect("target file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "class",
            "--page-token",
            &token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("mismatched impact token");
    assert_eq!(mismatch.status.code(), Some(1));
    let mismatch: serde_json::Value =
        serde_json::from_slice(&mismatch.stdout).expect("impact mismatch json");
    assert_eq!(mismatch["error"]["code"], "IMPACT_PAGE_TOKEN_MISMATCH");
}

#[test]
fn impact_stops_before_sql_for_mismatched_and_unsupported_subjects() {
    for (index, kind, resolved_offset, expected_outcome) in [
        (0usize, "CLASS", 16u64, "SUBJECT_IDENTITY_MISMATCH"),
        (1usize, "PARAMETER", 15u64, "UNSUPPORTED_SUBJECT_KIND"),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let declaration_file = workspace.join("Service.kt");
        std::fs::create_dir_all(&workspace).expect("workspace");
        std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
        let canonical = std::fs::canonicalize(&declaration_file).expect("canonical source");
        let socket = temp.path().join(format!("impact-closed-{index}.sock"));
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![(
                "raw/resolve",
                serde_json::json!({
                    "symbol": {
                        "fqName": "sample.Service",
                        "kind": kind,
                        "location": {
                            "filePath": canonical,
                            "startOffset": resolved_offset,
                            "endOffset": resolved_offset + 1
                        }
                    }
                }),
            )],
        );
        let output = kast(&home, &config)
            .args([
                "--output",
                "json",
                "agent",
                "impact",
                "--symbol",
                "sample.Service",
                "--declaration-file",
                declaration_file.to_str().expect("declaration file"),
                "--declaration-start-offset",
                "15",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("closed impact outcome");
        backend.join().expect("closed impact backend");
        assert!(
            output.status.success(),
            "stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let result: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("closed impact json");
        assert_eq!(result["result"]["outcome"], expected_outcome, "{result}");
    }
}

#[test]
fn traversal_tokens_reject_wrong_query_or_relation_before_runtime_io() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let handle = "rth1_callers_00000000-0000-4000-8000-000000000339";

    for token in [
        format!("krp1.callers.000000000000000000000000.traversal.{handle}"),
        format!("krp1.callees.000000000000000000000000.traversal.{handle}"),
    ] {
        let output = kast(&temp.path().join("home"), &temp.path().join("config"))
            .args([
                "--output",
                "json",
                "agent",
                "callers",
                "--symbol",
                "sample.Service",
                "--declaration-file",
                declaration_file.to_str().expect("declaration file"),
                "--declaration-start-offset",
                "15",
                "--kind",
                "class",
                "--page-token",
                &token,
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("mismatched traversal token");
        assert_eq!(output.status.code(), Some(1));
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("mismatch json");
        assert_eq!(
            stdout["error"]["code"], "RELATION_PAGE_TOKEN_MISMATCH",
            "token={token} output={stdout}",
        );
    }
}

#[test]
fn exact_symbol_returns_one_reusable_anchored_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("idea.sock");
    let declaration_file = workspace.join("Service.kt");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "symbol": {
                    "fqName": "sample.Service.run",
                    "kind": "FUNCTION",
                    "containingType": "sample.Service",
                    "location": {
                        "filePath": declaration_file,
                        "startOffset": 42,
                        "endOffset": 45,
                        "startLine": 3,
                        "startColumn": 5
                    }
                }
            }),
        )],
    );

    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Service.run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("exact symbol");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value = serde_json::from_slice(&output.stdout).expect("symbol json");
    assert_eq!(
        stdout["result"]["identity"],
        serde_json::json!({
            "fqName": "sample.Service.run",
            "kind": "FUNCTION",
            "declarationFile": declaration_file,
            "declarationStartOffset": 42,
            "containingType": "sample.Service"
        })
    );
    let requests = backend.join().expect("scripted backend");
    assert_eq!(requests[2]["method"], "symbol/resolve");
}

#[test]
fn selector_handle_resolves_once_and_reuses_identity_for_references() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.test-issued-selector-handle";
    let selector = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);

    let resolve_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("selector-handle-resolve.sock"),
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "selectorHandle": selector_handle,
                "symbol": {
                    "fqName": "sample.Service.run",
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": declaration_file,
                        "startOffset": 42,
                        "endOffset": 45
                    }
                }
            }),
        )],
    );

    let resolved = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Service.run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("resolve selector handle");
    assert!(
        resolved.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&resolved.stdout),
        String::from_utf8_lossy(&resolved.stderr),
    );
    let resolved_json: serde_json::Value =
        serde_json::from_slice(&resolved.stdout).expect("resolved handle json");
    assert_eq!(resolved_json["result"]["identity"], selector);
    assert_eq!(
        resolved_json["result"]["selectorHandle"], selector_handle,
        "compact exact lookup must expose the backend-issued opaque handle",
    );
    let mut requests = resolve_backend.join().expect("resolve backend");

    let references_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("selector-handle-references.sock"),
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": selector,
                "references": [],
                "cardinality": {"type": "EXACT", "totalCount": 0},
                "schemaVersion": 3
            }),
        )],
    );
    let references = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--selector-handle",
            selector_handle,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("references by selector handle");
    assert!(
        references.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&references.stdout),
        String::from_utf8_lossy(&references.stderr),
    );
    requests.extend(references_backend.join().expect("references backend"));

    let semantic_requests = requests
        .iter()
        .filter(|request| {
            request["method"]
                .as_str()
                .is_some_and(|method| method.starts_with("symbol/"))
        })
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .collect::<Vec<_>>(),
        vec!["symbol/resolve", "symbol/references"],
        "selector reuse must not perform fuzzy or exact rediscovery",
    );
    assert_eq!(
        semantic_requests[1]["params"]["selectorHandle"],
        selector_handle,
    );
    assert!(semantic_requests[1]["params"].get("selector").is_none());
}

#[test]
fn selector_handle_drives_all_relationship_commands_without_explicit_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.test-issued-relationship-selector-handle";
    let function = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);
    let interface = relation_identity("sample.Service", "INTERFACE", &declaration_file, 10);
    let cases = vec![
        (
            "callers",
            "symbol/callers",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": function,
                "records": [],
                "page": exact_relation_page(0),
                "schemaVersion": 3
            }),
        ),
        (
            "callees",
            "symbol/callers",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": function,
                "records": [],
                "page": exact_relation_page(0),
                "schemaVersion": 3
            }),
        ),
        (
            "implementations",
            "symbol/implementations",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": interface,
                "records": [],
                "page": exact_relation_page(0),
                "schemaVersion": 3
            }),
        ),
        (
            "hierarchy",
            "symbol/hierarchy",
            vec!["--direction", "both"],
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": interface,
                "records": [],
                "page": exact_relation_page(0),
                "schemaVersion": 3
            }),
        ),
    ];

    for (index, (command_name, method, extra_args, response)) in cases.into_iter().enumerate() {
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &temp
                .path()
                .join(format!("selector-handle-{command_name}-{index}.sock")),
            vec![(method, response)],
        );
        let mut command = kast(&home, &config);
        command.args([
            "--output",
            "json",
            "agent",
            command_name,
            "--selector-handle",
            selector_handle,
        ]);
        command.args(extra_args);
        command.args(["--workspace-root", workspace.to_str().expect("workspace")]);
        let output = command.output().expect("relationship by selector handle");

        assert!(
            output.status.success(),
            "command={command_name} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let requests = backend.join().expect("relationship backend");
        assert_eq!(requests[2]["method"], method);
        assert_eq!(requests[2]["params"]["selectorHandle"], selector_handle);
        assert!(requests[2]["params"].get("selector").is_none());
    }
}

#[test]
fn exact_identity_drives_references_callers_continuation_and_impact_without_rediscovery() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    seed_source_index(&workspace);
    let declaration_file =
        std::fs::canonicalize(workspace.join("lib/Bar.kt")).expect("declaration file");
    let selector = relation_identity("lib.Bar", "FUNCTION", &declaration_file, 1);

    let resolve_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-resolve.sock"),
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "symbol": {
                    "fqName": "lib.Bar",
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": declaration_file,
                        "startOffset": 1,
                        "endOffset": 2
                    }
                }
            }),
        )],
    );
    let resolved = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "lib.Bar",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("resolve identity");
    assert!(
        resolved.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&resolved.stdout),
        String::from_utf8_lossy(&resolved.stderr),
    );
    let resolved_json: serde_json::Value =
        serde_json::from_slice(&resolved.stdout).expect("resolved identity json");
    assert_eq!(resolved_json["result"]["identity"], selector);
    let mut semantic_requests = resolve_backend.join().expect("resolve backend");

    let reference_handle = "00000000-0000-4000-8000-000000000337";
    let first_reference_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-references-first.sock"),
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": selector,
                "references": [{
                    "location": relation_location(&workspace.join("app/A.kt"), 30),
                    "containingSymbol": {"type": "TOP_LEVEL"}
                }],
                "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 2},
                "page": {
                    "truncated": true,
                    "nextPageToken": reference_handle
                },
                "schemaVersion": 3
            }),
        )],
    );
    let references = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "lib.Bar",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "function",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("references");
    assert!(references.status.success());
    let references_json: serde_json::Value =
        serde_json::from_slice(&references.stdout).expect("references json");
    assert_eq!(references_json["result"]["outcome"], "AVAILABLE");
    let reference_page_token = references_json["result"]["page"]["nextPageToken"]
        .as_str()
        .expect("reference page token")
        .to_string();
    semantic_requests.extend(
        first_reference_backend
            .join()
            .expect("first reference backend"),
    );

    let second_reference_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-references-second.sock"),
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": selector,
                "references": [],
                "cardinality": {"type": "EXACT", "totalCount": 1},
                "schemaVersion": 3
            }),
        )],
    );
    let reference_continuation = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "lib.Bar",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "function",
            "--limit",
            "4",
            "--page-token",
            &reference_page_token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("reference continuation");
    assert!(reference_continuation.status.success());
    semantic_requests.extend(
        second_reference_backend
            .join()
            .expect("second reference backend"),
    );

    let caller_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-callers.sock"),
        vec![(
            "symbol/callers",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": selector,
                "records": [{
                    "relation": "CALLER",
                    "relatedSymbol": relation_identity(
                        "app.A.render",
                        "FUNCTION",
                        &workspace.join("app/A.kt"),
                        10,
                    ),
                    "callSite": relation_location(&workspace.join("app/A.kt"), 30),
                    "depth": 1,
                    "containingSymbol": {"type": "TOP_LEVEL"}
                }],
                "page": exact_relation_page(1),
                "schemaVersion": 3
            }),
        )],
    );
    let callers = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "callers",
            "--symbol",
            "lib.Bar",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "function",
            "--depth",
            "3",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("callers");
    assert!(callers.status.success());
    let callers_json: serde_json::Value =
        serde_json::from_slice(&callers.stdout).expect("callers json");
    assert_eq!(callers_json["result"]["outcome"], "AVAILABLE");
    semantic_requests.extend(caller_backend.join().expect("callers backend"));

    let impact_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("identity-workflow-impact.sock"),
        vec![(
            "raw/resolve",
            serde_json::json!({
                "symbol": {
                    "fqName": "lib.Bar",
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": declaration_file,
                        "startOffset": 1,
                        "endOffset": 2
                    }
                }
            }),
        )],
    );
    let impact = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "impact",
            "--symbol",
            "lib.Bar",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "1",
            "--kind",
            "function",
            "--depth",
            "3",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("impact");
    assert!(impact.status.success());
    let impact_json: serde_json::Value =
        serde_json::from_slice(&impact.stdout).expect("impact json");
    assert_eq!(impact_json["result"]["outcome"], "DEGRADED");
    assert_eq!(
        impact_json["result"]["reason"],
        "IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE"
    );
    semantic_requests.extend(impact_backend.join().expect("impact backend"));

    let public_methods = semantic_requests
        .iter()
        .filter_map(|request| request["method"].as_str())
        .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
        .collect::<Vec<_>>();
    assert_eq!(
        public_methods,
        [
            "symbol/resolve",
            "symbol/references",
            "symbol/references",
            "symbol/callers",
            "raw/resolve",
        ]
    );
    assert!(semantic_requests.iter().all(|request| {
        !matches!(
            request["method"].as_str(),
            Some("symbol/query" | "workspace/search" | "workspace/symbols")
        )
    }));
    for request in semantic_requests.iter().filter(|request| {
        matches!(
            request["method"].as_str(),
            Some("symbol/references" | "symbol/callers")
        )
    }) {
        assert!(
            request["params"]["maxResults"]
                .as_u64()
                .is_some_and(|limit| limit <= 4)
        );
    }
}

#[test]
fn exact_symbol_does_not_publish_a_partial_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("idea.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/resolve",
            serde_json::json!({
                "type": "RESOLVE_SUCCESS",
                "ok": true,
                "source": "compiler",
                "symbol": {
                    "fqName": "sample.Service.run",
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": workspace.join("Service.kt")
                    }
                }
            }),
        )],
    );

    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Service.run",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("partial exact symbol");

    assert!(output.status.success());
    let stdout: serde_json::Value = serde_json::from_slice(&output.stdout).expect("symbol json");
    assert_eq!(stdout["result"]["outcome"], "IDENTITY_ANCHOR_UNAVAILABLE");
    assert!(stdout["result"]["identity"].is_null(), "{stdout}");
    backend.join().expect("scripted backend");
}

#[test]
fn references_send_the_exact_anchor_and_project_occurrence_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("idea.sock");
    let declaration_file = workspace.join("src/Service.kt");
    std::fs::create_dir_all(declaration_file.parent().expect("source parent"))
        .expect("source directory");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let reference_file = workspace.join("src/Client.kt");
    let canonical_declaration_file =
        std::fs::canonicalize(&declaration_file).expect("canonical declaration file");
    let backend_token = "00000000-0000-4000-8000-000000000337";
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": {
                    "fqName": "sample.Service",
                    "kind": "CLASS",
                    "declarationFile": declaration_file,
                    "declarationStartOffset": 15
                },
                "references": [{
                    "location": {
                        "filePath": reference_file,
                        "startOffset": 20,
                        "endOffset": 27,
                        "startLine": 2,
                        "startColumn": 5
                    },
                    "containingSymbol": {
                        "type": "KNOWN",
                        "symbol": {
                            "fqName": "sample.Client.run",
                            "kind": "FUNCTION",
                            "declarationFile": reference_file,
                            "declarationStartOffset": 10,
                            "containingType": "sample.Client"
                        }
                    }
                }],
                "cardinality": {
                    "type": "KNOWN_MINIMUM",
                    "knownMinimumCount": 2
                },
                "page": {
                    "truncated": true,
                    "nextPageToken": backend_token
                },
                "schemaVersion": 3
            }),
        )],
    );

    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "15",
            "--kind",
            "class",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("references");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("references json");
    assert_eq!(stdout["result"]["outcome"], "AVAILABLE");
    assert_eq!(stdout["result"]["relation"], "references");
    assert_eq!(stdout["result"]["subject"]["fqName"], "sample.Service");
    assert_eq!(stdout["result"]["records"][0]["relation"], "REFERENCE");
    assert_eq!(
        stdout["result"]["records"][0]["containingSymbol"]["symbol"]["fqName"],
        "sample.Client.run"
    );
    let public_token = stdout["result"]["page"]["nextPageToken"]
        .as_str()
        .expect("public page token")
        .to_string();
    assert!(public_token.starts_with("krp1.references."));
    assert!(public_token.ends_with(&format!(".reference.{backend_token}")));

    let requests = backend.join().expect("scripted backend");
    assert_eq!(requests[2]["method"], "symbol/references");
    assert_eq!(
        requests[2]["params"]["selector"],
        serde_json::json!({
            "fqName": "sample.Service",
            "declarationFile": canonical_declaration_file,
            "declarationStartOffset": 15,
            "kind": "CLASS"
        })
    );
    assert_eq!(requests[2]["params"]["includeDeclaration"], false);
    assert_eq!(requests[2]["params"]["maxResults"], 4);
    assert!(requests[2]["params"]["pageToken"].is_null());

    let continuation_socket = temp.path().join("idea-continuation.sock");
    let continuation_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &continuation_socket,
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": {
                    "fqName": "sample.Service",
                    "kind": "CLASS",
                    "declarationFile": declaration_file,
                    "declarationStartOffset": 15
                },
                "references": [],
                "cardinality": {"type": "EXACT", "totalCount": 1},
                "schemaVersion": 3
            }),
        )],
    );
    let continuation = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "15",
            "--kind",
            "class",
            "--page-token",
            &public_token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("reference continuation");
    assert!(
        continuation.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&continuation.stdout),
        String::from_utf8_lossy(&continuation.stderr),
    );
    let continuation_requests = continuation_backend.join().expect("continuation backend");
    assert_eq!(
        continuation_requests[2]["params"]["pageToken"],
        backend_token
    );

    let mismatch = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "sample.OtherService",
            "--declaration-file",
            "src/Service.kt",
            "--declaration-start-offset",
            "15",
            "--kind",
            "class",
            "--page-token",
            &public_token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("reference token mismatch");
    assert_eq!(mismatch.status.code(), Some(1));
    let mismatch: serde_json::Value =
        serde_json::from_slice(&mismatch.stdout).expect("mismatch json");
    assert_eq!(mismatch["error"]["code"], "RELATION_PAGE_TOKEN_MISMATCH");
}

#[test]
fn references_project_every_closed_non_available_outcome() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let selector = serde_json::json!({
        "fqName": "sample.Service",
        "declarationFile": declaration_file,
        "declarationStartOffset": 15,
        "kind": "CLASS"
    });
    let subject = serde_json::json!({
        "fqName": "sample.Service",
        "kind": "CLASS",
        "declarationFile": declaration_file,
        "declarationStartOffset": 15
    });
    let cases = [
        (
            "SUBJECT_NOT_FOUND",
            serde_json::json!({"type": "SUBJECT_NOT_FOUND", "selector": selector}),
        ),
        (
            "SUBJECT_IDENTITY_MISMATCH",
            serde_json::json!({
                "type": "SUBJECT_IDENTITY_MISMATCH",
                "selector": selector,
                "actual": subject
            }),
        ),
        (
            "UNSUPPORTED_SUBJECT_KIND",
            serde_json::json!({
                "type": "UNSUPPORTED_SUBJECT_KIND",
                "selector": selector,
                "subject": subject
            }),
        ),
        (
            "DEGRADED",
            serde_json::json!({
                "type": "DEGRADED",
                "selector": selector,
                "subject": subject,
                "reason": "REFERENCES_UNAVAILABLE"
            }),
        ),
        (
            "CURSOR_STALE",
            serde_json::json!({
                "type": "CURSOR_STALE",
                "selector": selector,
                "reason": "GENERATION_CHANGED"
            }),
        ),
        (
            "CURSOR_INVALID",
            serde_json::json!({
                "type": "CURSOR_INVALID",
                "selector": selector,
                "reason": "UNKNOWN_HANDLE"
            }),
        ),
    ];

    for (index, (expected_outcome, response)) in cases.into_iter().enumerate() {
        let socket = temp.path().join(format!("idea-{index}.sock"));
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![("symbol/references", response)],
        );
        let output = kast(&home, &config)
            .args([
                "--output",
                "json",
                "agent",
                "references",
                "--symbol",
                "sample.Service",
                "--declaration-file",
                declaration_file.to_str().expect("declaration file"),
                "--declaration-start-offset",
                "15",
                "--kind",
                "class",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("closed references outcome");
        assert!(
            output.status.success(),
            "outcome={expected_outcome} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("references outcome json");
        assert_eq!(stdout["result"]["outcome"], expected_outcome);
        assert_eq!(stdout["result"]["selector"]["fqName"], "sample.Service");
        backend.join().expect("scripted backend");
    }
}

#[test]
fn references_fail_closed_on_an_unknown_response_variant() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let socket = temp.path().join("idea.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/references",
            serde_json::json!({"type": "FAILURE", "code": "stringly"}),
        )],
    );
    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("invalid references outcome");
    assert_eq!(output.status.code(), Some(1));
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("invalid references json");
    assert_eq!(stdout["error"]["code"], "AGENT_RESULT_INVALID");
    backend.join().expect("scripted backend");
}

#[test]
fn references_fail_closed_on_malformed_expected_outcome_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let selector = serde_json::json!({
        "fqName": "sample.Service",
        "declarationFile": declaration_file,
        "declarationStartOffset": 15,
        "kind": "CLASS"
    });
    let malformed = [
        serde_json::json!({
            "type": "SUBJECT_NOT_FOUND",
            "selector": {
                "fqName": "",
                "declarationFile": declaration_file,
                "declarationStartOffset": 15
            }
        }),
        serde_json::json!({
            "type": "SUBJECT_IDENTITY_MISMATCH",
            "selector": selector,
            "actual": {
                "fqName": "sample.Service",
                "kind": "",
                "declarationFile": declaration_file,
                "declarationStartOffset": 15
            }
        }),
        serde_json::json!({
            "type": "DEGRADED",
            "selector": selector,
            "subject": {
                "fqName": "sample.Service",
                "kind": "CLASS",
                "declarationFile": "",
                "declarationStartOffset": 15
            },
            "reason": "REFERENCES_UNAVAILABLE"
        }),
    ];

    for (index, response) in malformed.into_iter().enumerate() {
        let socket = temp.path().join(format!("idea-malformed-{index}.sock"));
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![("symbol/references", response)],
        );
        let output = kast(&home, &config)
            .args([
                "--output",
                "json",
                "agent",
                "references",
                "--symbol",
                "sample.Service",
                "--declaration-file",
                declaration_file.to_str().expect("declaration file"),
                "--declaration-start-offset",
                "15",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("malformed expected references outcome");
        assert_eq!(output.status.code(), Some(1));
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("malformed references json");
        assert_eq!(stdout["error"]["code"], "AGENT_RESULT_INVALID");
        backend.join().expect("scripted backend");
    }
}

#[test]
fn compact_references_bound_high_cardinality_output() {
    const TOTAL_REFERENCES: usize = 500;
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let references = (0..4)
        .map(|index| {
            serde_json::json!({
                "location": {
                    "filePath": workspace.join(format!("Client{index}.kt")),
                    "startOffset": index * 10,
                    "endOffset": index * 10 + 7,
                    "startLine": index + 1,
                    "startColumn": 1,
                    "preview": "oversized semantic preview ".repeat(2_000)
                },
                "containingSymbol": {"type": "TOP_LEVEL"}
            })
        })
        .collect::<Vec<_>>();
    let socket = temp.path().join("idea.sock");
    let backend_token = "00000000-0000-4000-8000-000000000337";
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/references",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": {
                    "fqName": "sample.Service",
                    "kind": "CLASS",
                    "declarationFile": declaration_file,
                    "declarationStartOffset": 15
                },
                "references": references,
                "cardinality": {
                    "type": "KNOWN_MINIMUM",
                    "knownMinimumCount": TOTAL_REFERENCES
                },
                "page": {"truncated": true, "nextPageToken": backend_token},
                "schemaVersion": 3
            }),
        )],
    );
    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "references",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("compact high-cardinality references");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let requests = backend.join().expect("scripted backend");
    assert_eq!(requests[2]["params"]["maxResults"], 4);
    let raw = String::from_utf8(output.stdout).expect("references utf8");
    let stdout: serde_json::Value = serde_json::from_str(&raw).expect("references json");
    assert_eq!(
        stdout["result"]["records"]
            .as_array()
            .expect("reference records")
            .len(),
        4
    );
    assert_eq!(
        stdout["result"]["page"]["cardinality"]["knownMinimumCount"],
        TOTAL_REFERENCES
    );
    assert!(
        stdout["result"]["records"]
            .as_array()
            .expect("reference records")
            .iter()
            .all(|record| record["location"].get("preview").is_none())
    );
    assert!(raw.lines().count() <= 120, "{} lines", raw.lines().count());
    let tokens = tiktoken_rs::cl100k_base()
        .expect("cl100k tokenizer")
        .encode_with_special_tokens(&raw)
        .len();
    assert!(tokens <= 1_500, "{tokens} compact reference tokens");
}

#[test]
fn remaining_relationship_commands_reach_bounded_compiler_engines() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let canonical_file = std::fs::canonicalize(&declaration_file).expect("canonical source");

    for (index, (command, expected_direction, expected_relation)) in [
        ("callers", "incoming", "CALLER"),
        ("callees", "outgoing", "CALLEE"),
    ]
    .into_iter()
    .enumerate()
    {
        let socket = temp.path().join(format!("idea-call-{index}.sock"));
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![(
                "symbol/callers",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "subject": relation_identity(
                        "sample.Service.run",
                        "FUNCTION",
                        &canonical_file,
                        15,
                    ),
                    "records": [{
                        "relation": expected_relation,
                        "relatedSymbol": relation_identity(
                            "sample.Client.call",
                            "FUNCTION",
                            &workspace.join("Client.kt"),
                            20,
                        ),
                        "callSite": relation_location(&workspace.join("Client.kt"), 30),
                        "depth": 1,
                        "containingSymbol": {"type": "TOP_LEVEL"}
                    }],
                    "page": exact_relation_page(1),
                    "schemaVersion": 3
                }),
            )],
        );
        let output = kast(&home, &config)
            .args([
                "--output",
                "json",
                "agent",
                command,
                "--symbol",
                "sample.Service.run",
                "--declaration-file",
                declaration_file.to_str().expect("declaration file"),
                "--declaration-start-offset",
                "15",
                "--kind",
                "function",
                "--depth",
                "2",
                "--limit",
                "4",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .output()
            .expect("call relationship");
        assert!(
            output.status.success(),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("call relationship json");
        assert_eq!(stdout["result"]["outcome"], "AVAILABLE");
        assert_eq!(stdout["result"]["relation"], command);
        assert_eq!(
            stdout["result"]["records"][0]["relation"],
            expected_relation
        );
        let requests = backend.join().expect("call backend");
        assert_eq!(
            requests[2]["params"]["selector"]["declarationFile"],
            canonical_file.to_string_lossy().as_ref()
        );
        assert_eq!(
            requests[2]["params"]["selector"]["declarationStartOffset"],
            15
        );
        assert_eq!(requests[2]["params"]["direction"], expected_direction);
        assert_eq!(requests[2]["params"]["depth"], 2);
        assert_eq!(requests[2]["params"]["maxResults"], 4);
    }

    let implementations_socket = temp.path().join("idea-implementations.sock");
    let implementations_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &implementations_socket,
        vec![(
            "symbol/implementations",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": relation_identity(
                    "sample.Service",
                    "INTERFACE",
                    &canonical_file,
                    15,
                ),
                "records": [{
                    "relation": "IMPLEMENTATION",
                    "implementation": relation_identity(
                        "sample.RealService",
                        "CLASS",
                        &workspace.join("RealService.kt"),
                        10,
                    ),
                    "declarationLocation": relation_location(
                        &workspace.join("RealService.kt"),
                        10,
                    )
                }],
                "page": exact_relation_page(1),
                "schemaVersion": 3
            }),
        )],
    );
    let implementations = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "implementations",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "interface",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("implementations");
    assert!(implementations.status.success());
    let implementations_json: serde_json::Value =
        serde_json::from_slice(&implementations.stdout).expect("implementations json");
    assert_eq!(
        implementations_json["result"]["records"][0]["relation"],
        "IMPLEMENTATION"
    );
    let implementation_requests = implementations_backend
        .join()
        .expect("implementations backend");
    assert_eq!(implementation_requests[2]["params"]["maxResults"], 4);
    assert_eq!(
        implementation_requests[2]["params"]["selector"]["declarationFile"],
        canonical_file.to_string_lossy().as_ref()
    );

    let hierarchy_socket = temp.path().join("idea-hierarchy.sock");
    let hierarchy_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &hierarchy_socket,
        vec![(
            "symbol/hierarchy",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": relation_identity(
                    "sample.Service",
                    "INTERFACE",
                    &canonical_file,
                    15,
                ),
                "records": [{
                    "relation": "SUBTYPE",
                    "relatedSymbol": relation_identity(
                        "sample.RealService",
                        "CLASS",
                        &workspace.join("RealService.kt"),
                        10,
                    ),
                    "declarationLocation": relation_location(
                        &workspace.join("RealService.kt"),
                        10,
                    ),
                    "depth": 1
                }],
                "page": exact_relation_page(1),
                "schemaVersion": 3
            }),
        )],
    );
    let hierarchy = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "hierarchy",
            "--symbol",
            "sample.Service",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "interface",
            "--direction",
            "subtypes",
            "--depth",
            "2",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("hierarchy");
    assert!(hierarchy.status.success());
    let hierarchy_json: serde_json::Value =
        serde_json::from_slice(&hierarchy.stdout).expect("hierarchy json");
    assert_eq!(
        hierarchy_json["result"]["records"][0]["relation"],
        "SUBTYPE"
    );
    let hierarchy_requests = hierarchy_backend.join().expect("hierarchy backend");
    assert_eq!(hierarchy_requests[2]["params"]["direction"], "SUBTYPES");
    assert_eq!(hierarchy_requests[2]["params"]["depth"], 2);
    assert_eq!(hierarchy_requests[2]["params"]["maxResults"], 4);
}

#[test]
fn call_relationship_page_tokens_round_trip_only_the_backend_handle() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let canonical_file = std::fs::canonicalize(&declaration_file).expect("canonical source");
    let handle = "rth1_callers_00000000-0000-4000-8000-000000000339";
    let first_records = (0..4)
        .map(|index| call_relation_record("CALLER", index, &workspace))
        .collect::<Vec<_>>();
    let first_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("idea-first-page.sock"),
        vec![(
            "symbol/callers",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": relation_identity(
                    "sample.Service.run",
                    "FUNCTION",
                    &canonical_file,
                    15,
                ),
                "records": first_records,
                "page": {
                    "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 5},
                    "returnedCount": 4,
                    "visitedCandidateCount": 5,
                    "truncated": true,
                    "nextHandle": handle
                },
                "schemaVersion": 3
            }),
        )],
    );
    let first = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "callers",
            "--symbol",
            "sample.Service.run",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "function",
            "--depth",
            "2",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("first call page");
    assert!(
        first.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr),
    );
    let first_json: serde_json::Value =
        serde_json::from_slice(&first.stdout).expect("first page json");
    let public_token = first_json["result"]["page"]["nextPageToken"]
        .as_str()
        .expect("public traversal token")
        .to_string();
    assert!(public_token.starts_with("krp1.callers."));
    assert!(public_token.ends_with(&format!(".traversal.{handle}")));
    assert!(!public_token.contains("generation"));
    assert!(!public_token.contains("frontier"));
    let first_requests = first_backend.join().expect("first page backend");
    assert!(first_requests[2]["params"]["pageToken"].is_null());

    let second_record = call_relation_record("CALLER", 4, &workspace);
    let second_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("idea-second-page.sock"),
        vec![(
            "symbol/callers",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": relation_identity(
                    "sample.Service.run",
                    "FUNCTION",
                    &canonical_file,
                    15,
                ),
                "records": [second_record],
                "page": {
                    "cardinality": {"type": "EXACT", "totalCount": 5},
                    "returnedCount": 1,
                    "visitedCandidateCount": 1,
                    "truncated": false
                },
                "schemaVersion": 3
            }),
        )],
    );
    let second = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "callers",
            "--symbol",
            "sample.Service.run",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "function",
            "--depth",
            "2",
            "--limit",
            "4",
            "--page-token",
            &public_token,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("second call page");
    assert!(
        second.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&second.stdout),
        String::from_utf8_lossy(&second.stderr),
    );
    let second_json: serde_json::Value =
        serde_json::from_slice(&second.stdout).expect("second page json");
    let first_names = first_json["result"]["records"]
        .as_array()
        .expect("first records")
        .iter()
        .map(|record| {
            record["relatedSymbol"]["fqName"]
                .as_str()
                .expect("first name")
        })
        .collect::<std::collections::BTreeSet<_>>();
    let second_names = second_json["result"]["records"]
        .as_array()
        .expect("second records")
        .iter()
        .map(|record| {
            record["relatedSymbol"]["fqName"]
                .as_str()
                .expect("second name")
        })
        .collect::<std::collections::BTreeSet<_>>();
    assert!(first_names.is_disjoint(&second_names));
    assert_eq!(first_names.len() + second_names.len(), 5);
    assert!(second_json["result"]["page"]["nextPageToken"].is_null());
    let second_requests = second_backend.join().expect("second page backend");
    assert_eq!(second_requests[2]["params"]["pageToken"], handle);
}

#[test]
fn typed_relationship_commands_project_closed_non_available_outcomes() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let canonical_file = std::fs::canonicalize(&declaration_file).expect("canonical source");

    for (index, (command, method, kind, expected_outcome, response)) in [
        (
            "callers",
            "symbol/callers",
            "function",
            "DEGRADED",
            serde_json::json!({
                "type": "DEGRADED",
                "selector": {
                    "fqName": "sample.Service",
                    "declarationFile": canonical_file,
                    "declarationStartOffset": 15,
                    "kind": "FUNCTION"
                },
                "subject": relation_identity(
                    "sample.Service",
                    "FUNCTION",
                    &canonical_file,
                    15,
                ),
                "reason": "CALL_HIERARCHY_UNAVAILABLE"
            }),
        ),
        (
            "implementations",
            "symbol/implementations",
            "function",
            "UNSUPPORTED_SUBJECT_KIND",
            serde_json::json!({
                "type": "UNSUPPORTED_SUBJECT_KIND",
                "selector": {
                    "fqName": "sample.Service",
                    "declarationFile": canonical_file,
                    "declarationStartOffset": 15,
                    "kind": "FUNCTION"
                },
                "subject": relation_identity(
                    "sample.Service",
                    "FUNCTION",
                    &canonical_file,
                    15,
                )
            }),
        ),
        (
            "hierarchy",
            "symbol/hierarchy",
            "class",
            "DEGRADED",
            serde_json::json!({
                "type": "DEGRADED",
                "selector": {
                    "fqName": "sample.Service",
                    "declarationFile": canonical_file,
                    "declarationStartOffset": 15,
                    "kind": "CLASS"
                },
                "subject": relation_identity(
                    "sample.Service",
                    "CLASS",
                    &canonical_file,
                    15,
                ),
                "reason": "TYPE_HIERARCHY_UNAVAILABLE"
            }),
        ),
    ]
    .into_iter()
    .enumerate()
    {
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &temp.path().join(format!("idea-outcome-{index}.sock")),
            vec![(method, response)],
        );
        let mut args = vec![
            "--output".to_string(),
            "json".to_string(),
            "agent".to_string(),
            command.to_string(),
            "--symbol".to_string(),
            "sample.Service".to_string(),
            "--declaration-file".to_string(),
            declaration_file.to_string_lossy().into_owned(),
            "--declaration-start-offset".to_string(),
            "15".to_string(),
            "--kind".to_string(),
            kind.to_string(),
        ];
        if command == "hierarchy" {
            args.extend(["--direction".to_string(), "subtypes".to_string()]);
        }
        args.extend([
            "--workspace-root".to_string(),
            workspace.to_string_lossy().into_owned(),
        ]);
        let output = kast(&home, &config)
            .args(args)
            .output()
            .expect("closed relationship outcome");
        assert!(
            output.status.success(),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("relationship outcome json");
        assert_eq!(stdout["result"]["outcome"], expected_outcome);
        assert!(stdout["result"].get("records").is_none());
        backend.join().expect("outcome backend");
    }
}

#[test]
fn typed_relationship_commands_reject_inconsistent_non_available_identity_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let canonical_file = std::fs::canonicalize(&declaration_file).expect("canonical source");
    let selector = serde_json::json!({
        "fqName": "sample.Service",
        "declarationFile": canonical_file,
        "declarationStartOffset": 15,
        "kind": "CLASS"
    });

    for (index, (command, method, kind, direction, response)) in [
        (
            "callers",
            "symbol/callers",
            "function",
            None,
            serde_json::json!({
                "type": "DEGRADED",
                "selector": {
                    "fqName": "sample.Service",
                    "declarationFile": canonical_file,
                    "declarationStartOffset": 15,
                    "kind": "FUNCTION"
                },
                "subject": relation_identity(
                    "sample.OtherService",
                    "FUNCTION",
                    &canonical_file,
                    15,
                ),
                "reason": "CALL_HIERARCHY_UNAVAILABLE"
            }),
        ),
        (
            "implementations",
            "symbol/implementations",
            "class",
            None,
            serde_json::json!({
                "type": "UNSUPPORTED_SUBJECT_KIND",
                "selector": selector.clone(),
                "subject": relation_identity(
                    "sample.Service",
                    "CLASS",
                    &canonical_file,
                    15,
                )
            }),
        ),
        (
            "hierarchy",
            "symbol/hierarchy",
            "class",
            Some("subtypes"),
            serde_json::json!({
                "type": "SUBJECT_IDENTITY_MISMATCH",
                "selector": selector,
                "actual": relation_identity(
                    "sample.Service",
                    "CLASS",
                    &canonical_file,
                    15,
                )
            }),
        ),
    ]
    .into_iter()
    .enumerate()
    {
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &temp
                .path()
                .join(format!("idea-invalid-outcome-{index}.sock")),
            vec![(method, response)],
        );
        let mut args = vec![
            "--output".to_string(),
            "json".to_string(),
            "agent".to_string(),
            command.to_string(),
            "--symbol".to_string(),
            "sample.Service".to_string(),
            "--declaration-file".to_string(),
            declaration_file.to_string_lossy().into_owned(),
            "--declaration-start-offset".to_string(),
            "15".to_string(),
            "--kind".to_string(),
            kind.to_string(),
        ];
        if let Some(direction) = direction {
            args.extend(["--direction".to_string(), direction.to_string()]);
        }
        args.extend([
            "--workspace-root".to_string(),
            workspace.to_string_lossy().into_owned(),
        ]);
        let output = kast(&home, &config)
            .args(args)
            .output()
            .expect("invalid relationship outcome");
        assert_eq!(
            output.status.code(),
            Some(1),
            "command={command} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("invalid relationship outcome json");
        assert_eq!(stdout["error"]["code"], "AGENT_RESULT_INVALID");
        backend.join().expect("invalid outcome backend");
    }
}

#[test]
fn call_relationships_fail_closed_on_over_depth_backend_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let socket = temp.path().join("idea-over-depth.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &socket,
        vec![(
            "symbol/callers",
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": relation_identity(
                    "sample.Service.run",
                    "FUNCTION",
                    &std::fs::canonicalize(&declaration_file).expect("canonical source"),
                    15,
                ),
                "records": [{
                    "relation": "CALLER",
                    "relatedSymbol": relation_identity(
                        "sample.Second.call",
                        "FUNCTION",
                        &workspace.join("Second.kt"),
                        40,
                    ),
                    "callSite": relation_location(&workspace.join("Second.kt"), 50),
                    "depth": 2,
                    "containingSymbol": {"type": "TOP_LEVEL"}
                }],
                "page": exact_relation_page(1),
                "schemaVersion": 3
            }),
        )],
    );
    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            "callers",
            "--symbol",
            "sample.Service.run",
            "--declaration-file",
            declaration_file.to_str().expect("declaration file"),
            "--declaration-start-offset",
            "15",
            "--kind",
            "function",
            "--depth",
            "1",
            "--limit",
            "4",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("over-depth call relationship");
    assert_eq!(output.status.code(), Some(1));
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("over-depth json");
    assert_eq!(stdout["error"]["code"], "AGENT_RESULT_INVALID");
    backend.join().expect("over-depth backend");
}
