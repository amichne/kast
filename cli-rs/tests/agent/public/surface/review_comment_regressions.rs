use super::*;

fn relationship_identity(
    fq_name: &str,
    kind: &str,
    file: &Path,
    start_offset: u64,
) -> serde_json::Value {
    serde_json::json!({
        "fqName": fq_name,
        "kind": kind,
        "declarationFile": file,
        "declarationStartOffset": start_offset
    })
}

fn rejected_protocol(output: std::process::Output) -> serde_json::Value {
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(output.stderr.is_empty(), "{output:?}");
    serde_json::from_slice(&output.stdout).expect("canonical rejected protocol JSON")
}

#[test]
fn review_comment_regression_relationship_failures_retain_validated_subject_evidence() {
    for case in ["mismatch", "unsupported", "malformed-selector"] {
        let fixture = tempfile::tempdir().expect("temporary relationship failure fixture");
        let home = fixture.path().join("home");
        let config = fixture.path().join("config");
        let workspace = fixture.path().join("workspace");
        support::metrics::seed_source_index(&workspace);
        let workspace = workspace.canonicalize().expect("canonical workspace");
        let selector = format!("ksh1.review-{case}");
        let function = relationship_identity(
            "sample.Service.run",
            "FUNCTION",
            &workspace.join("app/A.kt"),
            42,
        );
        let unsupported =
            relationship_identity("sample.Service", "CLASS", &workspace.join("lib/Foo.kt"), 1);
        let (authenticated, response) = match case {
            "mismatch" => (
                function.clone(),
                serde_json::json!({
                    "type": "SUBJECT_IDENTITY_MISMATCH",
                    "selector": function,
                    "actual": relationship_identity(
                        "sample.Service.run",
                        "FUNCTION",
                        &workspace.join("app/A.kt"),
                        43,
                    )
                }),
            ),
            "unsupported" => (
                unsupported.clone(),
                serde_json::json!({
                    "type": "UNSUPPORTED_SUBJECT_KIND",
                    "selector": unsupported,
                    "subject": relationship_identity(
                        "sample.Service",
                        "CLASS",
                        &workspace.join("lib/Foo.kt"),
                        1,
                    )
                }),
            ),
            "malformed-selector" => (
                function.clone(),
                serde_json::json!({
                    "type": "SUBJECT_IDENTITY_MISMATCH",
                    "selector": relationship_identity(
                        "sample.Other.run",
                        "FUNCTION",
                        &workspace.join("app/B.kt"),
                        7,
                    ),
                    "actual": relationship_identity(
                        "sample.Service.run",
                        "FUNCTION",
                        &workspace.join("app/A.kt"),
                        43,
                    )
                }),
            ),
            _ => unreachable!(),
        };
        let backend = support::spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &fixture.path().join("relationship.sock"),
            vec![
                (
                    "selector/identity",
                    serde_json::json!({"type": "AVAILABLE", "identity": authenticated}),
                ),
                ("symbol/callers", response),
            ],
        );

        let value = rejected_protocol(
            named("kast")
                .current_dir(&workspace)
                .env("HOME", &home)
                .env("KAST_CONFIG_HOME", &config)
                .args([
                    "--output",
                    "json",
                    "relation",
                    "calls",
                    "incoming",
                    "--selector",
                    &selector,
                ])
                .output()
                .expect("run relationship failure"),
        );

        if case == "malformed-selector" {
            assert_eq!(
                value["result"]["failure"]["type"], "backend-contract-violation",
                "{value:#}",
            );
        } else {
            assert_eq!(
                value["result"]["failure"]["selector"], selector,
                "{value:#}"
            );
            let evidence = if case == "mismatch" {
                "actual"
            } else {
                "subject"
            };
            assert!(
                value["result"]["failure"][evidence]["fqName"].is_string(),
                "{value:#}",
            );
            assert!(
                value["result"]["failure"][evidence]["declarationFile"]
                    .as_str()
                    .is_some_and(|path| !path.starts_with('/')),
                "{value:#}",
            );
        }
        let requests = backend.join().expect("relationship backend");
        assert_eq!(
            requests.last().expect("relationship request")["method"],
            "symbol/callers"
        );
    }
}

#[test]
fn review_comment_regression_references_retain_containing_symbol_evidence() {
    let fixture = tempfile::tempdir().expect("temporary reference evidence fixture");
    let home = fixture.path().join("home");
    let config = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    support::metrics::seed_source_index(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let declaration_file = workspace.join("lib/Bar.kt");
    let selector = "ksh1.reference-owner-evidence";
    let location = |file: &str, offset: u64| {
        serde_json::json!({
            "filePath": workspace.join(file),
            "startOffset": offset,
            "endOffset": offset + 1,
            "startLine": 1,
            "startColumn": offset + 1,
            "preview": "Bar()"
        })
    };
    let backend = support::spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &fixture.path().join("references.sock"),
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
                    "references": [
                        {
                            "location": location("app/A.kt", 5),
                            "containingSymbol": {
                                "type": "KNOWN",
                                "symbol": protocol_identity(&workspace.join("app/A.kt"), 7)
                            }
                        },
                        {
                            "location": location("app/B.kt", 6),
                            "containingSymbol": {"type": "TOP_LEVEL"}
                        },
                        {
                            "location": location("lib/Bar.kt", 7),
                            "containingSymbol": {
                                "type": "UNAVAILABLE",
                                "reason": "IDENTITY_UNAVAILABLE"
                            }
                        }
                    ],
                    "evidence": complete_protocol_relationship_evidence(3)
                }),
            ),
        ],
    );

    let value = typed_protocol_json(
        typed_public_kast(&home, &config, &workspace)
            .args([
                "--output",
                "json",
                "relation",
                "references",
                "--selector",
                selector,
            ])
            .output()
            .expect("run public references"),
    );

    assert_protocol_shape(&value, "relation.references", "references");
    let references = value["result"]["references"]
        .as_array()
        .expect("reference records");
    assert_eq!(
        references[0]["containingSymbol"]["type"], "known",
        "{value:#}"
    );
    assert_eq!(
        references[0]["containingSymbol"]["symbol"]["declarationFile"], "app/A.kt",
        "{value:#}",
    );
    assert_eq!(
        references[1]["containingSymbol"]["type"], "top-level",
        "{value:#}"
    );
    assert_eq!(
        references[2]["containingSymbol"]["type"], "unavailable",
        "{value:#}"
    );
    assert_eq!(
        references[2]["containingSymbol"]["reason"], "identity-unavailable",
        "{value:#}",
    );
    let requests = backend.join().expect("references backend");
    assert_eq!(
        requests.last().expect("references request")["method"],
        "symbol/references"
    );
}

#[test]
fn review_comment_regression_graph_continuation_cardinality_is_not_user_controlled() {
    let fixture = tempfile::tempdir().expect("temporary graph continuation fixture");
    let home = fixture.path().join("home");
    let config = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_paged_public_graph(&workspace);
    let first = published_public_kast(&home, &config, &workspace)
        .current_dir(&workspace)
        .args(["--output", "json", "graph", "nodes"])
        .output()
        .expect("first graph page");
    assert!(first.status.success(), "{first:?}");
    let first: serde_json::Value =
        serde_json::from_slice(&first.stdout).expect("first graph page JSON");
    let continuation = first["result"]["page"]["continuation"]
        .as_str()
        .expect("graph continuation");
    let mut fields = continuation
        .split('.')
        .map(str::to_string)
        .collect::<Vec<_>>();
    assert_eq!(fields.len(), 4, "{continuation}");
    assert_eq!(fields[0], "kgn3", "{continuation}");
    fields[3] = u64::MAX.to_string();
    let caller_chosen_boundary = fields.join(".");

    let output = published_public_kast(&home, &config, &workspace)
        .current_dir(&workspace)
        .args([
            "--output",
            "json",
            "graph",
            "nodes",
            "--continuation",
            &caller_chosen_boundary,
        ])
        .output()
        .expect("caller-chosen graph boundary");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let value: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("continued graph page JSON");
    assert_eq!(value["result"]["page"]["returned"], 0, "{value:#}");
    assert_eq!(
        value["result"]["page"]["cardinality"]["type"], "exact",
        "{value:#}"
    );
    assert_eq!(
        value["result"]["page"]["cardinality"]["count"], 501,
        "{value:#}"
    );
}
