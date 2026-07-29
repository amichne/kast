#[test]
fn exact_zero_relationships_require_complete_coverage_proof() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.proofless-exact-zero";
    let function = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);
    let interface = relation_identity("sample.Service", "INTERFACE", &declaration_file, 10);
    let cases = vec![
        (
            "references",
            "symbol/references",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": function,
                "references": [],
                "cardinality": {"type": "EXACT", "totalCount": 0},
                "schemaVersion": 5
            }),
        ),
        (
            "callers",
            "symbol/callers",
            Vec::<&str>::new(),
            serde_json::json!({
                "type": "AVAILABLE",
                "subject": function,
                "records": [],
                "page": proofless_exact_relation_page(0),
                "schemaVersion": 5
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
                "page": proofless_exact_relation_page(0),
                "schemaVersion": 5
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
                "page": proofless_exact_relation_page(0),
                "schemaVersion": 5
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
                "page": proofless_exact_relation_page(0),
                "schemaVersion": 5
            }),
        ),
    ];

    for (index, (command_name, method, extra_args, response)) in cases.into_iter().enumerate() {
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &temp.path().join(format!("proofless-zero-{index}.sock")),
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

        let output = command.output().expect("proofless relationship");
        assert_eq!(
            output.status.code(),
            Some(1),
            "command={command_name} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("invalid relationship json");
        assert_eq!(
            stdout["error"]["code"], "AGENT_RESULT_INVALID",
            "command={command_name} output={stdout}",
        );
        backend.join().expect("scripted backend");
    }
}

#[test]
fn relationship_evidence_variants_reject_inconsistent_coverage_facts() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.inconsistent-relationship-evidence";
    let subject = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);
    let cases = [
        serde_json::json!({
            "type": "COMPLETE",
            "cardinality": {"type": "EXACT", "totalCount": 0},
            "coverage": {
                "type": "COMPLETE",
                "identity": "COMPLETE",
                "projectScope": "COMPLETE",
                "sourceSetScope": "EXCLUDED",
                "indexFreshness": "COMPLETE",
                "backend": "COMPLETE",
                "requestedFamily": "COMPLETE",
                "limitations": []
            }
        }),
        serde_json::json!({
            "type": "RESUMABLE",
            "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 1},
            "coverage": {
                "type": "RESUMABLE",
                "identity": "COMPLETE",
                "projectScope": "COMPLETE",
                "sourceSetScope": "COMPLETE",
                "indexFreshness": "COMPLETE",
                "backend": "COMPLETE",
                "requestedFamily": "IN_PROGRESS",
                "limitations": []
            }
        }),
        serde_json::json!({
            "type": "LIMITED",
            "cardinality": {"type": "KNOWN_MINIMUM", "knownMinimumCount": 0},
            "coverage": {
                "type": "LIMITED",
                "identity": "COMPLETE",
                "projectScope": "COMPLETE",
                "sourceSetScope": "COMPLETE",
                "indexFreshness": "COMPLETE",
                "backend": "COMPLETE",
                "requestedFamily": "PARTIAL",
                "limitations": []
            }
        }),
    ];

    for (index, evidence) in cases.into_iter().enumerate() {
        let backend = spawn_scripted_idea_backend(
            &home,
            &config,
            &workspace,
            &temp.path().join(format!("invalid-evidence-{index}.sock")),
            vec![(
                "symbol/references",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "subject": subject,
                    "references": [],
                    "evidence": evidence,
                    "schemaVersion": 5
                }),
            )],
        );
        let output = kast(&home, &config)
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
            .expect("inconsistent relationship evidence");

        assert_eq!(output.status.code(), Some(1));
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("invalid evidence output");
        assert_eq!(stdout["error"]["code"], "AGENT_RESULT_INVALID");
        backend.join().expect("scripted backend");
    }
}

#[test]
fn genuine_exact_zero_preserves_complete_coverage_in_compact_and_count_views() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.complete-exact-zero";
    let response = || {
        serde_json::json!({
            "type": "AVAILABLE",
            "subject": relation_identity(
                "sample.Service.run",
                "FUNCTION",
                &declaration_file,
                42,
            ),
            "references": [],
            "evidence": complete_relationship_evidence(0),
            "schemaVersion": 5
        })
    };

    let compact_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("complete-zero-compact.sock"),
        vec![("symbol/references", response())],
    );
    let compact = kast(&home, &config)
        .args([
            "agent",
            "references",
            "--selector-handle",
            selector_handle,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("compact complete zero");
    assert!(
        compact.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&compact.stdout),
        String::from_utf8_lossy(&compact.stderr),
    );
    let compact_stdout = String::from_utf8_lossy(&compact.stdout);
    assert!(compact_stdout.contains("coverage"), "{compact_stdout}");
    assert!(compact_stdout.contains("COMPLETE"), "{compact_stdout}");
    assert!(compact_stdout.contains("limitations"), "{compact_stdout}");
    compact_backend.join().expect("compact backend");

    let count_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("complete-zero-count.sock"),
        vec![("symbol/references", response())],
    );
    let count = run_agent_json(
        &home,
        &config,
        [
            "references",
            "--selector-handle",
            selector_handle,
            "--count",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(count["result"]["page"]["cardinality"]["type"], "EXACT");
    assert_eq!(count["result"]["page"]["cardinality"]["totalCount"], 0);
    assert_eq!(count["result"]["coverage"]["type"], "COMPLETE");
    assert_eq!(count["result"]["limitations"], serde_json::json!([]));
    count_backend.join().expect("count backend");

    let selected_backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("complete-zero-selected.sock"),
        vec![("symbol/references", response())],
    );
    let selected = run_agent_json(
        &home,
        &config,
        [
            "references",
            "--selector-handle",
            selector_handle,
            "--fields",
            "subject",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert!(selected["result"].get("page").is_none());
    assert_eq!(selected["result"]["coverage"]["type"], "COMPLETE");
    assert_eq!(selected["result"]["limitations"], serde_json::json!([]));
    selected_backend.join().expect("selected backend");
}

#[test]
fn handle_backed_degraded_relationship_preserves_known_minimum_and_limitations() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector_handle = "ksh1.degraded-relationship";
    let selector = serde_json::json!({
        "fqName": "sample.Service.run",
        "declarationFile": declaration_file,
        "declarationStartOffset": 42,
        "kind": "FUNCTION"
    });
    let subject = relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);
    let backend = spawn_scripted_idea_backend(
        &home,
        &config,
        &workspace,
        &temp.path().join("degraded-evidence.sock"),
        vec![(
            "symbol/callers",
            serde_json::json!({
                "type": "DEGRADED",
                "selector": selector,
                "subject": subject,
                "reason": "CALL_HIERARCHY_UNAVAILABLE",
                "evidence": excluded_source_set_evidence(3),
                "schemaVersion": 5
            }),
        )],
    );

    let stdout = run_agent_json(
        &home,
        &config,
        [
            "callers",
            "--selector-handle",
            selector_handle,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ],
    );
    assert_eq!(stdout["result"]["outcome"], "DEGRADED");
    assert_eq!(
        stdout["result"]["cardinality"],
        serde_json::json!({"type": "KNOWN_MINIMUM", "knownMinimumCount": 3})
    );
    assert_eq!(stdout["result"]["coverage"]["type"], "LIMITED");
    assert_eq!(stdout["result"]["records"], serde_json::json!([]));
    assert_eq!(
        stdout["result"]["limitations"],
        serde_json::json!(["SOURCE_SET_EXCLUDED", "FAMILY_SEARCH_INCOMPLETE"])
    );
    backend.join().expect("degraded backend");
}
