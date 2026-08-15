use super::*;

#[test]
fn occupied_verified_add_file_plan_namespace_is_rejected_without_overwrite() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/DanglingPlan.kt");
    let content = b"package sample\nclass Added\n";
    let plan_id = verified_add_file_plan_id(&target, content);
    let plan_directory = home.join(".local/share/kast/state/agent-plans");
    std::fs::create_dir_all(&plan_directory).expect("plan directory");
    let plan_path = plan_directory.join(format!("{plan_id}.json"));
    let dangling_target = fixture.path().join("missing-plan.json");
    std::os::unix::fs::symlink(&dangling_target, &plan_path).expect("dangling plan link");
    let binary = write_active_kast_for_test(&home, &config_home);

    let planned = change_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/DanglingPlan.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    assert_eq!(planned.status.code(), Some(1), "{planned:?}");
    assert!(
        std::fs::symlink_metadata(&plan_path)
            .expect("plan namespace entry retained")
            .file_type()
            .is_symlink(),
    );
    assert!(!dangling_target.exists());
    assert!(!target.exists());
}

#[test]
fn verified_add_file_recovery_state_is_private_durable_and_replayable() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/DurableRecovery.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/DurableRecovery.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let pending = verified_add_file_recovery_required(&target, content, "WORKSPACE_PUBLICATION");
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("durable-recovery-apply.sock"),
        vec![("change/apply-add-file", pending.clone())],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("recovery-required apply");
    assert_eq!(applied.status.code(), Some(1), "{applied:?}");
    assert_eq!(decode(&applied), pending);
    apply_backend.join().expect("recovery-required backend");

    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    assert_eq!(
        std::fs::metadata(&plan_path)
            .expect("durable plan")
            .permissions()
            .mode()
            & 0o777,
        0o600,
    );
    let stored: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("durable recovery state"))
            .expect("durable recovery JSON");
    assert_eq!(stored["state"]["state"], "RECOVERY_REQUIRED");
    assert_eq!(stored["state"]["result"], pending);

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
        &fixture.path().join("durable-recovery-recover.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("durable recovery");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered), rolled_back);
    recover_backend.join().expect("durable recovery backend");

    let terminal: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("terminal state"))
            .expect("terminal JSON");
    assert_eq!(terminal["state"]["state"], "TERMINAL");
    assert_eq!(terminal["state"]["result"], rolled_back);
    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("durable-recovery-replay.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("durable terminal replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), rolled_back);
    assert_eq!(
        replay_backend
            .join()
            .expect("durable terminal replay backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}
