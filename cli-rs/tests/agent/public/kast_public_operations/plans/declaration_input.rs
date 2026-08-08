#[test]
fn change_add_declaration_normalizes_one_terminal_line_ending() {
    let declaration = "class Added";
    for (case, input) in [
        ("no-final-newline", declaration),
        ("lf", "class Added\n"),
        ("crlf", "class Added\r\n"),
    ] {
        let fixture = tempfile::tempdir().expect("fixture");
        let home = fixture.path().join("home");
        let config_home = fixture.path().join("config");
        let workspace = fixture.path().join("workspace");
        let source_root = workspace.join("src/main/kotlin");
        std::fs::create_dir_all(&source_root).expect("source root");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
        let target = source_root.join("Existing.kt");
        let preimage = b"class Existing\n";
        std::fs::write(&target, preimage).expect("existing source");
        let workspace = workspace.canonicalize().expect("canonical workspace");
        let target = target.canonicalize().expect("canonical target");
        let preview =
            public_exact_add_declaration_preview(&workspace, &target, preimage, declaration);
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &fixture.path().join(format!("{case}.sock")),
            vec![("raw/plan-add-declaration", preview)],
        );
        let binary = write_active_kast_for_test(&home, &config_home);
        let mut change = installed_public_kast(&binary, &home, &config_home, &workspace);
        change.args([
            "change",
            "plan",
            "add-declaration",
            "--file",
            target.to_str().expect("target"),
        ]);
        let change = run_with_stdin(change, input);
        let requests = backend
            .join()
            .unwrap_or_else(|_| panic!("{case} planner backend"));
        assert!(change.status.success(), "{case}: {change:?}");
        let request = requests
            .iter()
            .find(|request| request["method"] == "raw/plan-add-declaration")
            .unwrap_or_else(|| panic!("{case}: add-declaration request in {requests:#?}"));
        assert_eq!(
            request["params"]["proposedDeclaration"], declaration,
            "{case}: planner receives normalized declaration text"
        );
        let public = decode(&change);
        assert_eq!(
            public["plan"]["preview"]["proposedDeclaration"], declaration,
            "{case}: public preview"
        );
        let plan_id = public["planId"].as_str().expect("plan id");
        let plans = home.join(".local/share/kast/state/agent-plans");
        assert_eq!(
            std::fs::read(plans.join(format!("{plan_id}.content")))
                .expect("stored declaration content"),
            declaration.as_bytes(),
            "{case}: persisted content"
        );
        let stored: Value = serde_json::from_slice(
            &std::fs::read(plans.join(format!("{plan_id}.json"))).expect("stored plan"),
        )
        .expect("stored plan JSON");
        assert_eq!(
            stored["contentSha256"],
            source_sha256(declaration.as_bytes()),
            "{case}: persisted digest"
        );
    }
}

#[test]
fn change_add_declaration_retains_typed_rejection_for_invalid_content() {
    for (case, input) in [
        ("blank-only", " \r\n"),
        ("multiple-declarations", "class First\nclass Second\n"),
    ] {
        let fixture = tempfile::tempdir().expect("fixture");
        let home = fixture.path().join("home");
        let config_home = fixture.path().join("config");
        let workspace = fixture.path().join("workspace");
        let source_root = workspace.join("src/main/kotlin");
        std::fs::create_dir_all(&source_root).expect("source root");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
        let target = source_root.join("Existing.kt");
        std::fs::write(&target, "class Existing\n").expect("existing source");
        let workspace = workspace.canonicalize().expect("canonical workspace");
        let target = target.canonicalize().expect("canonical target");
        let backend = spawn_scripted_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &fixture.path().join(format!("invalid-{case}.sock")),
            vec![(
                "raw/plan-add-declaration",
                scripted_json_rpc_error(
                    "INVALID_ADDITION_CONTENT",
                    "Add-declaration requires exactly one valid declaration.",
                    json!({"case": case}),
                    false,
                ),
            )],
        );
        let binary = write_active_kast_for_test(&home, &config_home);
        let mut change = installed_public_kast(&binary, &home, &config_home, &workspace);
        change.args([
            "change",
            "plan",
            "add-declaration",
            "--file",
            target.to_str().expect("target"),
        ]);
        let change = run_with_stdin(change, input);
        backend
            .join()
            .unwrap_or_else(|_| panic!("{case} planner backend"));
        assert_eq!(change.status.code(), Some(1), "{case}: {change:?}");
        let envelope = decode_envelope(&change);
        assert_eq!(envelope["result"]["type"], "rejected", "{case}");
        assert_eq!(
            envelope["result"]["failure"]["code"], "INVALID_ADDITION_CONTENT",
            "{case}"
        );
    }
}
