use super::*;

#[test]
fn public_recover_preserves_publication_failure_until_terminal_rollback() {
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
    let pending = verified_add_file_recovery_required(&target, content, "WORKSPACE_PUBLICATION");
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("publication-interruption.sock"),
        vec![("change/apply-add-file", pending.clone())],
    );
    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("publication-interrupted apply");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    assert_eq!(decode(&interrupted), pending);
    assert!(!target.exists(), "the public client owns no source writer");
    apply_backend.join().expect("publication apply backend");

    let rolled_back = verified_add_file_rolled_back(
        &target,
        content,
        "WORKSPACE_PUBLICATION",
        "PUBLICATION_FAILED",
    );
    let recover_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("publication-recover.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover publication failure");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered), rolled_back);
    assert_eq!(
        recover_backend
            .join()
            .expect("publication recovery backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("publication-replay.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal rollback replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), rolled_back);
    assert_eq!(
        replay_backend
            .join()
            .expect("publication replay backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}

#[test]
fn rolled_back_recovery_reacquires_native_authority_instead_of_replaying_local_claim() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/NativeRollback.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/NativeRollback.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let recovery = verified_add_file_recovery_required(&target, content, "WORKSPACE_PUBLICATION");
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("native-rollback-apply.sock"),
        vec![("change/apply-add-file", recovery.clone())],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("recovery-required apply");
    assert_eq!(applied.status.code(), Some(1), "{applied:?}");
    assert_eq!(decode(&applied), recovery);
    apply_backend.join().expect("recovery-required backend");

    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let forged =
        verified_add_file_rolled_back(&target, content, "PSI_ADMISSION", "PSI_NOT_ADMITTED");
    let mut stored: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("recovery plan"))
            .expect("recovery plan JSON");
    stored["state"] = json!({"state": "TERMINAL", "result": forged});
    std::fs::write(
        &plan_path,
        serde_json::to_vec(&stored).expect("forged rollback JSON"),
    )
    .expect("forge local rollback");

    let native = verified_add_file_rolled_back(
        &target,
        content,
        "WORKSPACE_PUBLICATION",
        "PUBLICATION_FAILED",
    );
    let recover_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("native-rollback-recover.sock"),
        vec![("change/apply-add-file", native.clone())],
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("native rollback reacquisition");
    let requests = recover_backend.join().expect("native rollback backend");

    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered), native);
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}

#[test]
fn public_recover_retains_reconciliation_while_foreign_bytes_are_present() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Foreign.kt");
    let content = b"package sample\nclass Added\n";
    let foreign = b"package sample\nclass Foreign\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Foreign.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    std::fs::write(&target, foreign).expect("foreign target");
    let reconciliation = verified_add_file_reconciliation_required(
        &target,
        content,
        "PSI_ADMISSION",
        "PSI_NOT_ADMITTED",
    );
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("foreign-reconciliation.sock"),
        vec![
            ("change/apply-add-file", reconciliation.clone()),
            ("change/apply-add-file", reconciliation.clone()),
        ],
    );
    for command in ["apply", "recover"] {
        let result = if command == "apply" {
            installed_public_kast(&binary, &home, &config_home, &workspace)
                .args(["change", "apply", "--plan-id", &plan_id])
                .output()
                .expect("foreign apply")
        } else {
            installed_public_kast(&binary, &home, &config_home, &workspace)
                .args(["change", "recover", "--recovery-id", &plan_id])
                .output()
                .expect("foreign recover")
        };
        assert_eq!(result.status.code(), Some(1), "{result:?}");
        assert_eq!(decode(&result), reconciliation);
        assert_eq!(
            std::fs::read(&target).expect("foreign bytes retained"),
            foreign
        );
    }
    assert_eq!(
        backend
            .join()
            .expect("foreign reconciliation backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        2,
    );
}

#[cfg(unix)]
#[test]
fn public_recover_reenters_the_native_plan_after_the_client_is_sigkilled() {
    use std::os::unix::process::ExitStatusExt;

    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Killed.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Killed.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let entered = fixture.path().join("sigkill.entered");
    let release = fixture.path().join("sigkill.release");
    let apply_backend = spawn_gated_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-apply.sock"),
        &target,
        content,
        &entered,
        &release,
        vec![(
            "change/apply-add-file",
            verified_add_file_recovery_required(&target, content, "PSI_ADMISSION"),
        )],
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn killable apply");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(entered.is_file(), "client reached the canonical apply RPC");
    assert_eq!(unsafe { libc::kill(apply.id() as i32, libc::SIGKILL) }, 0);
    let killed = apply.wait_with_output().expect("wait for killed apply");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    std::fs::write(&release, "release\n").expect("release server reply");
    let killed_requests = apply_backend.join().expect("killed apply backend");
    assert_eq!(
        killed_requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );

    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let stored: Value = serde_json::from_slice(&std::fs::read(&plan_path).expect("retained plan"))
        .expect("retained plan JSON");
    assert_eq!(stored["state"]["state"], "APPLY_OUTCOME_UNKNOWN");
    assert_eq!(stored["state"]["authority"]["recoveryId"], plan_id);
    assert_eq!(stored["state"]["authority"]["expectedVersion"], 0);
    let rolled_back =
        verified_add_file_rolled_back(&target, content, "PSI_ADMISSION", "PSI_NOT_ADMITTED");
    let recover_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-recover.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover after SIGKILL");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered), rolled_back);
    assert!(!target.exists());
    recover_backend.join().expect("SIGKILL recovery backend");

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-replay.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("SIGKILL terminal replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), rolled_back);
    assert_eq!(
        replay_backend
            .join()
            .expect("SIGKILL replay backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}
