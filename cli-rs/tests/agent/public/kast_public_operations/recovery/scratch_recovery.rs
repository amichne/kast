use super::*;

#[cfg(unix)]
#[test]
fn public_recover_restores_declared_quarantine_scratch_after_cas_is_sigkilled() {
    use std::os::unix::process::ExitStatusExt;

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
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_replacement(
        &binary,
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-cas-plan.sock"),
        &replacement,
    );
    let entered = fixture.path().join("sigkill-cas.entered");
    let release = fixture.path().join("sigkill-cas.release");
    let backend = spawn_gated_quarantine_scratch_crash_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-cas.sock"),
        &entered,
        &release,
        vec![("raw/plan-replacement", replacement.preview)],
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn killable CAS apply");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(
        entered.is_file(),
        "CAS detached into its declared quarantine"
    );
    let retained_scratch = std::fs::read_to_string(&entered).expect("retained quarantine path");
    let retained_scratch = Path::new(retained_scratch.trim()).to_path_buf();
    let kill_result = unsafe { libc::kill(apply.id() as i32, libc::SIGKILL) };
    assert_eq!(kill_result, 0, "SIGKILL CAS apply child");
    let killed = apply.wait_with_output().expect("wait for killed CAS apply");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    std::fs::write(&release, "release\n").expect("release detached CAS backend");
    backend.join().expect("killed CAS backend");
    assert!(
        !target.exists(),
        "detached target remains absent after client death"
    );
    assert_eq!(
        std::fs::read(&retained_scratch).expect("declared quarantine preimage"),
        preimage
    );

    let shutdown = fixture.path().join("sigkill-cas-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("sigkill-cas-recover.sock"),
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover detached CAS scratch");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert_eq!(std::fs::read(&target).expect("restored preimage"), preimage);
    assert!(
        !retained_scratch.exists(),
        "declared quarantine was consumed"
    );
    std::fs::write(&shutdown, "stop\n").expect("stop CAS recovery backend");
    let requests = recover_backend
        .join()
        .expect("CAS scratch recovery backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/recover-mutation-scratch")
            .count(),
        1
    );
    assert!(requests.iter().all(|request| {
        !matches!(
            request["method"].as_str(),
            Some("raw/apply-edits" | "raw/exact-file-image-cas")
        )
    }));
}

#[cfg(unix)]
#[test]
fn public_recover_rejects_wrong_bytes_at_a_journal_owned_scratch_path() {
    use std::os::unix::process::ExitStatusExt;

    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/ForeignScratch.kt");
    let content = b"class ForeignScratch\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/ForeignScratch.kt",
        std::str::from_utf8(content).expect("Kotlin source"),
    );
    let entered = fixture.path().join("foreign-scratch.entered");
    let release = fixture.path().join("foreign-scratch.release");
    let backend = spawn_gated_foreign_prepared_scratch_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("foreign-scratch.sock"),
        &entered,
        &release,
        successful_verified_add_file_script(&target, content),
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn foreign-scratch apply");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(
        entered.is_file(),
        "backend retained a declared scratch role"
    );
    let scratch = std::fs::read_to_string(&entered).expect("foreign scratch path");
    let scratch = Path::new(scratch.trim()).to_path_buf();
    let kill_result = unsafe { libc::kill(apply.id() as i32, libc::SIGKILL) };
    assert_eq!(kill_result, 0, "SIGKILL foreign-scratch apply");
    let killed = apply
        .wait_with_output()
        .expect("wait for foreign-scratch apply");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    std::fs::write(&release, "release\n").expect("release foreign-scratch backend");
    backend.join().expect("foreign-scratch backend");
    assert!(
        !target.exists(),
        "foreign scratch never authorizes a source write"
    );
    assert_eq!(
        std::fs::read(&scratch).expect("foreign scratch retained"),
        b"foreign scratch image"
    );

    let shutdown = fixture.path().join("foreign-scratch-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("foreign-scratch-recover.sock"),
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover foreign scratch");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered)["outcome"], "RECOVERY_REQUIRED");
    assert!(
        !target.exists(),
        "wrong-hash owned scratch remains write-free"
    );
    assert!(scratch.is_file(), "wrong-hash scratch is not consumed");
    std::fs::write(&shutdown, "stop\n").expect("stop foreign-scratch recovery backend");
    let requests = recover_backend
        .join()
        .expect("foreign-scratch recovery backend");
    assert!(requests.iter().all(|request| {
        !matches!(
            request["method"].as_str(),
            Some("raw/apply-edits" | "raw/exact-file-image-cas" | "raw/recover-mutation-scratch")
        )
    }));
}

#[cfg(unix)]
fn assert_reverse_quarantine_only_recovery(case: &str, preimage: &[u8]) {
    use std::os::unix::process::ExitStatusExt;

    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_root = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_root).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let target = source_root.join("Reverse.kt");
    std::fs::write(&target, preimage).expect("present source preimage");
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
        &fixture.path().join(format!("{case}-plan.sock")),
        &replacement,
    );
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-apply.sock")),
        vec![("raw/plan-replacement", replacement.preview)],
    );
    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_ALL_WRITES")
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("interrupt after forward write");
    assert_eq!(decode(&interrupted)["outcome"], "RECOVERY_REQUIRED");
    apply_backend.join().expect("forward apply backend");
    assert_eq!(
        std::fs::read(&target).expect("forward postimage"),
        postimage
    );

    let entered = fixture.path().join(format!("{case}-reverse.entered"));
    let release = fixture.path().join(format!("{case}-reverse.release"));
    let reverse_backend = spawn_gated_quarantine_scratch_crash_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-reverse.sock")),
        &entered,
        &release,
        vec![(
            "raw/verify-mutation-postcondition",
            scripted_json_rpc_error(
                "MUTATION_POSTCONDITION_FAILED",
                "Force reverse exact-image recovery",
                json!({}),
                false,
            ),
        )],
    );
    let recovery = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn killable reverse recovery");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(entered.is_file(), "reverse CAS detached its postimage");
    let quarantine = std::fs::read_to_string(&entered).expect("reverse quarantine path");
    let quarantine = Path::new(quarantine.trim()).to_path_buf();
    let kill_result = unsafe { libc::kill(recovery.id() as i32, libc::SIGKILL) };
    assert_eq!(kill_result, 0, "SIGKILL reverse recovery");
    let killed = recovery
        .wait_with_output()
        .expect("wait for reverse recovery");
    assert_eq!(killed.status.signal(), Some(libc::SIGKILL), "{killed:?}");
    std::fs::write(&release, "release\n").expect("release reverse backend");
    let reverse_requests = reverse_backend.join().expect("reverse backend");
    assert!(!target.exists(), "reverse target is absent after detach");
    assert_eq!(
        std::fs::read(&quarantine).expect("reverse quarantine postimage"),
        postimage
    );
    let reverse_request = reverse_requests
        .iter()
        .find(|request| request["method"] == "raw/exact-file-image-cas")
        .expect("reverse exact-image request");
    let prepared = Path::new(
        reverse_request["params"]["mutationScratch"]["preparedPath"]
            .as_str()
            .expect("reverse prepared path"),
    );
    assert!(
        !prepared.exists(),
        "crash occurred before reverse preimage preparation"
    );

    let shutdown = fixture.path().join(format!("{case}-final.shutdown"));
    let final_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join(format!("{case}-final.sock")),
        &shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover reverse quarantine-only state");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert!(
        target.is_file(),
        "PRESENT preimage is not collapsed to ABSENT"
    );
    assert_eq!(
        std::fs::read(&target).expect("restored exact preimage"),
        preimage
    );
    assert!(!quarantine.exists(), "reverse quarantine is consumed");
    std::fs::write(&shutdown, "stop\n").expect("stop final recovery backend");
    let final_requests = final_backend.join().expect("final recovery backend");
    let scratch_recovery = final_requests
        .iter()
        .find(|request| request["method"] == "raw/recover-mutation-scratch")
        .expect("typed reverse scratch recovery");
    assert_eq!(
        scratch_recovery["params"]["scratchDirection"],
        "RESTORE_PREIMAGE"
    );
    assert_eq!(scratch_recovery["params"]["preimage"]["state"], "PRESENT");
    assert!(final_requests.iter().all(|request| {
        !matches!(
            request["method"].as_str(),
            Some("raw/apply-edits" | "raw/exact-file-image-cas")
        )
    }));
}

#[cfg(unix)]
#[test]
fn public_recover_materializes_a_nonempty_present_preimage_from_reverse_quarantine_only() {
    assert_reverse_quarantine_only_recovery("reverse-present", b"class Existing\n");
}

#[cfg(unix)]
#[test]
fn public_recover_preserves_a_whitespace_only_present_preimage_from_reverse_quarantine_only() {
    assert_reverse_quarantine_only_recovery("reverse-whitespace-present", b"\n");
}
