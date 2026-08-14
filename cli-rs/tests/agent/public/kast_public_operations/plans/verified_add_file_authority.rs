#[test]
fn verified_add_file_plan_stage_and_persisted_request_tampering_fail_closed() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Tamper.kt");
    let content = b"package sample\nclass Added\n";
    let plan_id = verified_add_file_plan_id(&target, content);
    let invalid_stage = json!({
        "planId": plan_id,
        "planVersion": 0,
        "stage": "APPROVED",
        "operation": "add-file",
        "preview": {
            "targetPath": target,
            "proposedContent": std::str::from_utf8(content).expect("Kotlin content"),
            "generation": 7,
        },
        "schemaVersion": 7,
    });
    let stage_backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("invalid-stage.sock"),
        vec![("change/plan-add-file", invalid_stage)],
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let mut plan = installed_public_kast(&binary, &home, &config_home, &workspace);
    plan.args(["change", "plan", "add-file", "--file", "src/main/kotlin/Tamper.kt"]);
    let invalid_plan = run_with_stdin(
        plan,
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    assert_eq!(invalid_plan.status.code(), Some(1), "{invalid_plan:?}");
    assert_eq!(
        decode(&invalid_plan)["error"],
        "KAST_VERIFIED_ADD_FILE_PLAN_INVALID",
    );
    stage_backend.join().expect("invalid stage backend");

    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Tamper.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let original = std::fs::read(&plan_path).expect("persisted plan");
    let original_json: Value = serde_json::from_slice(&original).expect("plan JSON");
    let mutations = [
        (
            "targetPath",
            json!(workspace.join("src/main/kotlin/Other.kt")),
        ),
        (
            "proposedContent",
            json!("package sample\nclass Different\n"),
        ),
        ("postimageSha256", json!("0".repeat(64))),
    ];
    for (field, value) in mutations {
        let mut tampered = original_json.clone();
        tampered[field] = value;
        std::fs::write(
            &plan_path,
            serde_json::to_vec(&tampered).expect("tampered plan JSON"),
        )
        .expect("write tampered plan");
        let rejected = installed_public_kast(&binary, &home, &config_home, &workspace)
            .args(["change", "apply", "--plan-id", &plan_id])
            .output()
            .expect("tampered apply");
        assert_eq!(rejected.status.code(), Some(1), "{field}: {rejected:?}");
        assert_eq!(decode(&rejected)["error"], "KAST_PLAN_INVALID", "{field}");
    }
    std::fs::write(&plan_path, original).expect("restore plan");
}

#[test]
fn verified_add_file_terminal_hash_and_result_matrix_tampering_fail_closed() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/ResultTamper.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/ResultTamper.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let mut terminal = verified_add_file_receipt(&target, content);
    terminal["postimageSha256"] = json!("f".repeat(64));
    let mut matrix = verified_add_file_rejected(
        &target,
        content,
        "SOURCE_APPLICATION",
        "VCS_WRITE_PROMPT_REJECTED",
    );
    matrix["stage"] = json!("AWAITING_APPROVAL");
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("invalid-results.sock"),
        vec![
            ("change/apply-add-file", terminal),
            ("change/apply-add-file", matrix),
        ],
    );
    for (index, case) in ["terminal hash", "closed result matrix"].into_iter().enumerate() {
        let mut command = installed_public_kast(&binary, &home, &config_home, &workspace);
        if index == 0 {
            command.args(["change", "apply", "--plan-id", &plan_id]);
        } else {
            command.args(["change", "recover", "--recovery-id", &plan_id]);
        }
        let rejected = command.output().expect("tampered result transition");
        assert_eq!(rejected.status.code(), Some(1), "{case}: {rejected:?}");
        assert_eq!(
            decode(&rejected)["error"],
            "KAST_VERIFIED_ADD_FILE_RESULT_INVALID",
            "{case}",
        );
    }
    backend.join().expect("invalid result backend");
    assert!(!target.exists());
}

#[test]
fn verified_terminal_replay_reacquires_server_proof_instead_of_trusting_local_receipt_fields() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/ServerProvenance.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/ServerProvenance.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let expected = verified_add_file_receipt(&target, content);
    let first_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("server-provenance-first.sock"),
        vec![("change/apply-add-file", expected.clone())],
    );
    let first = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("first verified apply");
    assert!(first.status.success(), "{first:?}");
    first_backend.join().expect("first verified backend");

    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let mut stored: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("terminal plan"))
            .expect("terminal plan JSON");
    stored["state"]["result"]["publication"]["generation"] = json!(999);
    stored["state"]["result"]["identity"]["packageName"] = json!("forged");
    stored["state"]["result"]["identity"]["declarations"] =
        json!([{"name": "Forged", "kind": "CLASS"}]);
    std::fs::write(
        &plan_path,
        serde_json::to_vec(&stored).expect("forged terminal JSON"),
    )
    .expect("forge local terminal receipt");

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("server-provenance-replay.sock"),
        vec![("change/apply-add-file", expected.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("server-proven terminal replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), expected);
    let requests = replay_backend.join().expect("server-proven replay backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}

#[test]
fn verified_add_file_recover_rejects_awaiting_and_rejected_states_without_rpc() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/RecoveryGate.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/RecoveryGate.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let awaiting = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("awaiting recovery");
    assert_eq!(awaiting.status.code(), Some(1), "{awaiting:?}");
    assert_eq!(
        decode(&awaiting)["error"],
        "KAST_VERIFIED_ADD_FILE_RECOVERY_INVALID",
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
        &fixture.path().join("rejected-state.sock"),
        vec![("change/apply-add-file", rejection)],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("rejected apply");
    assert_eq!(applied.status.code(), Some(1), "{applied:?}");
    backend.join().expect("rejected state backend");
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("rejected recovery");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(
        decode(&recovered)["error"],
        "KAST_VERIFIED_ADD_FILE_RECOVERY_INVALID",
    );
}

#[test]
fn verified_add_file_recover_rejects_tampered_in_flight_approval_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/InFlight.kt",
        "package sample\nclass Added\n",
    );
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let mut stored: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("stored plan"))
            .expect("stored plan JSON");
    stored["state"] = json!({
        "state": "APPLY_OUTCOME_UNKNOWN",
        "authority": {
            "recoveryId": plan_id,
            "expectedVersion": 0,
            "approvalEvidenceSha256": "0".repeat(64),
        },
    });
    std::fs::write(
        &plan_path,
        serde_json::to_vec(&stored).expect("tampered in-flight plan"),
    )
    .expect("write tampered in-flight authority");
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("tampered in-flight recovery");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered)["error"], "KAST_PLAN_INVALID");
}
