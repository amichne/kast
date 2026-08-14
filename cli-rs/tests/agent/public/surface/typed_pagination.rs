use super::{protocol_identity, support, typed_protocol_json, typed_public_kast};

fn rejected_json(output: std::process::Output) -> serde_json::Value {
    assert_eq!(output.status.code(), Some(1), "{output:?}");
    assert!(output.stderr.is_empty(), "{output:?}");
    serde_json::from_slice(&output.stdout).expect("canonical rejected JSON")
}

#[test]
fn continuations_are_operation_bound_and_stale_closed() {
    let fixture = tempfile::tempdir().expect("temporary pagination fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    support::metrics::seed_source_index(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let declaration_file = workspace.join("lib/Bar.kt");
    let selector = "ksh1.pagination-proof";
    let raw_continuation = "3f63f3a8-23c1-4be8-a707-dde43fbabf55";

    let first_backend = support::spawn_ready_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("first-page.sock"),
        2,
        vec![
            (
                "selector/identity",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "identity": protocol_identity(&declaration_file, 20)
                }),
            ),
            (
                "symbol/references",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "subject": protocol_identity(&declaration_file, 20),
                    "references": [],
                    "evidence": {
                        "type": "RESUMABLE",
                        "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 0},
                        "coverage": {"limitations": ["FAMILY_SEARCH_IN_PROGRESS"]}
                    },
                    "page": {"nextPageToken": raw_continuation},
                    "schemaVersion": support::api_schema_version()
                }),
            ),
        ],
    );
    let first = typed_protocol_json(
        typed_public_kast(&home, &config_home, &workspace)
            .args([
                "--output",
                "json",
                "relation",
                "references",
                "--selector",
                selector,
            ])
            .output()
            .expect("first reference page"),
    );
    assert_eq!(first["status"], "qualified", "{first:#}");
    assert_eq!(
        first["result"]["page"]["cardinality"]["type"],
        "known-minimum"
    );
    let continuation = first["result"]["page"]["continuation"]
        .as_str()
        .expect("opaque continuation")
        .to_string();
    assert!(continuation.starts_with("kpc1.references."), "{first:#}");
    let first_requests = first_backend.join().expect("first reference backend");
    assert!(first_requests[1]["params"].get("pageToken").is_none());

    let stale_backend = support::spawn_ready_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("stale-page.sock"),
        2,
        vec![
            (
                "selector/identity",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "identity": protocol_identity(&declaration_file, 20)
                }),
            ),
            (
                "symbol/references",
                serde_json::json!({"type": "CURSOR_STALE"}),
            ),
        ],
    );
    let stale = rejected_json(
        typed_public_kast(&home, &config_home, &workspace)
            .args([
                "--output",
                "json",
                "relation",
                "references",
                "--selector",
                selector,
                "--continuation",
                &continuation,
            ])
            .output()
            .expect("stale reference page"),
    );
    assert_eq!(stale["operation"], "relation.references", "{stale:#}");
    assert_eq!(stale["result"]["type"], "rejected", "{stale:#}");
    assert_eq!(stale["result"]["failure"]["type"], "continuation-stale");
    let stale_requests = stale_backend.join().expect("stale reference backend");
    let stale_request = stale_requests
        .iter()
        .find(|request| request["method"] == "symbol/references")
        .expect("stale reference request");
    assert_eq!(stale_request["params"]["pageToken"], raw_continuation);

    let mismatch_backend = support::spawn_ready_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("mismatched-page.sock"),
        1,
        vec![(
            "selector/identity",
            serde_json::json!({
                "type": "AVAILABLE",
                "identity": protocol_identity(&declaration_file, 20)
            }),
        )],
    );
    let mismatch = rejected_json(
        typed_public_kast(&home, &config_home, &workspace)
            .args([
                "--output",
                "json",
                "relation",
                "calls",
                "incoming",
                "--selector",
                selector,
                "--continuation",
                &continuation,
            ])
            .output()
            .expect("cross-operation continuation"),
    );
    assert_eq!(
        mismatch["operation"], "relation.calls.incoming",
        "{mismatch:#}"
    );
    assert_eq!(
        mismatch["result"]["failure"]["type"],
        "continuation-mismatch"
    );
    let mismatch_requests = mismatch_backend.join().expect("mismatch backend");
    let semantic_requests = mismatch_requests
        .iter()
        .filter(|request| request["method"] == "selector/identity")
        .collect::<Vec<_>>();
    assert_eq!(semantic_requests.len(), 1, "{mismatch_requests:#?}");
    assert!(
        mismatch_requests
            .iter()
            .all(|request| request["method"] != "symbol/callers"),
        "cross-operation continuation reached semantic execution: {mismatch_requests:#?}"
    );
}
