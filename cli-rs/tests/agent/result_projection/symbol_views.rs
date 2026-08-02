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
    let socket_path = temp.path().join("indexer.sock");
    let backend = spawn_scripted_indexer_backend(
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
                "kind": "FUNCTION",
                "declarationFile": stdout["result"]["location"]["filePath"],
                "declarationStartOffset": 41
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
            "schemaVersion": 5
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
