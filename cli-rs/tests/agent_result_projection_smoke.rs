mod support;

use serde_json::{Value, json};
use support::metrics::{seed_high_cardinality_impact, seed_source_index};
use support::*;

const SYMBOL_LINE_BUDGET: usize = 120;
const SYMBOL_TOKEN_BUDGET: usize = 1_500;
const IMPACT_LINE_BUDGET: usize = 120;
const IMPACT_TOKEN_BUDGET: usize = 1_500;
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
    assert_eq!(
        stdout["result"]["relationshipCardinality"],
        json!({"knownMinimumCount": 0, "exact": true})
    );
    assert!(stdout["result"].get("identity").is_none(), "{stdout}");
}

#[test]
fn symbol_relationships_bound_requests_and_compact_a_143k_token_result() {
    const RELATION_ITEMS: usize = 500;
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let verbose_socket_path = temp.path().join("idea-verbose.sock");
    let noise = "high cardinality relationship evidence ".repeat(72);
    let references = (0..RELATION_ITEMS)
        .map(|index| {
            json!({
                "filePath": workspace.join(format!("src/Reference{index}.kt")).display().to_string(),
                "startOffset": index,
                "endOffset": index + 1,
                "preview": noise,
            })
        })
        .collect::<Vec<_>>();
    let callers = (0..RELATION_ITEMS)
        .map(|index| {
            json!({
                "symbol": {
                    "fqName": format!("sample.Caller{index}"),
                    "kind": "FUNCTION",
                    "location": {
                        "filePath": workspace.join(format!("src/Caller{index}.kt")).display().to_string(),
                        "startOffset": index,
                        "endOffset": index + 1,
                        "preview": noise,
                    }
                },
                "callSite": {
                    "filePath": workspace.join(format!("src/Caller{index}.kt")).display().to_string(),
                    "startOffset": index,
                    "endOffset": index + 1,
                    "preview": noise,
                },
                "children": []
            })
        })
        .collect::<Vec<_>>();
    let verbose_backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &verbose_socket_path,
        vec![
            ("symbol/resolve", oversized_symbol_result(&workspace)),
            (
                "symbol/references",
                json!({
                    "type": "REFERENCES_SUCCESS",
                    "ok": true,
                    "query": {"symbol": "sample.Container.target", "maxResults": RELATION_ITEMS},
                    "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                    "filePath": workspace.join("src/Container.kt").display().to_string(),
                    "offset": 41,
                    "references": references,
                    "cardinality": {"type": "EXACT", "totalCount": RELATION_ITEMS},
                    "logFile": ""
                }),
            ),
            (
                "symbol/callers",
                json!({
                    "type": "CALLERS_SUCCESS",
                    "ok": true,
                    "query": {"symbol": "sample.Container.target", "maxTotalCalls": RELATION_ITEMS, "maxChildrenPerNode": RELATION_ITEMS},
                    "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                    "filePath": workspace.join("src/Container.kt").display().to_string(),
                    "offset": 41,
                    "root": {
                        "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                        "children": callers
                    },
                    "stats": {
                        "totalNodes": RELATION_ITEMS + 1,
                        "totalEdges": RELATION_ITEMS,
                        "truncatedNodes": 0,
                        "maxDepthReached": 1,
                        "timeoutReached": false,
                        "maxTotalCallsReached": false,
                        "maxChildrenPerNodeReached": false,
                        "filesVisited": RELATION_ITEMS
                    },
                    "logFile": ""
                }),
            ),
        ],
    );
    let verbose_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Container.target",
            "--references",
            "--callers",
            "incoming",
            "--limit",
            "500",
            "--verbose",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("verbose high-cardinality symbol relationships");
    assert!(
        verbose_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&verbose_output.stdout),
        String::from_utf8_lossy(&verbose_output.stderr),
    );
    let verbose_requests = verbose_backend
        .join()
        .expect("verbose relationship backend");
    assert_eq!(verbose_requests[3]["params"]["maxResults"], RELATION_ITEMS);
    assert_eq!(
        verbose_requests[4]["params"]["maxTotalCalls"],
        RELATION_ITEMS
    );
    assert_eq!(
        verbose_requests[4]["params"]["maxChildrenPerNode"],
        RELATION_ITEMS
    );
    let verbose_raw = String::from_utf8(verbose_output.stdout).expect("verbose utf8 output");
    let verbose_tokens = cl100k_tokens(&verbose_raw);
    assert!(
        verbose_tokens >= 143_000,
        "verbose command must preserve the reviewed 143k-token scenario; measured {verbose_tokens}"
    );

    let compact_socket_path = temp.path().join("idea-compact.sock");
    let compact_backend = spawn_scripted_idea_backend(
        &home,
        &config_home,
        &workspace,
        &compact_socket_path,
        vec![
            ("symbol/resolve", oversized_symbol_result(&workspace)),
            (
                "symbol/references",
                json!({
                    "type": "REFERENCES_SUCCESS",
                    "ok": true,
                    "query": {"symbol": "sample.Container.target", "maxResults": 4},
                    "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                    "filePath": workspace.join("src/Container.kt").display().to_string(),
                    "offset": 41,
                    "references": references.into_iter().take(4).collect::<Vec<_>>(),
                    "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 4},
                    "page": {
                        "truncated": true,
                        "nextPageToken": "00000000-0000-4000-8000-000000000337"
                    },
                    "logFile": ""
                }),
            ),
            (
                "symbol/callers",
                json!({
                    "type": "CALLERS_SUCCESS",
                    "ok": true,
                    "query": {"symbol": "sample.Container.target", "maxTotalCalls": 4, "maxChildrenPerNode": 4},
                    "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                    "filePath": workspace.join("src/Container.kt").display().to_string(),
                    "offset": 41,
                    "root": {
                        "symbol": {"fqName": "sample.Container.target", "kind": "FUNCTION"},
                        "children": callers.into_iter().take(4).collect::<Vec<_>>()
                    },
                    "stats": {
                        "totalNodes": RELATION_ITEMS + 1,
                        "totalEdges": RELATION_ITEMS,
                        "truncatedNodes": RELATION_ITEMS - 4,
                        "maxDepthReached": 1,
                        "timeoutReached": false,
                        "maxTotalCallsReached": true,
                        "maxChildrenPerNodeReached": true,
                        "filesVisited": 4
                    },
                    "logFile": ""
                }),
            ),
        ],
    );
    let compact_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "symbol",
            "--query",
            "sample.Container.target",
            "--references",
            "--callers",
            "incoming",
            "--limit",
            "500",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("compact high-cardinality symbol relationships");
    assert!(
        compact_output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact_output.stdout),
        String::from_utf8_lossy(&compact_output.stderr),
    );
    let requests = compact_backend
        .join()
        .expect("compact relationship backend");
    let raw = String::from_utf8(compact_output.stdout).expect("compact utf8 output");
    let stdout: Value = serde_json::from_str(&raw).expect("relationship json");
    let relationships = stdout["result"]["relationships"]
        .as_array()
        .expect("relationships");

    assert_eq!(requests[3]["params"]["maxResults"], 4);
    assert_eq!(requests[4]["params"]["maxTotalCalls"], 4);
    assert_eq!(requests[4]["params"]["maxChildrenPerNode"], 4);
    assert_eq!(relationships[0]["cardinality"]["type"], "KNOWN_MINIMUM");
    assert_eq!(relationships[0]["cardinality"]["knownMinimumCount"], 4);
    assert_eq!(relationships[0]["returnedCount"], 4);
    assert_eq!(relationships[0]["truncated"], true);
    assert_eq!(
        relationships[0]["nextPageToken"],
        "00000000-0000-4000-8000-000000000337"
    );
    assert_eq!(
        relationships[0]["items"]
            .as_array()
            .expect("references")
            .len(),
        4
    );
    assert_eq!(relationships[1]["cardinality"]["type"], "KNOWN_MINIMUM");
    assert_eq!(
        relationships[1]["cardinality"]["knownMinimumCount"],
        RELATION_ITEMS
    );
    assert_eq!(relationships[1]["returnedCount"], 4);
    assert_eq!(relationships[1]["truncated"], true);
    assert_eq!(
        relationships[1]["items"].as_array().expect("callers").len(),
        4
    );
    assert!(
        relationships[0]["items"][0]["location"]
            .get("preview")
            .is_none()
    );
    assert!(
        relationships[1]["items"][0]["location"]
            .get("preview")
            .is_none()
    );
    assert_output_budget(&raw, SYMBOL_LINE_BUDGET, SYMBOL_TOKEN_BUDGET);
}

#[test]
fn impact_default_is_typed_bounded_and_supports_selected_and_count_views() {
    const HIGH_CARDINALITY_IMPACT_NODES: usize = 500;
    const EXISTING_IMPACT_NODES: usize = 3;
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    seed_source_index(&workspace);
    seed_high_cardinality_impact(&workspace, "lib.Foo", HIGH_CARDINALITY_IMPACT_NODES);

    let run = |view: &[&str]| {
        kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "impact",
                "--symbol",
                "lib.Foo",
                "--depth",
                "3",
                "--workspace-root",
                workspace.to_str().expect("workspace"),
            ])
            .args(view)
            .output()
            .expect("agent impact")
    };

    let output = run(&[]);
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let raw = String::from_utf8(output.stdout).expect("impact utf8");
    let stdout: Value = serde_json::from_str(&raw).expect("impact json");
    assert_eq!(stdout["result"]["type"], "KAST_AGENT_IMPACT_RESULT");
    assert_eq!(stdout["result"]["query"]["symbol"], "lib.Foo");
    assert_eq!(stdout["result"]["query"]["depth"], 3);
    assert_eq!(stdout["result"]["query"]["limit"], 4);
    assert_eq!(
        stdout["result"]["query"]["workspaceRoot"],
        workspace.display().to_string()
    );
    assert_eq!(
        stdout["result"]["totalCount"],
        HIGH_CARDINALITY_IMPACT_NODES + EXISTING_IMPACT_NODES
    );
    assert_eq!(stdout["result"]["returnedCount"], 4);
    assert_eq!(stdout["result"]["truncated"], true);
    assert_eq!(
        stdout["result"]["nodes"].as_array().expect("nodes").len(),
        4
    );
    assert!(stdout["result"].get("confidence").is_some(), "{stdout}");
    assert_output_budget(&raw, IMPACT_LINE_BUDGET, IMPACT_TOKEN_BUDGET);

    let selected: Value = serde_json::from_slice(&run(&["--fields", "query,confidence"]).stdout)
        .expect("selected impact json");
    assert_eq!(selected["result"]["type"], "KAST_AGENT_IMPACT_SELECTION");
    assert!(selected["result"].get("query").is_some(), "{selected}");
    assert!(selected["result"].get("confidence").is_some(), "{selected}");
    assert!(selected["result"].get("nodes").is_none(), "{selected}");

    let count: Value =
        serde_json::from_slice(&run(&["--count"]).stdout).expect("count impact json");
    assert_eq!(count["result"]["type"], "KAST_AGENT_IMPACT_COUNT");
    assert_eq!(
        count["result"]["totalCount"],
        HIGH_CARDINALITY_IMPACT_NODES + EXISTING_IMPACT_NODES
    );
    assert_eq!(count["result"]["returnedCount"], 4);
    assert!(count["result"].get("nodes").is_none(), "{count}");

    for detailed_view in ["--verbose", "--explain"] {
        let detailed: Value =
            serde_json::from_slice(&run(&[detailed_view]).stdout).expect("detailed impact json");
        assert_eq!(detailed["result"]["type"], "KAST_AGENT_COMMAND");
        assert_eq!(
            detailed["result"]["steps"][0]["result"]["type"],
            "METRICS_SUCCESS"
        );
        assert_eq!(
            detailed["result"]["steps"][0]["result"]["totalCount"],
            HIGH_CARDINALITY_IMPACT_NODES + EXISTING_IMPACT_NODES
        );
    }
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

#[test]
fn mutation_default_exposes_state_files_edits_and_diagnostic_summary() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let file = workspace.join("src/Added.kt");
    let socket_path = temp.path().join("idea.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    write_gradle_marker(&workspace);
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
                    "idempotencyKey": "issue-337-rename",
                    "mutationKind": "RENAME",
                    "state": {
                        "type": "COMPLETED",
                        "trace": {
                            "enteredStages": ["EDIT_APPLICATION", "DIAGNOSTICS"],
                            "editApplicationState": "COMPLETED",
                            "verboseTrace": "mutation trace ".repeat(200)
                        },
                        "cancellationRequested": false,
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
                                    "clean": true,
                                    "errorCount": 0,
                                    "warningCount": 1,
                                    "semanticOutcome": "COMPLETE",
                                    "requestedFileCount": 1,
                                    "analyzedFileCount": 1,
                                    "skippedFileCount": 0,
                                    "errors": []
                                }
                            }
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
    write_gradle_marker(&workspace);
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
        "readCapabilities": ["WORKSPACE_FILES", "symbol/resolve", "symbol/references"],
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
        "schemaVersion": 3
    })
}

fn cl100k_tokens(value: &str) -> usize {
    tiktoken_rs::cl100k_base()
        .expect("cl100k_base tokenizer")
        .encode_with_special_tokens(value)
        .len()
}
