#[test]
fn force_path_projection_can_return_a_development_install_to_standard() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );

    let output = setup_command(&home, &kast_home, &source)
        .arg("--force")
        .output()
        .expect("forced standard setup");

    assert!(
        output.status.success(),
        "forced standard setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err());
}

#[test]
fn force_failure_preserves_receipt_owned_control_authority_for_retry() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let control = home.join(".local/bin/kastctl");
    let expected_target = kast_home.join("current/libexec/kastctl");
    assert_eq!(
        std::fs::read_link(&control).expect("receipt-owned control projection"),
        expected_target,
    );

    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--force", "--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-force-reset",
        )
        .output()
        .expect("forced setup failure");

    assert_eq!(
        interrupted.status.code(),
        Some(86),
        "injected force crash must stop setup"
    );
    let authority_snapshot = kast_home.join("force-reset-path-authority.json");
    assert!(
        authority_snapshot.is_file(),
        "the durable authority snapshot must survive process exit",
    );
    assert!(
        !kast_home.join("path-projection-transaction.json").exists(),
        "the failure must occur before a PATH mutation transaction",
    );
    assert!(
        std::fs::symlink_metadata(kast_home.join("current")).is_err(),
        "force reset must have removed the receipt authority",
    );
    assert_eq!(
        std::fs::read_link(&control).expect("preserved control projection"),
        expected_target,
    );

    let recovered = setup_with_profile(&home, &kast_home, &source, "development");

    assert!(
        recovered.status.success(),
        "retry must recover durable control authority: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert_eq!(
        std::fs::read_link(&control).expect("recovered control projection"),
        expected_target,
    );
    assert!(
        !authority_snapshot.exists(),
        "successful receipt publication must remove the stale authority snapshot",
    );
}

#[test]
fn force_failure_authority_never_authorizes_a_control_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--force", "--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "after-force-reset",
        )
        .output()
        .expect("forced setup failure");
    assert!(
        !interrupted.status.success(),
        "injected force failure must stop setup"
    );
    let control = home.join(".local/bin/kastctl");
    std::fs::remove_file(&control).expect("replace stranded control projection");
    std::fs::write(&control, "late unmanaged").expect("unmanaged control replacement");

    let rejected = setup_with_profile(&home, &kast_home, &source, "development");

    assert!(!rejected.status.success(), "replacement must fail closed");
    let error: serde_json::Value =
        serde_json::from_slice(&rejected.stdout).expect("typed force recovery error");
    assert_eq!(error["code"], "FORCE_RESET_PATH_AUTHORITY_CHANGED");
    assert_eq!(
        std::fs::read_to_string(&control).expect("preserved unmanaged replacement"),
        "late unmanaged",
    );
    assert!(
        kast_home.join("force-reset-path-authority.json").is_file(),
        "rejected recovery must retain its durable authority evidence",
    );
    assert!(
        std::fs::symlink_metadata(kast_home.join("current")).is_err(),
        "rejected recovery must not activate a release",
    );
}

#[test]
fn force_authority_write_crash_never_publishes_a_partial_journal() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let receipt_path = kast_home.join("current/receipt.json");
    let original_receipt = std::fs::read(&receipt_path).expect("original receipt");
    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--force", "--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "during-force-authority-write",
        )
        .output()
        .expect("force authority write crash");

    assert_eq!(interrupted.status.code(), Some(86));
    assert_eq!(
        std::fs::read(&receipt_path).expect("intact receipt after crash"),
        original_receipt,
    );
    assert!(
        std::fs::symlink_metadata(kast_home.join("force-reset-path-authority.json")).is_err(),
        "a partial authority snapshot must never have its final name",
    );

    let recovered = setup_with_profile(&home, &kast_home, &source, "development");

    assert!(
        recovered.status.success(),
        "intact receipt authority must permit retry: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(
        !kast_home.join("force-reset-path-authority.json").exists(),
        "retry must not retain a force authority snapshot",
    );
    assert!(
        !kast_home.join("staging/force-authority").exists(),
        "retry must retire the crashed unique temporary snapshot",
    );
}

#[test]
fn standard_retry_relinquishes_changed_force_authority() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let interrupted = setup_command(&home, &kast_home, &source)
        .args(["--force", "--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-force-reset",
        )
        .output()
        .expect("forced setup crash");
    assert_eq!(interrupted.status.code(), Some(86));
    let control = home.join(".local/bin/kastctl");
    std::fs::remove_file(&control).expect("replace stranded control projection");
    std::fs::write(&control, "standard unmanaged").expect("unmanaged control replacement");

    let recovered = setup(&home, &kast_home, &source);

    assert!(
        recovered.status.success(),
        "standard retry must treat the replacement as unmanaged: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(&control).expect("preserved unmanaged control"),
        "standard unmanaged",
    );
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("standard receipt"),
    )
    .expect("standard receipt JSON");
    assert_eq!(receipt["setupProfile"], "STANDARD");
    assert!(
        receipt["pathProjections"]
            .as_array()
            .expect("path projections")
            .iter()
            .all(|projection| projection["command"] != "KASTCTL"),
        "standard receipt must not publish kastctl ownership",
    );
    assert!(
        receipt["ownedPaths"]
            .as_array()
            .expect("owned paths")
            .iter()
            .all(|path| path.as_str() != Some(control.to_str().expect("control path"))),
        "standard receipt must not retain kastctl ownership",
    );
    assert!(
        !kast_home.join("force-reset-path-authority.json").exists(),
        "standard recovery must retire stale authority evidence",
    );
}
