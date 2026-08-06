#[test]
fn path_projection_recovers_a_create_interrupted_before_receipt_commit() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-control-apply",
        )
        .output()
        .expect("interrupted development setup");
    assert!(
        !interrupted.status.success(),
        "setup must stop at the crash point"
    );

    let recovered = setup(&home, &kast_home, &source);

    assert!(
        recovered.status.success(),
        "recovery setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(
        std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err(),
        "standard recovery must roll back the interrupted developer projection",
    );
    assert!(
        !kast_home.join("path-projection-transaction.json").exists(),
        "recovery must clear the durable transaction",
    );
}

#[test]
fn path_projection_recovers_create_crash_before_identity_journal() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());

    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-control-temporary-create",
        )
        .output()
        .expect("interrupted development setup");

    assert!(
        !interrupted.status.success(),
        "setup must stop at the crash point"
    );
    assert!(kast_home.join("path-projection-transaction.json").is_file());
    assert_eq!(
        control_create_temporaries(&home.join(".local/bin")).len(),
        1
    );

    let recovered = setup(&home, &kast_home, &source);

    assert!(
        recovered.status.success(),
        "recovery setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err());
    assert!(control_create_temporaries(&home.join(".local/bin")).is_empty());
    assert!(!kast_home.join("path-projection-transaction.json").exists());
}

#[test]
fn path_projection_preserves_changed_unproven_temporary_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-control-temporary-create",
        )
        .output()
        .expect("interrupted development setup");
    assert!(
        !interrupted.status.success(),
        "setup must stop at the crash point"
    );
    let temporary_paths = control_create_temporaries(&home.join(".local/bin"));
    assert_eq!(temporary_paths.len(), 1);
    let temporary_path = &temporary_paths[0];
    std::fs::remove_file(temporary_path).expect("replace temporary projection");
    std::fs::write(temporary_path, "unmanaged").expect("changed temporary projection");

    let failed = setup(&home, &kast_home, &source);

    assert!(
        !failed.status.success(),
        "changed unproven temporary projection must fail closed",
    );
    let error: serde_json::Value =
        serde_json::from_slice(&failed.stdout).expect("typed recovery failure");
    assert_eq!(error["code"], "PATH_PROJECTION_RECOVERY_CONFLICT");
    assert_eq!(
        std::fs::read_to_string(temporary_path).expect("preserved temporary replacement"),
        "unmanaged",
    );
    assert!(kast_home.join("path-projection-transaction.json").is_file());
}

#[test]
fn path_projection_preserves_same_target_path_for_unmaterialized_create() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-control-create-prepare",
        )
        .output()
        .expect("interrupted development setup");
    assert!(
        !interrupted.status.success(),
        "setup must stop after prepare"
    );
    let journal_path = kast_home.join("path-projection-transaction.json");
    let journal: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&journal_path).expect("projection journal"))
            .expect("projection journal JSON");
    let temporary_path = PathBuf::from(
        journal["mutation"]["temporary_path"]
            .as_str()
            .expect("prepared temporary path"),
    );
    let expected_target = kast_home.join("current/libexec/kastctl");
    std::os::unix::fs::symlink(&expected_target, &temporary_path)
        .expect("same-target unmanaged path");

    let failed = setup(&home, &kast_home, &source);

    assert!(
        !failed.status.success(),
        "an occupied unmaterialized path must fail closed",
    );
    let error: serde_json::Value =
        serde_json::from_slice(&failed.stdout).expect("typed recovery failure");
    assert_eq!(error["code"], "PATH_PROJECTION_RECOVERY_CONFLICT");
    assert_eq!(
        std::fs::read_link(&temporary_path).expect("preserved same-target path"),
        expected_target,
    );
    assert!(journal_path.is_file());
}

#[test]
fn path_projection_recovers_a_create_interrupted_after_receipt_commit() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-receipt-commit",
        )
        .output()
        .expect("interrupted development setup");
    assert!(
        !interrupted.status.success(),
        "setup must stop at the crash point"
    );
    assert!(home.join(".local/bin/kastctl").is_symlink());
    assert!(kast_home.join("path-projection-transaction.json").is_file());

    let recovered = setup(&home, &kast_home, &source);

    assert!(
        recovered.status.success(),
        "recovery setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(
        std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err(),
        "standard recovery must complete the committed transaction before removing it",
    );
    assert!(
        !kast_home.join("path-projection-transaction.json").exists(),
        "recovery must clear the durable transaction",
    );
}

#[test]
fn post_receipt_projection_failure_never_rolls_back_activated_release() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(
        setup_with_profile(&home, &kast_home, &first_source, "development")
            .status
            .success(),
    );

    let failed = setup_command(&home, &kast_home, &second_source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-control-transaction-finalize",
        )
        .output()
        .expect("post-receipt setup failure");

    assert!(
        !failed.status.success(),
        "the injected post-receipt failure must fail setup",
    );
    let receipt_path = kast_home.join("current/receipt.json");
    let receipt: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&receipt_path).expect("published receipt"))
            .expect("published receipt JSON");
    assert_eq!(receipt["activeVersion"], "v9.8.8");
    assert_eq!(receipt["setupProfile"], "STANDARD");
    assert!(std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err());
    assert!(kast_home.join("path-projection-transaction.json").is_file());

    let recovered = setup(&home, &kast_home, &second_source);

    assert!(
        recovered.status.success(),
        "retry must finalize the published state: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(!kast_home.join("path-projection-transaction.json").exists());
}

#[test]
fn same_release_failures_before_migration_and_verification_preserve_the_prior_profile() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );

    for failure_point in ["before-current-migration", "before-current-verify"] {
        let output = setup_command(&home, &kast_home, &source)
            .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
            .env(
                "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
                failure_point,
            )
            .output()
            .expect("failing standard setup");
        assert!(
            !output.status.success(),
            "injected {failure_point} failure must fail setup",
        );
        assert_eq!(
            std::fs::read_link(home.join(".local/bin/kastctl"))
                .expect("prior developer projection"),
            kast_home.join("current/libexec/kastctl"),
        );
        let receipt: serde_json::Value = serde_json::from_slice(
            &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
        )
        .expect("setup receipt JSON");
        assert_eq!(receipt["setupProfile"], "DEVELOPMENT");
    }
}

#[test]
fn control_exchange_preserves_a_replacement_after_exchange() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup_with_profile(&home, &kast_home, &first, "development")
        .status
        .success());
    let control = home.join(".local/bin/kastctl");
    let manifest_path = second.join("manifest.json");
    let custom_binary = second.join("commands/kastctl");
    std::fs::create_dir_all(custom_binary.parent().unwrap()).expect("custom binary directory");
    std::fs::rename(second.join("libexec/kastctl"), &custom_binary).expect("custom binary");
    let mut manifest: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&manifest_path).unwrap()).unwrap();
    manifest["activation"]["cli"]["path"] = serde_json::json!("commands/kastctl");
    manifest["artifacts"][0]["path"] = serde_json::json!("commands/kastctl");
    std::fs::write(&manifest_path, serde_json::to_vec(&manifest).unwrap()).unwrap();
    let barrier = temp.path().join("control-exchange-barrier");
    let mut child = setup_command(&home, &kast_home, &second)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_PATH", &control)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-projection-exchange-before-validation",
        )
        .spawn()
        .expect("setup at control exchange barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-projection-exchange-before-validation",
    );
    let observed = temp.path().join("observed-control");
    std::fs::rename(&control, &observed).expect("observe control publication");
    std::fs::write(&control, "operator control\n").expect("operator control");
    release_setup_barrier(&barrier, "after-projection-exchange-before-validation");

    let output = child.wait_with_output().expect("setup output");

    assert!(!output.status.success(), "changed publication must fail");
    assert_eq!(std::fs::read_to_string(control).unwrap(), "operator control\n");
    assert!(observed.is_symlink());
}
