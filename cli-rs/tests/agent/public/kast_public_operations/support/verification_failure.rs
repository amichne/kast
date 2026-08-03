use super::*;

pub(crate) fn successful_verified_add_file_script(
    target: &Path,
    content: &[u8],
) -> Vec<(&'static str, Value)> {
    vec![
        ("mutation/submit", successful_add_file_result(target)),
        ("raw/workspace-refresh", independent_refresh(target)),
        (
            "raw/diagnostics",
            independent_diagnostics(target, &source_sha256(content), vec![], 0, 0, 0, 0, None),
        ),
    ]
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
    let verification_script = verification_script(&target, &content_hash);
    let mut script = vec![("mutation/submit", successful_add_file_result(&target))];
    script.extend(verification_script.clone());
    let apply_socket = fixture.path().join(format!("{case}-apply.sock"));
    let apply_backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &apply_socket,
        &target,
        content,
        script,
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["apply", &plan_id])
        .output()
        .expect("apply with unavailable compiler verification");
    let apply_requests = apply_backend.join().expect("apply backend");
    assert_eq!(
        apply.status.code(),
        Some(1),
        "{apply:?}; methods={:?}",
        apply_requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .collect::<Vec<_>>()
    );
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "RECOVERY_REQUIRED", "{receipt:#}");
    assert_eq!(receipt["recoveryId"], plan_id);
    assert_eq!(
        std::fs::read(&target).expect("unverified postimage"),
        content
    );
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
    );

    let recover_socket = fixture.path().join(format!("{case}-recover.sock"));
    let recover_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &recover_socket,
        verification_script,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["recover", &plan_id])
        .output()
        .expect("recover unverified postimage in a new process");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let recovered = decode(&recovered);
    assert_eq!(recovered["outcome"], "ROLLED_BACK", "{recovered:#}");
    assert!(!target.exists(), "recovery restored the absent pre-state");

    let recover_requests = recover_backend.join().expect("recovery backend");
    assert_eq!(
        recover_requests
            .iter()
            .filter(|request| request["method"] == "mutation/submit")
            .count(),
        0,
        "recovery must not resubmit an unverified mutation",
    );
}
