use super::*;

#[test]
fn public_apply_and_recover_share_one_exclusive_verified_add_file_plan_lock() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let entered = fixture.path().join("exclusive-plan-lock.entered");
    let release = fixture.path().join("exclusive-plan-lock.release");
    let backend = spawn_gated_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("exclusive-plan-lock.sock"),
        &target,
        content,
        &entered,
        &release,
        successful_verified_add_file_script(&target, content),
    );

    let first = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("first apply");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(entered.is_file(), "first apply reached the canonical RPC");

    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let lock_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.lock"));
    assert_eq!(
        std::fs::metadata(&lock_path)
            .expect("durable plan lock")
            .permissions()
            .mode()
            & 0o777,
        0o600,
    );
    let plan_before = std::fs::read(&plan_path).expect("awaiting-approval plan");
    let concurrent = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("concurrent recover");
    assert_eq!(concurrent.status.code(), Some(1), "{concurrent:?}");
    assert_eq!(decode(&concurrent)["error"], "KAST_PLAN_BUSY");
    assert_eq!(
        std::fs::read(&plan_path).expect("unchanged plan"),
        plan_before
    );

    std::fs::write(&release, "release\n").expect("release canonical RPC gate");
    let first = first.wait_with_output().expect("first apply output");
    assert!(first.status.success(), "{first:?}");
    let receipt = decode(&first);
    assert_eq!(receipt["outcome"], "VERIFIED");
    let requests = backend.join().expect("gated backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
    let stored: Value = serde_json::from_slice(&std::fs::read(&plan_path).expect("terminal plan"))
        .expect("terminal plan JSON");
    assert_eq!(stored["state"]["state"], "TERMINAL");

    let invalid_recover = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("verified terminal is not recovery authority");
    assert_eq!(
        invalid_recover.status.code(),
        Some(1),
        "{invalid_recover:?}"
    );
    assert_eq!(
        decode(&invalid_recover)["error"],
        "KAST_VERIFIED_ADD_FILE_RECOVERY_INVALID",
    );
    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("exclusive-plan-lock-replay.sock"),
        vec![("change/apply-add-file", receipt.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("terminal apply replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt);
    replay_backend.join().expect("terminal replay backend");
}

#[test]
fn public_apply_routes_exact_source_drift_to_closed_server_revalidation() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Ordering.kt");
    let content = b"package sample\nclass Planned\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Ordering.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let foreign = b"package sample\nclass Foreign\n";
    std::fs::write(&target, foreign).expect("drifted target");
    let rejection =
        verified_add_file_rejected(&target, content, "REVALIDATION", "PLAN_REVALIDATION_FAILED");
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("ordering-apply.sock"),
        vec![("change/apply-add-file", rejection.clone())],
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("drifted apply");
    assert_eq!(apply.status.code(), Some(1), "{apply:?}");
    assert_eq!(decode(&apply), rejection);
    assert_eq!(
        std::fs::read(&target).expect("foreign source retained"),
        foreign
    );
    assert_eq!(
        backend
            .join()
            .expect("ordering backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}

#[test]
fn malformed_persisted_recovery_state_fails_closed_without_server_or_write() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/TamperedRecovery.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/TamperedRecovery.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let mut stored: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("stored plan"))
            .expect("stored plan JSON");
    stored["state"] = json!({
        "state": "RECOVERY_REQUIRED",
        "result": verified_add_file_receipt(&target, content),
    });
    std::fs::write(
        &plan_path,
        serde_json::to_vec(&stored).expect("tampered recovery JSON"),
    )
    .expect("tamper recovery state");

    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("tampered recovery");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered)["error"], "KAST_PLAN_INVALID");
    assert!(!target.exists());
}
