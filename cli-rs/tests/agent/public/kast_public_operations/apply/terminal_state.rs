use super::*;

#[test]
fn public_apply_and_recover_share_one_exclusive_plan_lock() {
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
    let socket = fixture.path().join("exclusive-plan-lock.sock");
    let entered = fixture.path().join("exclusive-plan-lock.entered");
    let release = fixture.path().join("exclusive-plan-lock.release");
    let backend = spawn_gated_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &socket,
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
    assert!(
        entered.is_file(),
        "first apply reached the mutation boundary"
    );

    let journal_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.recovery.json"));
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
    let journal_before = std::fs::read(&journal_path).expect("prepared journal");
    let concurrent = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("concurrent recover");
    assert_eq!(concurrent.status.code(), Some(1), "{concurrent:?}");
    assert_eq!(decode(&concurrent)["error"], "KAST_PLAN_BUSY");
    assert_eq!(
        std::fs::read(&journal_path).expect("unchanged journal"),
        journal_before,
    );
    assert!(!target.exists(), "concurrent recovery performed no write");

    std::fs::write(&release, "release\n").expect("release mutation gate");
    let first = first.wait_with_output().expect("first apply output");
    assert!(
        first.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&first.stdout),
        String::from_utf8_lossy(&first.stderr),
    );
    let receipt = decode(&first);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    let requests = backend.join().expect("gated mutation backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
        "the concurrent process must not submit another mutation",
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal recover replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "terminal receipt is unchanged");
}

#[test]
fn public_apply_classifies_exact_source_drift_before_semantic_revalidation() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let binary = write_active_kast_for_test(&home, &config_home);
    let relative_path = "src/main/kotlin/Ordering.kt";
    let target = workspace.join(relative_path);
    let planned = "package sample\nclass Planned\n";
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        relative_path,
        planned,
    );
    let foreign = b"package sample\nclass Foreign\n";
    std::fs::write(&target, foreign).expect("drifted target");

    let mut changed_authority = public_exact_add_file_preview(&workspace, &target, planned);
    changed_authority["proof"]["context"]["requiredGeneration"] = json!(8);
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("ordering-apply.sock"),
        vec![("raw/plan-add-file", changed_authority)],
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("drifted apply");
    assert_eq!(apply.status.code(), Some(1), "{apply:?}");
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "CONFLICTED", "{receipt:#}");
    assert_eq!(receipt["schemaVersion"], 7, "{receipt:#}");
    assert_eq!(
        std::fs::read(&target).expect("foreign source retained"),
        foreign
    );
    let requests = backend.join().expect("ordering backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/plan-add-file")
            .count(),
        0,
        "semantic revalidation must not run after exact source drift"
    );
}

#[test]
fn public_apply_persists_stable_rejected_and_conflicted_outcomes() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let binary = write_active_kast_for_test(&home, &config_home);

    let rejected_target = workspace.join("src/main/kotlin/Rejected.kt");
    let rejected_content = "package sample\nclass Rejected\n";
    let rejected_plan = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Rejected.kt",
        rejected_content,
    );
    let mut changed_authority =
        public_exact_add_file_preview(&workspace, &rejected_target, rejected_content);
    changed_authority["proof"]["context"]["requiredGeneration"] = json!(8);
    let socket = fixture.path().join("rejected-apply.sock");
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![("raw/plan-add-file", changed_authority)],
    );
    let rejected = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &rejected_plan])
        .output()
        .expect("rejected apply");
    assert_eq!(rejected.status.code(), Some(1), "{rejected:?}");
    let rejected_envelope = decode_envelope(&rejected);
    assert_eq!(rejected_envelope["status"], "rejected");
    assert_eq!(rejected_envelope["result"]["type"], "rejected");
    assert_eq!(
        rejected_envelope["result"]["failure"]["type"],
        "mutation-non-success"
    );
    let rejected_receipt = decode(&rejected);
    assert_eq!(
        rejected_receipt["outcome"], "REJECTED",
        "{rejected_receipt:#}"
    );
    assert_eq!(rejected_receipt["schemaVersion"], 7, "{rejected_receipt:#}");
    assert!(
        !rejected_target.exists(),
        "rejection retained absent pre-state"
    );
    assert_eq!(
        backend
            .join()
            .expect("rejected backend")
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        0,
    );
    let rejected_replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &rejected_plan])
        .output()
        .expect("rejected replay");
    assert_eq!(
        rejected_replay.status.code(),
        Some(1),
        "{rejected_replay:?}"
    );
    assert_eq!(decode(&rejected_replay), rejected_receipt);

    let conflicted_target = workspace.join("src/main/kotlin/Conflicted.kt");
    let conflicted_plan = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Conflicted.kt",
        "package sample\nclass Planned\n",
    );
    let foreign = b"package sample\nclass Foreign\n";
    std::fs::write(&conflicted_target, foreign).expect("foreign source");
    let conflicted_socket = fixture.path().join("conflicted-apply.sock");
    let conflicted_shutdown = fixture.path().join("conflicted-apply.shutdown");
    let conflicted_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &conflicted_socket,
        &conflicted_shutdown,
    );
    let conflicted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &conflicted_plan])
        .output()
        .expect("conflicted apply");
    assert_eq!(conflicted.status.code(), Some(1), "{conflicted:?}");
    let conflicted_receipt = decode(&conflicted);
    assert_eq!(
        conflicted_receipt["outcome"], "CONFLICTED",
        "{conflicted_receipt:#}"
    );
    assert_eq!(
        conflicted_receipt["schemaVersion"], 7,
        "{conflicted_receipt:#}"
    );
    assert_eq!(
        std::fs::read(&conflicted_target).expect("foreign source retained"),
        foreign,
    );
    std::fs::write(&conflicted_shutdown, "stop\n").expect("stop conflict backend");
    assert_eq!(
        conflicted_backend
            .join()
            .expect("conflicted backend")
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        0,
    );
    let conflicted_replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &conflicted_plan])
        .output()
        .expect("conflicted replay");
    assert_eq!(
        conflicted_replay.status.code(),
        Some(1),
        "{conflicted_replay:?}"
    );
    assert_eq!(decode(&conflicted_replay), conflicted_receipt);
}

#[test]
fn prejournal_failures_retain_a_simultaneous_lease_release_failure() {
    for (case, failure_point, primary_failure) in [
        (
            "journal-persistence",
            "RECOVERY_JOURNAL_PERSISTENCE",
            "Recovery journal persistence failed at the deterministic test seam.",
        ),
        (
            "before-journal",
            "BEFORE_RECOVERY_JOURNAL",
            "Apply stopped before its recovery journal became durable.",
        ),
    ] {
        let fixture = tempfile::tempdir().expect("fixture");
        let home = fixture.path().join("home");
        let config_home = fixture.path().join("config");
        let workspace = fixture.path().join("workspace");
        std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
        let workspace = workspace.canonicalize().expect("canonical workspace");
        let target = workspace.join("src/main/kotlin/Prejournal.kt");
        let content = "package sample\nclass Prejournal\n";
        let binary = write_active_kast_for_test(&home, &config_home);
        let plan_id = plan_add_file(
            &binary,
            &home,
            &config_home,
            &workspace,
            "src/main/kotlin/Prejournal.kt",
            content,
        );
        let shutdown = fixture.path().join(format!("{case}.shutdown"));
        let backend = spawn_lease_only_mutating_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &fixture.path().join(format!("{case}.sock")),
            &shutdown,
        );

        let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
            .env("KAST_TEST_MUTATION_FAILURE_POINT", failure_point)
            .env("KAST_TEST_MUTATION_LEASE_RELEASE_FAILURE", "1")
            .args(["change", "apply", "--plan-id", &plan_id])
            .output()
            .expect("prejournal dual failure");
        std::fs::write(&shutdown, "stop\n").expect("stop prejournal backend");
        backend.join().expect("prejournal backend");
        assert_eq!(apply.status.code(), Some(1), "{apply:?}");
        let failure = decode(&apply);
        assert_eq!(
            failure["error"], "KAST_MUTATION_LEASE_RELEASE_FAILED",
            "case={case}; failure={failure:#}",
        );
        assert!(
            failure["message"]
                .as_str()
                .is_some_and(|message| message.contains(primary_failure)
                    && message.contains("owned lease could not be released")),
            "case={case}; failure={failure:#}",
        );
        assert!(!target.exists(), "case={case}; no source write is allowed");
        assert!(
            !home
                .join(".local/share/kast/state/agent-plans")
                .join(format!("{plan_id}.recovery.json"))
                .exists(),
            "case={case}; no recovery journal became durable",
        );
    }
}
