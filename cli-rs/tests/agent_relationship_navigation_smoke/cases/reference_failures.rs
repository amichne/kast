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
                "reason": "REFERENCES_UNAVAILABLE",
                "evidence": excluded_source_set_evidence(0)
            }),
        ),
        (
            "CURSOR_STALE",
            serde_json::json!({
                "type": "CURSOR_STALE",
                "selector": selector,
                "reason": "GENERATION_CHANGED",
                "evidence": excluded_source_set_evidence(0)
            }),
        ),
        (
            "CURSOR_INVALID",
            serde_json::json!({
                "type": "CURSOR_INVALID",
                "selector": selector,
                "reason": "UNKNOWN_HANDLE",
                "evidence": excluded_source_set_evidence(0)
            }),
        ),
    ];

    for (index, (expected_outcome, response)) in cases.into_iter().enumerate() {
        let socket = temp.path().join(format!("indexer-{index}.sock"));
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &socket,
            vec![("symbol/references", response)],
        );
        let stdout = run_agent_json(
            &home,
            &config,
            [
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
            ],
        );
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
    let socket = temp.path().join("indexer.sock");
    let backend = spawn_scripted_indexer_backend(
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
fn explicit_references_fail_closed_on_response_provenance_mismatch() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&declaration_file, "package sample\nclass Service\n").expect("source");
    let requested_selector = serde_json::json!({
        "fqName": "sample.Service",
        "declarationFile": declaration_file,
        "declarationStartOffset": 15,
        "kind": "CLASS"
    });
    let other_selector = serde_json::json!({
        "fqName": "sample.Other",
        "declarationFile": declaration_file,
        "declarationStartOffset": 15,
        "kind": "CLASS"
    });
    let other_subject = serde_json::json!({
        "fqName": "sample.Other",
        "kind": "CLASS",
        "declarationFile": declaration_file,
        "declarationStartOffset": 15
    });
    let cases = [
        serde_json::json!({
            "type": "AVAILABLE",
            "subject": other_subject,
            "references": [],
            "evidence": complete_relationship_evidence(0),
            "schemaVersion": api_schema_version()
        }),
        serde_json::json!({
            "type": "DEGRADED",
            "selector": requested_selector,
            "subject": other_subject,
            "reason": "REFERENCES_UNAVAILABLE",
            "evidence": excluded_source_set_evidence(0),
            "schemaVersion": api_schema_version()
        }),
        serde_json::json!({
            "type": "CURSOR_STALE",
            "selector": other_selector,
            "reason": "GENERATION_CHANGED",
            "evidence": excluded_source_set_evidence(0),
            "schemaVersion": api_schema_version()
        }),
        serde_json::json!({
            "type": "SELECTOR_HANDLE_REJECTED",
            "reason": "STALE",
            "recovery": "RESOLVE_AGAIN",
            "schemaVersion": api_schema_version()
        }),
    ];

    for (index, response) in cases.into_iter().enumerate() {
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &temp
                .path()
                .join(format!("reference-provenance-{index}.sock")),
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
            .expect("mismatched reference provenance");

        assert_eq!(
            output.status.code(),
            Some(1),
            "case={index} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let result: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("provenance failure JSON");
        assert_eq!(result["error"]["code"], "AGENT_RESULT_INVALID");
        backend.join().expect("provenance backend");
    }
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
        let socket = temp.path().join(format!("indexer-malformed-{index}.sock"));
        let backend = spawn_scripted_indexer_backend(
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
