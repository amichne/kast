use super::*;

#[test]
fn public_recover_blocks_on_a_retained_exact_cas_backend_artifact() {
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
    let target = target.canonicalize().expect("canonical source");
    let replacement = replacement_fixture(&target, preimage);
    let postimage = replacement.postimage.clone();
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_replacement(
        &binary,
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-cas-plan.sock"),
        &replacement,
    );
    let artifact = target
        .parent()
        .expect("target parent")
        .join(".kast-cleanup-retained-cas");
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-cas-apply.sock"),
        vec![
            ("raw/plan-replacement", replacement.preview),
            (
                "raw/exact-file-image-cas",
                scripted_json_rpc_error_with_retained_artifact(
                    "UNSAFE_WORKSPACE_MUTATION",
                    "Exact file-image commit retained secure recovery evidence",
                    json!({
                        "recoveryFilePathCount": "1",
                        "recoveryFilePath.0": artifact,
                    }),
                    true,
                    &artifact,
                    preimage,
                ),
            ),
        ],
    );
    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("apply with retained CAS artifact");
    assert_eq!(applied.status.code(), Some(1), "{applied:?}");
    assert_eq!(decode(&applied)["outcome"], "RECOVERY_REQUIRED");
    apply_backend.join().expect("retained CAS apply backend");
    assert_eq!(
        std::fs::read(&target).expect("committed postimage"),
        postimage
    );

    let present_shutdown = fixture.path().join("retained-cas-present.shutdown");
    let present_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-cas-present.sock"),
        &present_shutdown,
    );
    let present = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover with retained CAS artifact present");
    std::fs::write(&present_shutdown, "stop\n").expect("stop present backend");
    let present_requests = present_backend.join().expect("present recovery backend");
    let present_receipt = decode(&present);
    assert_eq!(
        present_receipt["outcome"], "RECOVERY_REQUIRED",
        "{present_receipt:#}"
    );
    assert_eq!(present_receipt["schemaVersion"], 7, "{present_receipt:#}");
    assert!(
        present_requests.iter().all(|request| {
            !matches!(
                request["method"].as_str(),
                Some("raw/apply-edits" | "raw/exact-file-image-cas")
            )
        }),
        "a present backend artifact must block all source writes"
    );
    assert_eq!(
        std::fs::read(&target).expect("retained postimage"),
        postimage
    );

    std::fs::remove_file(&artifact).expect("external cleanup of retained artifact");
    let absent_shutdown = fixture.path().join("retained-cas-absent.shutdown");
    let absent_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-cas-absent.sock"),
        &absent_shutdown,
    );
    let absent = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover after retained CAS artifact removal");
    std::fs::write(&absent_shutdown, "stop\n").expect("stop absent backend");
    let absent_requests = absent_backend.join().expect("absent recovery backend");
    assert!(absent.status.success(), "{absent:?}");
    let receipt = decode(&absent);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert!(
        absent_requests.iter().all(|request| {
            !matches!(
                request["method"].as_str(),
                Some("raw/apply-edits" | "raw/exact-file-image-cas")
            )
        }),
        "all-post recovery after cleanup must remain write-free"
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal CAS recovery replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt);
}

#[test]
fn public_recover_preserves_native_recovery_until_server_reports_terminal_rollback() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/RolledBack.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/RolledBack.kt",
        std::str::from_utf8(content).expect("Kotlin source"),
    );
    let pending = verified_add_file_recovery_required_with_failure(
        &target,
        content,
        "SOURCE_APPLICATION",
        "SOURCE_APPLICATION_FAILED",
    );
    let rolled_back = verified_add_file_rolled_back(
        &target,
        content,
        "SOURCE_APPLICATION",
        "SOURCE_APPLICATION_FAILED",
    );
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-native-recovery.sock"),
        vec![
            ("change/apply-add-file", pending.clone()),
            ("change/apply-add-file", pending.clone()),
            ("change/apply-add-file", rolled_back.clone()),
        ],
    );

    let applied = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("apply requiring native recovery");
    assert_eq!(applied.status.code(), Some(1), "{applied:?}");
    assert_eq!(decode(&applied), pending);

    let still_pending = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recovery while server retains recovery authority");
    assert_eq!(still_pending.status.code(), Some(1), "{still_pending:?}");
    assert_eq!(decode(&still_pending), pending);
    assert!(!target.exists(), "the client owns no rollback writer");

    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("server-completed native recovery");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered), rolled_back);
    let requests = backend.join().expect("retained native recovery backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        3,
    );

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retained-native-replay.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal retained-artifact replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), rolled_back);
    assert_eq!(
        replay_backend
            .join()
            .expect("retained native replay backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}
