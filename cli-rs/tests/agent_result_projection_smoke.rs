mod support;

use serde_json::{Value, json};
use support::*;

const SYMBOL_LINE_BUDGET: usize = 120;
const SYMBOL_TOKEN_BUDGET: usize = 1_500;
const DIAGNOSTICS_LINE_BUDGET: usize = 200;
const DIAGNOSTICS_TOKEN_BUDGET: usize = 2_500;
const MUTATION_LINE_BUDGET: usize = 100;
const MUTATION_TOKEN_BUDGET: usize = 1_200;
const VERIFY_LINE_BUDGET: usize = 100;
const VERIFY_TOKEN_BUDGET: usize = 1_200;

fn oversized_symbol_result(workspace: &Path) -> Value {
    let surrounding_members = (0..10)
        .map(|index| {
            json!({
                "fqName": format!("sample.Container.member{index}"),
                "kind": "FUNCTION",
                "documentation": "member detail ".repeat(5),
            })
        })
        .collect::<Vec<_>>();
    json!({
        "type": "RESOLVE_SUCCESS",
        "ok": true,
        "source": "compiler",
        "symbol": {
            "fqName": "sample.Container.target",
            "kind": "FUNCTION",
            "location": {
                "filePath": workspace.join("src/Container.kt").display().to_string(),
                "startOffset": 41,
                "endOffset": 47,
                "startLine": 4,
                "startColumn": 9,
                "preview": "target()"
            },
            "documentation": "oversized documentation ".repeat(100),
            "surroundingMembers": surrounding_members,
        },
        "ranking": {
            "traces": (0..10).map(|index| json!({
                "candidate": format!("sample.Candidate{index}"),
                "score": index,
                "explanation": "ranking evidence ".repeat(5),
            })).collect::<Vec<_>>()
        },
        "nextRequest": {
            "method": "symbol/references",
            "explanation": "next request explanation ".repeat(10),
        }
    })
}

fn run_symbol(extra_args: &[&str]) -> (Value, String, Vec<Value>) {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("idea.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![("symbol/resolve", oversized_symbol_result(&workspace))],
    );
    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Container.target",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .args(extra_args)
        .output()
        .expect("symbol command");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let requests = backend.join().expect("scripted backend");
    let raw = String::from_utf8(output.stdout).expect("utf8 output");
    let value = serde_json::from_str(&raw).expect("symbol json");
    (value, raw, requests)
}

#[test]
fn symbol_default_is_a_stable_compact_projection_within_budget() {
    let (stdout, raw, requests) = run_symbol(&[]);

    assert_eq!(
        stdout["result"],
        json!({
            "type": "KAST_AGENT_SYMBOL_RESULT",
            "ok": true,
            "mode": "exact",
            "confidenceMode": "exact",
            "outcome": "RESOLVED",
            "ambiguous": false,
            "source": "compiler",
            "identity": {
                "fqName": "sample.Container.target",
                "kind": "FUNCTION"
            },
            "location": {
                "filePath": stdout["result"]["location"]["filePath"],
                "startOffset": 41,
                "endOffset": 47,
                "startLine": 4,
                "startColumn": 9,
                "preview": "target()"
            },
            "relationships": [],
            "schemaVersion": 3
        })
    );
    assert!(stdout.get("request").is_none(), "{stdout}");
    assert!(stdout.get("response").is_none(), "{stdout}");
    assert_eq!(requests[2]["params"]["includeDocumentation"], false);
    assert_eq!(requests[2]["params"]["includeSurroundingMembers"], false);
    assert!(requests[2]["params"].get("surroundingLines").is_none());
    assert_output_budget(&raw, SYMBOL_LINE_BUDGET, SYMBOL_TOKEN_BUDGET);
}

#[test]
fn symbol_verbose_preserves_detailed_validated_evidence() {
    let (stdout, _, requests) = run_symbol(&["--verbose"]);

    assert_eq!(stdout["result"]["type"], "KAST_AGENT_SYMBOL_LOOKUP");
    assert!(
        stdout["result"]["outcome"]["resolution"]["symbol"]["surroundingMembers"]
            .as_array()
            .is_some_and(|members| members.len() == 10),
        "{stdout}"
    );
    assert_eq!(requests[2]["params"]["includeDocumentation"], true);
    assert_eq!(requests[2]["params"]["includeSurroundingMembers"], true);
    assert!(
        stdout["result"]["outcome"]["resolution"]["ranking"]["traces"]
            .as_array()
            .is_some_and(|traces| traces.len() == 10),
        "{stdout}"
    );
}

#[test]
fn symbol_explain_requests_and_preserves_explanatory_evidence() {
    let (stdout, _, requests) = run_symbol(&["--explain"]);

    assert_eq!(stdout["result"]["type"], "KAST_AGENT_SYMBOL_LOOKUP");
    assert_eq!(requests[2]["params"]["includeDocumentation"], true);
    assert_eq!(requests[2]["params"]["includeSurroundingMembers"], true);
    assert!(
        stdout["result"]["outcome"]["resolution"]["ranking"]["traces"]
            .as_array()
            .is_some_and(|traces| traces.len() == 10),
        "{stdout}"
    );
}

#[test]
fn symbol_fields_are_typed_and_selected_without_json_path_surgery() {
    let (stdout, _, _) = run_symbol(&["--fields", "identity,location"]);

    let mut fields = stdout["result"]
        .as_object()
        .expect("selected result")
        .keys()
        .map(String::as_str)
        .collect::<Vec<_>>();
    fields.sort_unstable();
    assert_eq!(
        fields,
        vec!["identity", "location", "ok", "schemaVersion", "type"]
    );
}

#[test]
fn symbol_count_returns_cardinality_without_candidate_payloads() {
    let (stdout, _, _) = run_symbol(&["--count"]);

    assert_eq!(stdout["result"]["resultCount"], 1);
    assert_eq!(stdout["result"]["candidateCount"], 0);
    assert_eq!(stdout["result"]["relationshipCount"], 0);
    assert!(stdout["result"].get("identity").is_none(), "{stdout}");
}

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
    let file = workspace.join("src/App.kt");
    let socket_path = temp.path().join("idea.sock");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            ("raw/workspace-refresh", json!({"ok": true})),
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
                    "semanticOutcome": "COMPLETE",
                    "requestedFileCount": 1,
                    "analyzedFileCount": 1,
                    "skippedFileCount": 0
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
        stdout["result"]["diagnostics"][0]["code"],
        "UNRESOLVED_REFERENCE"
    );
    assert!(stdout["result"].get("steps").is_none(), "{stdout}");
    assert_output_budget(&raw, DIAGNOSTICS_LINE_BUDGET, DIAGNOSTICS_TOKEN_BUDGET);
}

#[test]
fn mutation_default_exposes_state_files_edits_and_diagnostic_summary() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/Added.kt");
    let content = temp.path().join("Added.kt");
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&content, "class Added\n").expect("content");
    let backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![(
            "mutation/submit",
            json!({
                "operation": {
                    "operationId": "00000000-0000-0000-0000-000000000337",
                    "idempotencyKey": "issue-337-add-file",
                    "mutationKind": "ADD_FILE",
                    "state": {
                        "type": "COMPLETED",
                        "trace": {
                            "enteredStages": ["EDIT_APPLICATION", "DIAGNOSTICS"],
                            "editApplicationState": "COMPLETED",
                            "verboseTrace": "mutation trace ".repeat(200)
                        },
                        "cancellationRequested": false,
                        "result": {
                            "appliedEdits": [{
                                "filePath": file.display().to_string(),
                                "startOffset": 0,
                                "endOffset": 0
                            }],
                            "changedFiles": [file.display().to_string()],
                            "diagnostics": [{"severity": "WARNING", "message": "Unused"}]
                        }
                    }
                },
                "deduplicated": false
            }),
        )],
    );
    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "add-file",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--file-path",
            file.to_str().expect("file"),
            "--content-file",
            content.to_str().expect("content"),
            "--apply",
            "--idempotency-key",
            "issue-337-add-file",
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
    assert_eq!(stdout["result"]["operation"]["state"], "COMPLETED");
    assert_eq!(
        stdout["result"]["operation"]["editApplicationState"],
        "COMPLETED"
    );
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
    let socket_path = temp.path().join("idea.sock");
    let runtime = json!({
        "state": "READY",
        "healthy": true,
        "active": true,
        "indexing": false,
        "backendName": "idea",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "schemaVersion": 3
    });
    let capabilities = json!({
        "backendName": "idea",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "readCapabilities": ["symbol/resolve", "symbol/references"],
        "mutationCapabilities": ["mutation/submit"],
        "limits": {
            "requestTimeoutMillis": 60000,
            "maxResults": 1000,
            "maxConcurrentRequests": 4
        },
        "explanation": "capability explanation ".repeat(200),
        "schemaVersion": 3
    });
    let backend = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        vec![
            ("runtime/status", runtime.clone()),
            ("capabilities", capabilities.clone()),
            ("health", json!({"ok": true, "status": "READY"})),
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
    assert_eq!(stdout["result"]["runtime"]["backendName"], "idea");
    assert_eq!(stdout["result"]["capabilities"]["readCount"], 2);
    assert_eq!(stdout["result"]["capabilities"]["mutationCount"], 1);
    assert!(stdout["result"].get("steps").is_none(), "{stdout}");
    assert_output_budget(&raw, VERIFY_LINE_BUDGET, VERIFY_TOKEN_BUDGET);
}

fn assert_output_budget(output: &str, line_budget: usize, token_budget: usize) {
    let value: Value = serde_json::from_str(output).expect("budget fixture json");
    let measured = serde_json::to_string_pretty(&value).expect("pretty budget fixture");
    let lines = measured.lines().count();
    let bpe = tiktoken_rs::cl100k_base().expect("cl100k_base tokenizer");
    let tokens = bpe.encode_with_special_tokens(&measured).len();
    assert!(
        lines <= line_budget,
        "output used {lines} lines; budget is {line_budget}"
    );
    assert!(
        tokens <= token_budget,
        "output used {tokens} cl100k_base tokens; budget is {token_budget}"
    );
}
