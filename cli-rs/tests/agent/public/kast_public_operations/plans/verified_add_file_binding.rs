#[test]
fn change_add_file_apply_uses_verified_operation_binding_without_raw_bypass() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin/demo");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/demo/Added.kt");
    let content = "package demo\n\nclass Added\n";
    let plan_id = verified_add_file_plan_id(&target, content.as_bytes());
    let postimage_sha256 = source_sha256(content.as_bytes());
    let plan_result = json!({
        "planId": plan_id,
        "planVersion": 0,
        "stage": "AWAITING_APPROVAL",
        "operation": "add-file",
        "preview": {
            "targetPath": target,
            "proposedContent": content,
            "generation": 7,
        },
        "schemaVersion": 7,
    });
    let verified_receipt = json!({
        "outcome": "VERIFIED",
        "planId": plan_id,
        "planVersion": 5,
        "operation": "add-file",
        "publication": {"generation": 8},
        "identity": {
            "targetPath": target,
            "packageName": "demo",
            "declarations": [{"name": "Added", "kind": "CLASS"}],
        },
        "postimageSha256": postimage_sha256,
        "schemaVersion": 7,
    });
    let backend = support::spawn_verified_add_file_binding_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("verified-add-file.sock"),
        plan_result,
        verified_receipt.clone(),
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let mut plan = installed_public_kast(&binary, &home, &config_home, &workspace);
    plan.args([
        "change",
        "plan",
        "add-file",
        "--file",
        target.to_str().expect("target"),
    ]);
    let plan = run_with_stdin(plan, content);
    assert!(
        plan.status.success(),
        "operation-specific plan failed: stdout={} stderr={}",
        String::from_utf8_lossy(&plan.stdout),
        String::from_utf8_lossy(&plan.stderr),
    );
    let public_plan = decode(&plan);
    assert_eq!(public_plan["planId"], plan_id);
    assert_eq!(public_plan["planVersion"], 0);

    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("verified add-file apply");
    assert!(applied.status.success(), "{applied:?}");
    assert_eq!(decode(&applied), verified_receipt);
    let requests = backend.join().expect("strict add-file backend");
    let methods = requests
        .iter()
        .filter_map(|request| request["method"].as_str())
        .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
        .collect::<Vec<_>>();
    assert_eq!(
        methods,
        ["change/plan-add-file", "change/apply-add-file"],
        "raw plan/apply/CAS/refresh/postcondition bypasses are forbidden",
    );
    let plan_request = requests
        .iter()
        .find(|request| request["method"] == "change/plan-add-file")
        .expect("exact plan request");
    assert_eq!(plan_request["params"]["workspaceRoot"], workspace.display().to_string());
    assert_eq!(plan_request["params"]["targetPath"], target.display().to_string());
    assert_eq!(plan_request["params"]["proposedContent"], content);
    let apply_request = requests
        .iter()
        .find(|request| request["method"] == "change/apply-add-file")
        .expect("exact apply request");
    let approval_sha256 = source_sha256(
        format!(
            "kast-public-cli\nworkspaceRoot={}\nplanId={plan_id}\nexpectedVersion=0\n",
            workspace.display(),
        )
        .as_bytes(),
    );
    assert_eq!(apply_request["params"]["workspaceRoot"], workspace.display().to_string());
    assert_eq!(apply_request["params"]["planId"], plan_id);
    assert_eq!(apply_request["params"]["expectedVersion"], 0);
    assert_eq!(
        apply_request["params"]["approvalEvidence"]["approvedBy"],
        "kast-public-cli",
    );
    assert_eq!(
        apply_request["params"]["approvalEvidence"]["evidenceSha256"],
        approval_sha256,
    );

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("verified-add-file-replay.sock"),
        vec![("change/apply-add-file", verified_receipt.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("terminal verified add-file replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), verified_receipt);
    let replay_requests = replay_backend.join().expect("verified replay backend");
    assert_eq!(
        replay_requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
        "verified replay must reacquire server proof",
    );
}

#[test]
fn verified_add_file_vcs_prompt_rejection_is_exact_through_the_installed_route() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/VcsRejected.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/VcsRejected.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let rejection = verified_add_file_rejected(
        &target,
        content,
        "SOURCE_APPLICATION",
        "VCS_WRITE_PROMPT_REJECTED",
    );
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("vcs-rejected.sock"),
        vec![("change/apply-add-file", rejection.clone())],
    );

    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("VCS-rejected apply");
    assert_eq!(applied.status.code(), Some(1), "{applied:?}");
    assert_eq!(decode(&applied), rejection);
    assert!(!target.exists(), "VCS rejection retains the absent pre-state");
    let requests = backend.join().expect("VCS-rejected backend");
    assert_eq!(
        requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
            .collect::<Vec<_>>(),
        ["change/apply-add-file"],
    );
}

#[test]
fn verified_add_file_publication_failure_retains_recovery_until_terminal_rollback() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/PublicationFailure.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/PublicationFailure.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let recovery = verified_add_file_recovery_required(&target, content, "WORKSPACE_PUBLICATION");
    let rolled_back = verified_add_file_rolled_back(
        &target,
        content,
        "WORKSPACE_PUBLICATION",
        "PUBLICATION_FAILED",
    );
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("publication-recovery.sock"),
        vec![
            ("change/apply-add-file", recovery.clone()),
            ("change/apply-add-file", rolled_back.clone()),
        ],
    );

    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("publication-failed apply");
    assert_eq!(applied.status.code(), Some(1), "{applied:?}");
    assert_eq!(decode(&applied), recovery);

    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("publication recovery");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered), rolled_back);
    let requests = backend.join().expect("publication recovery backend");
    assert_eq!(
        requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
            .collect::<Vec<_>>(),
        ["change/apply-add-file", "change/apply-add-file"],
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal rollback replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), rolled_back);
}

#[test]
fn verified_add_file_tampered_and_stale_authority_fail_closed() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Stale.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Stale.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let original = std::fs::read(&plan_path).expect("persisted plan");
    let mut tampered: Value = serde_json::from_slice(&original).expect("stored plan JSON");
    tampered["planVersion"] = json!(1);
    std::fs::write(&plan_path, serde_json::to_vec(&tampered).expect("tampered plan JSON"))
        .expect("tamper plan");
    let rejected = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("tampered plan apply");
    assert_eq!(rejected.status.code(), Some(1), "{rejected:?}");
    assert_eq!(decode(&rejected)["error"], "KAST_PLAN_INVALID");
    std::fs::write(&plan_path, original).expect("restore plan");

    let mut stale = verified_add_file_receipt(&target, content);
    stale["planVersion"] = json!(4);
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("stale-result.sock"),
        vec![("change/apply-add-file", stale)],
    );
    let rejected = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("stale result apply");
    assert_eq!(rejected.status.code(), Some(1), "{rejected:?}");
    assert_eq!(
        decode(&rejected)["error"],
        "KAST_VERIFIED_ADD_FILE_RESULT_INVALID",
    );
    assert!(!target.exists());
    assert_eq!(
        backend
            .join()
            .expect("stale result backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}
