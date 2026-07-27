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
                    "evidence": complete_relationship_evidence(5),
                    "returnedCount": 4,
                    "visitedCandidateCount": 5,
                    "truncated": true,
                    "nextHandle": handle
                },
                "schemaVersion": 5
            }),
        )],
    );
    let first_json = run_agent_json(
        &home,
        &config,
        [
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
        ],
    );
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
                    "evidence": complete_relationship_evidence(5),
                    "returnedCount": 1,
                    "visitedCandidateCount": 1,
                    "truncated": false
                },
                "schemaVersion": 5
            }),
        )],
    );
    let second_json = run_agent_json(
        &home,
        &config,
        [
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
        ],
    );
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
                "reason": "CALL_HIERARCHY_UNAVAILABLE",
                "evidence": excluded_source_set_evidence(0)
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
                "reason": "TYPE_HIERARCHY_UNAVAILABLE",
                "evidence": excluded_source_set_evidence(0)
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
        let stdout = run_agent_json(&home, &config, args);
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
