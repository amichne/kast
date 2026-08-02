#[test]
fn degraded_relationships_preserve_qualified_positive_records() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let function = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);
    let interface = relation_identity("sample.Service", "INTERFACE", &declaration_file, 10);
    let function_selector = serde_json::json!({
        "fqName": "sample.Service.run",
        "declarationFile": declaration_file,
        "declarationStartOffset": 42,
        "kind": "FUNCTION"
    });
    let interface_selector = serde_json::json!({
        "fqName": "sample.Service",
        "declarationFile": declaration_file,
        "declarationStartOffset": 10,
        "kind": "INTERFACE"
    });
    let implementation_file = workspace.join("RealService.kt");
    let cases = vec![
        (
            "references",
            "symbol/references",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "DEGRADED",
                "selector": function_selector,
                "subject": function,
                "reason": "REFERENCES_UNAVAILABLE",
                "references": [{
                    "location": relation_location(&workspace.join("Client.kt"), 20),
                    "containingSymbol": {"type": "TOP_LEVEL"}
                }],
                "evidence": excluded_source_set_evidence(1),
                "schemaVersion": 5
            }),
            "REFERENCE",
        ),
        (
            "callers",
            "symbol/callers",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "DEGRADED",
                "selector": function_selector,
                "subject": function,
                "reason": "CALL_HIERARCHY_UNAVAILABLE",
                "records": [call_relation_record("CALLER", 1, &workspace)],
                "evidence": excluded_source_set_evidence(1),
                "schemaVersion": 5
            }),
            "CALLER",
        ),
        (
            "implementations",
            "symbol/implementations",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "DEGRADED",
                "selector": interface_selector,
                "subject": interface,
                "reason": "IMPLEMENTATIONS_UNAVAILABLE",
                "records": [{
                    "relation": "IMPLEMENTATION",
                    "implementation": relation_identity(
                        "sample.RealService",
                        "CLASS",
                        &implementation_file,
                        10,
                    ),
                    "declarationLocation": relation_location(&implementation_file, 10)
                }],
                "evidence": excluded_source_set_evidence(1),
                "schemaVersion": 5
            }),
            "IMPLEMENTATION",
        ),
        (
            "hierarchy",
            "symbol/hierarchy",
            vec!["--direction", "subtypes"],
            serde_json::json!({
                "type": "DEGRADED",
                "selector": interface_selector,
                "subject": interface,
                "reason": "TYPE_HIERARCHY_UNAVAILABLE",
                "records": [{
                    "relation": "SUBTYPE",
                    "relatedSymbol": relation_identity(
                        "sample.RealService",
                        "CLASS",
                        &implementation_file,
                        10,
                    ),
                    "declarationLocation": relation_location(&implementation_file, 10),
                    "depth": 1
                }],
                "evidence": excluded_source_set_evidence(1),
                "schemaVersion": 5
            }),
            "SUBTYPE",
        ),
    ];

    for (index, (command_name, method, extra_args, response, expected_relation)) in
        cases.into_iter().enumerate()
    {
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &temp.path().join(format!("qualified-positive-{index}.sock")),
            vec![(method, response)],
        );
        let mut command = kast(&home, &config);
        command.args([
            "--output",
            "json",
            "agent",
            command_name,
            "--selector-handle",
            "ksh1.qualified-positive",
        ]);
        command.args(extra_args);
        command.args(["--workspace-root", workspace.to_str().expect("workspace")]);

        let output = command.output().expect("qualified positive relationship");
        assert!(
            output.status.success(),
            "command={command_name} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("qualified positive JSON");
        assert_eq!(stdout["result"]["outcome"], "DEGRADED");
        assert_eq!(
            stdout["result"]["cardinality"],
            serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 1}),
        );
        assert_eq!(
            stdout["result"]["records"][0]["relation"],
            expected_relation
        );
        assert!(stdout["result"].get("page").is_none(), "{stdout}");
        backend.join().expect("qualified positive backend");
    }
}

#[test]
fn degraded_relationships_reject_records_beyond_the_known_minimum() {
    assert_invalid_degraded_records(false);
    assert_invalid_degraded_records(true);
}

#[test]
fn degraded_relationships_reject_page_proof() {
    assert_invalid_degraded_page(false);
    assert_invalid_degraded_page(true);
}

fn assert_invalid_degraded_records(references: bool) {
    assert_invalid_degraded_response(references, true, false);
}

fn assert_invalid_degraded_page(references: bool) {
    assert_invalid_degraded_response(references, false, true);
}

fn assert_invalid_degraded_response(references: bool, include_record: bool, include_page: bool) {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector = serde_json::json!({
        "fqName": "sample.Service.run",
        "declarationFile": declaration_file,
        "declarationStartOffset": 42,
        "kind": "FUNCTION"
    });
    let subject = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);
    let (command_name, method, response) = if references {
        let occurrences = if include_record {
            vec![serde_json::json!({
                "location": relation_location(&workspace.join("Client.kt"), 20),
                "containingSymbol": {"type": "TOP_LEVEL"}
            })]
        } else {
            Vec::new()
        };
        let page = include_page.then(|| serde_json::json!({"truncated": false}));
        (
            "references",
            "symbol/references",
            serde_json::json!({
                "type": "DEGRADED",
                "selector": selector,
                "subject": subject,
                "reason": "REFERENCES_UNAVAILABLE",
                "references": occurrences,
                "page": page,
                "evidence": excluded_source_set_evidence(0),
                "schemaVersion": 5
            }),
        )
    } else {
        let records = if include_record {
            vec![call_relation_record("CALLER", 1, &workspace)]
        } else {
            Vec::new()
        };
        let page = include_page.then(|| exact_relation_page(0));
        (
            "callers",
            "symbol/callers",
            serde_json::json!({
                "type": "DEGRADED",
                "selector": selector,
                "subject": subject,
                "reason": "CALL_HIERARCHY_UNAVAILABLE",
                "records": records,
                "page": page,
                "evidence": excluded_source_set_evidence(0),
                "schemaVersion": 5
            }),
        )
    };
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("invalid-qualified-positive.sock"),
        vec![(method, response)],
    );
    let output = kast(&home, &config)
        .args([
            "--output",
            "json",
            "agent",
            command_name,
            "--selector-handle",
            "ksh1.invalid-qualified-positive",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("invalid degraded relationship");

    assert_eq!(output.status.code(), Some(1));
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("invalid degraded output");
    assert_eq!(stdout["error"]["code"], "AGENT_RESULT_INVALID");
    backend.join().expect("invalid degraded backend");
}
