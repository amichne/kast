use super::*;

include!("declaration_input.rs");

#[test]
fn change_add_declaration_persists_restart_safe_file_bottom_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Existing.kt");
    let preimage = b"\xef\xbb\xbfclass Existing\r\n";
    std::fs::write(&target, preimage).expect("existing source");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = target.canonicalize().expect("canonical target");
    let declaration = "class Added";
    let plan_id = "a".repeat(64);
    let preview = verified_add_declaration_plan_result(&plan_id, &target, declaration, 7);
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("plan-add-declaration.sock"),
        vec![("change/plan-add-declaration", preview.clone())],
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
    let change = run_with_stdin(change, declaration);
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    assert!(
        !String::from_utf8_lossy(&change.stdout).contains("contentBase64"),
        "public add-declaration plan must redact exact image bytes"
    );
    backend.join().expect("add-declaration planner backend");
    let public = decode(&change);
    assert_eq!(public["preview"]["proposedDeclaration"], declaration);
    let plan_id = public["planId"].as_str().expect("plan id");
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let stored: Value = serde_json::from_slice(
        &std::fs::read(&plan_path).expect("stored add-declaration authority"),
    )
    .expect("stored add-declaration JSON");
    assert_eq!(stored["planId"], plan_id);
    assert_eq!(stored["planVersion"], 0);
    assert_eq!(stored["targetPath"], target.to_string_lossy().as_ref());
    assert_eq!(stored["state"]["state"], "AWAITING_APPROVAL");

    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("add-declaration-apply.sock"),
        vec![(
            "change/apply-add-declaration",
            verified_add_declaration_receipt(plan_id, &target, 8),
        )],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", plan_id])
        .output()
        .expect("verified add-declaration apply");
    assert!(applied.status.success(), "{applied:?}");
    let verified_receipt = decode(&applied);
    assert_eq!(
        verified_receipt["outcome"], "VERIFIED",
        "{verified_receipt:#}"
    );
    let apply_requests = apply_backend.join().expect("add-declaration apply backend");
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-declaration")
            .count(),
        1
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", plan_id])
        .output()
        .expect("verified add-declaration replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), verified_receipt);

    std::fs::write(&target, preimage).expect("reset add-declaration preimage");
    let tamper_plan_id = "b".repeat(64);
    let tamper_plan_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("add-declaration-tamper-plan.sock"),
        vec![(
            "change/plan-add-declaration",
            verified_add_declaration_plan_result(&tamper_plan_id, &target, declaration, 7),
        )],
    );
    let mut tamper_change = installed_public_kast(&binary, &home, &config_home, &workspace);
    tamper_change.args([
        "change",
        "plan",
        "add-declaration",
        "--file",
        target.to_str().expect("target"),
    ]);
    let tamper_change = run_with_stdin(tamper_change, declaration);
    assert!(tamper_change.status.success(), "{tamper_change:?}");
    tamper_plan_backend
        .join()
        .expect("add-declaration tamper planner");
    let plan_id = decode(&tamper_change)["planId"]
        .as_str()
        .expect("tamper plan id")
        .to_string();
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let stored: Value = serde_json::from_slice(
        &std::fs::read(&plan_path).expect("stored tamper add-declaration authority"),
    )
    .expect("stored tamper add-declaration JSON");

    let mut tampered = stored;
    tampered["planId"] = json!("c".repeat(64));
    let mut encoded = serde_json::to_vec(&tampered).expect("tampered declaration plan");
    encoded.push(b'\n');
    std::fs::write(&plan_path, encoded).expect("write tampered declaration plan");
    let restarted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("restart with tampered declaration authority");
    assert_eq!(restarted.status.code(), Some(1), "{restarted:?}");
    assert_eq!(decode(&restarted)["error"], "KAST_PLAN_INVALID");
}

#[test]
fn change_persists_a_private_root_bound_plan() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"plan-test\"\n",
    )
    .expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let binary = write_active_kast_for_test(&home, &config_home);

    let change = change_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        "package sample\nclass Added\n",
    );
    assert!(
        change.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr)
    );
    assert!(
        !String::from_utf8_lossy(&change.stdout).contains("contentBase64"),
        "public plan must not expose private raw byte images"
    );
    let change = decode(&change);
    let plan_id = change["planId"].as_str().expect("plan id");
    assert!(plan_id.starts_with("af-"), "operation-specific plan id");
    assert_eq!(change["operation"], "add-file");
    assert_eq!(
        change["preview"]["proposedContent"],
        "package sample\nclass Added\n"
    );
    assert_eq!(change["planVersion"], 0);
    assert_eq!(change["stage"], "AWAITING_APPROVAL");

    let plans = home.join(".local/share/kast/state/agent-plans");
    let plan_path = plans.join(format!("{plan_id}.json"));
    assert!(plan_path.is_file(), "persisted plan");
    assert_eq!(
        std::fs::metadata(&plan_path)
            .expect("plan metadata")
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
    let stored: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("stored add-file authority"))
            .expect("stored add-file JSON");
    assert_eq!(stored["schemaVersion"], 1);
    assert_eq!(stored["planId"], plan_id);
    assert_eq!(stored["planVersion"], 0);
    assert_eq!(
        stored["targetPath"],
        workspace
            .join("src/main/kotlin/Added.kt")
            .to_string_lossy()
            .as_ref()
    );
    assert_eq!(stored["plannedGeneration"], 7);
    assert_eq!(stored["state"]["state"], "AWAITING_APPROVAL");

    let other = fixture.path().join("other");
    std::fs::create_dir_all(&other).expect("other root");
    std::fs::write(other.join("settings.gradle.kts"), "").expect("other settings");
    let wrong_root = installed_public_kast(&binary, &home, &config_home, &other)
        .args(["change", "apply", "--plan-id", plan_id])
        .output()
        .expect("wrong-root apply");
    assert_eq!(wrong_root.status.code(), Some(1), "{wrong_root:?}");
    assert_eq!(decode(&wrong_root)["error"], "KAST_PLAN_WORKSPACE_MISMATCH");
    assert!(plan_path.is_file(), "failed apply keeps plan");

    let mut tampered = stored;
    tampered["planId"] = json!(format!("af-{}", "0".repeat(64)));
    let mut encoded = serde_json::to_vec(&tampered).expect("tampered add-file plan");
    encoded.push(b'\n');
    std::fs::write(&plan_path, encoded).expect("write tampered add-file plan");
    let restarted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", plan_id])
        .output()
        .expect("restart with tampered add-file authority");
    assert_eq!(restarted.status.code(), Some(1), "{restarted:?}");
    assert_eq!(decode(&restarted)["error"], "KAST_PLAN_INVALID");
}

#[test]
fn refresh_keeps_relationship_failure_actionable_without_graph_extraction() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source = workspace.join("src/App.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source directory");
    std::fs::write(&source, "fun app() = missing\n").expect("source");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let _index = seed_empty_graph_scope(&workspace);
    let source = source.canonicalize().expect("canonical source");
    let failure_id = uuid::Uuid::new_v4().hyphenated().to_string();
    let socket = fixture.path().join("refresh.sock");
    let backend = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        2,
        vec![
            (
                "raw/workspace-refresh",
                complete_refresh(&source, &failure_id),
            ),
            ("raw/diagnostics", diagnostics_with_error(&source)),
        ],
    );

    let refresh = kast(&home, &config_home, &workspace)
        .args([
            "workspace",
            "refresh",
            "--file",
            source.to_str().expect("source"),
        ])
        .output()
        .expect("refresh");
    assert!(
        refresh.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&refresh.stdout),
        String::from_utf8_lossy(&refresh.stderr)
    );
    let refresh = decode(&refresh);
    assert_eq!(refresh["fileCount"], 1);
    assert_eq!(refresh["diagnostics"]["severityCounts"]["error"], 1);
    assert_eq!(refresh["diagnostics"]["cardinality"]["totalCount"], 1);
    assert_eq!(refresh["diagnostics"]["cardinality"]["returnedCount"], 1);
    assert_eq!(refresh["diagnostics"]["cardinality"]["truncated"], false);
    assert_eq!(
        refresh["diagnostics"]["diagnostics"][0]["severity"],
        "ERROR"
    );
    assert_eq!(refresh["graph"]["updated"], false);
    assert_eq!(
        refresh["externalizableFailures"],
        json!([{
            "path": "src/App.kt",
            "failureId": failure_id,
            "code": "PSI_UNAVAILABLE"
        }])
    );
    assert_eq!(
        refresh["next"],
        json!([format!(
            "kast workspace externalize --failure-id {failure_id}"
        )])
    );
    let requests = backend.join().expect("refresh backend");
    let semantic_requests = requests
        .iter()
        .filter(|request| {
            matches!(
                request["method"].as_str(),
                Some("raw/workspace-refresh" | "raw/diagnostics" | "raw/semantic-graph")
            )
        })
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_requests
            .iter()
            .map(|request| request["method"].as_str().expect("method"))
            .collect::<Vec<_>>(),
        ["raw/workspace-refresh", "raw/diagnostics"]
    );
    for request in semantic_requests {
        assert_eq!(request["params"]["filePaths"], json!([source]));
    }
}

include!("../../operations/refresh.rs");
include!("../../operations/focused_refresh.rs");
include!("verified_add_declaration_binding.rs");
include!("verified_add_file_binding.rs");
include!("verified_add_file_authority.rs");
