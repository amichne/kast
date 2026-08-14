use super::*;

#[test]
fn public_recover_finishes_verified_receipt_after_postwrite_interruption() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
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
    let socket = fixture.path().join("verified-recovery.sock");
    let backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &socket,
        &target,
        content,
        successful_verified_add_file_script(&target, content),
    );

    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env(
            "KAST_TEST_MUTATION_FAILURE_POINT",
            "AFTER_VERIFIED_EVIDENCE",
        )
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("interrupted apply");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    let interrupted = decode(&interrupted);
    assert_eq!(
        interrupted["outcome"], "RECOVERY_REQUIRED",
        "{interrupted:#}"
    );
    assert_eq!(interrupted["recoveryId"], plan_id);
    assert_eq!(std::fs::read(&target).expect("postimage"), content);

    let requests = backend.join().expect("mutation backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
    );

    let recover_socket = fixture.path().join("verified-recovery-second-process.sock");
    let recover_shutdown = fixture
        .path()
        .join("verified-recovery-second-process.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &recover_socket,
        &recover_shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover in a new process");
    assert!(
        recovered.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert_eq!(std::fs::read(&target).expect("verified postimage"), content);
    std::fs::write(&recover_shutdown, "stop\n").expect("stop recovery backend");
    recover_backend.join().expect("recovery backend");

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("terminal apply replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "terminal retry is stable");
}

#[test]
fn public_recover_verifies_all_postimages_after_postwrite_interruption() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
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
    let apply_socket = fixture.path().join("ambiguous-apply.sock");
    let apply_backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &apply_socket,
        &target,
        content,
        vec![("mutation/submit", successful_add_file_result(&target))],
    );

    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env(
            "KAST_TEST_MUTATION_FAILURE_POINT",
            "AFTER_MUTATION_BEFORE_VERIFIED_EVIDENCE",
        )
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("ambiguous postwrite apply");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    let interrupted = decode(&interrupted);
    assert_eq!(
        interrupted["outcome"], "RECOVERY_REQUIRED",
        "{interrupted:#}"
    );
    assert_eq!(
        std::fs::read(&target).expect("ambiguous postimage"),
        content
    );
    let apply_requests = apply_backend.join().expect("apply backend");
    assert_eq!(
        apply_requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
    );

    let recover_socket = fixture.path().join("ambiguous-recover.sock");
    let shutdown = fixture.path().join("ambiguous-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &recover_socket,
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover ambiguous postwrite");
    assert!(recovered.status.success(), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert_eq!(std::fs::read(&target).expect("verified postimage"), content,);

    std::fs::write(&shutdown, "stop\n").expect("stop recovery backend");
    let recover_requests = recover_backend.join().expect("recover backend");
    assert_eq!(
        recover_requests
            .iter()
            .filter(|request| request["method"] == "mutation/submit")
            .count(),
        0,
        "recovery must not resubmit an already-applied mutation",
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("rolled-back recovery replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "verified replay is stable");
}

#[cfg(unix)]
#[test]
fn public_recover_consumes_declared_prepared_scratch_after_apply_is_sigkilled() {
    use std::os::unix::process::ExitStatusExt;

    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Killed.kt");
    let content = b"package sample\nclass Killed\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Killed.kt",
        std::str::from_utf8(content).expect("Kotlin source"),
    );
    let entered = fixture.path().join("sigkill.entered");
    let release = fixture.path().join("sigkill.release");
    let apply_backend = spawn_gated_prepared_scratch_crash_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-apply.sock"),
        &entered,
        &release,
        successful_verified_add_file_script(&target, content),
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
    assert!(
        entered.is_file(),
        "apply entered its durable post-journal write"
    );
    let journal_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.recovery.json"));
    assert!(
        journal_path.is_file(),
        "recovery journal is durable before SIGKILL"
    );
    let kill_result = unsafe { libc::kill(apply.id() as i32, libc::SIGKILL) };
    assert_eq!(kill_result, 0, "SIGKILL apply child");
    let killed = apply.wait_with_output().expect("wait for killed apply");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    let retained_scratch = std::fs::read_to_string(&entered).expect("retained scratch path");
    let retained_scratch = Path::new(retained_scratch.trim()).to_path_buf();

    std::fs::write(&release, "release\n").expect("release in-flight backend request");
    apply_backend.join().expect("killed apply backend");
    assert!(!target.exists(), "target retains its exact absent preimage");
    assert_eq!(
        std::fs::read(&retained_scratch).expect("declared prepared postimage"),
        content
    );

    let shutdown = fixture.path().join("sigkill-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-recover.sock"),
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover after SIGKILL");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert_eq!(
        receipt["schemaVersion"],
        api_schema_version(),
        "{receipt:#}"
    );
    assert!(
        !target.exists(),
        "typed recovery retains the absent preimage"
    );
    assert!(
        !retained_scratch.exists(),
        "typed recovery consumes the exact declared prepared path"
    );
    std::fs::write(&shutdown, "stop\n").expect("stop recovery backend");
    let requests = recover_backend.join().expect("SIGKILL recovery backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| {
                matches!(
                    request["method"].as_str(),
                    Some("raw/apply-edits" | "raw/exact-file-image-cas")
                )
            })
            .count(),
        0,
        "scratch recovery must not resubmit a normal mutation"
    );
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/recover-mutation-scratch")
            .count(),
        1,
        "restart must consume only the journal-declared prepared path"
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("SIGKILL terminal replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), receipt);
}
