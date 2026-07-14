mod support;

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
fn pending_relationship_commands_accept_exact_identity_selectors() {
    for (command, command_args) in [
        ("callers", vec!["--depth", "2"]),
        ("callees", vec!["--depth", "2"]),
        ("implementations", Vec::new()),
        ("hierarchy", vec!["--direction", "both", "--depth", "2"]),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut invocation = vec!["--output", "json", "agent", command];
        invocation.extend(exact_selector());
        invocation.extend(command_args);
        invocation.extend(["--limit", "17", "--fields", "subject,page"]);

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
        assert_eq!(
            stdout["error"]["code"], "RELATIONSHIP_ENDPOINT_UNAVAILABLE",
            "command={command} output={stdout}",
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
