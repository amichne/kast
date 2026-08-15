use super::*;

pub(crate) fn successful_verified_add_file_script(
    target: &Path,
    content: &[u8],
) -> Vec<(&'static str, Value)> {
    vec![(
        "change/apply-add-file",
        verified_add_file_receipt(target, content),
    )]
}

pub(crate) fn assert_independent_verification_failure_rolls_back(
    case: &str,
    verification_script: impl FnOnce(&Path, &str) -> Vec<(&'static str, Value)>,
) {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Unverified.kt");
    let content = b"package sample\nclass Unverified\n";
    let content_hash = source_sha256(content);
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Unverified.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let _legacy_verification_fixture = verification_script(&target, &content_hash);
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-apply.sock")),
        vec![(
            "change/apply-add-file",
            verified_add_file_recovery_required(&target, content, "PSI_ADMISSION"),
        )],
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("apply with unavailable compiler verification");
    let apply_requests = apply_backend.join().expect("apply backend");
    assert_eq!(apply.status.code(), Some(1), "{apply:?}");
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "RECOVERY_REQUIRED", "{receipt:#}");
    assert_eq!(receipt["planId"], plan_id);
    assert!(
        !target.exists(),
        "client must not perform a raw source write"
    );
    assert_eq!(
        apply_requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
            .collect::<Vec<_>>(),
        ["change/apply-add-file"],
    );

    let rolled_back =
        verified_add_file_rolled_back(&target, content, "PSI_ADMISSION", "PSI_NOT_ADMITTED");
    let recover_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-recover.sock")),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover unverified postimage in a new process");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let recovered_receipt = decode(&recovered);
    assert_eq!(
        recovered_receipt["outcome"], "ROLLED_BACK",
        "{recovered_receipt:#}"
    );
    assert_eq!(recovered_receipt["failure"], "PSI_NOT_ADMITTED");
    assert!(
        !target.exists(),
        "typed rollback retained the absent pre-state"
    );
    recover_backend.join().expect("recovery backend");

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-replay.sock")),
        vec![("change/apply-add-file", rolled_back)],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal recovery replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), recovered_receipt);
    assert_eq!(
        replay_backend
            .join()
            .expect("terminal recovery replay backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
}
