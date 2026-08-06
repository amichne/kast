#[test]
fn receipt_write_does_not_follow_a_predictable_temporary_symlink() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let barrier = temp.path().join("receipt-write-barrier");
    let receipt_path = kast_home.join("current/receipt.json");
    let mut child = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-receipt-temporary-create",
        )
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_PATH",
            &receipt_path,
        )
        .spawn()
        .expect("spawn development setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-receipt-temporary-create");
    let victim = temp.path().join("victim");
    std::fs::write(&victim, "preserve").expect("victim file");
    let predictable = receipt_path.with_extension(format!("json.tmp-{}", child.id()));
    std::os::unix::fs::symlink(&victim, &predictable).expect("predictable temporary symlink");
    release_setup_barrier(&barrier, "before-receipt-temporary-create");

    let output = child.wait_with_output().expect("development setup output");

    assert!(
        output.status.success(),
        "safe receipt publication should ignore the attacker path: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(&victim).expect("preserved victim"),
        "preserve",
    );
    assert_eq!(
        std::fs::read_link(&predictable).expect("untouched attacker symlink"),
        victim,
    );
}

#[test]
fn journal_write_does_not_follow_a_predictable_temporary_symlink() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let barrier = temp.path().join("journal-write-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-identity-journal-write",
        )
        .spawn()
        .expect("spawn development setup");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "before-control-identity-journal-write",
    );
    let victim = temp.path().join("journal-victim");
    std::fs::write(&victim, "preserve").expect("victim file");
    let journal_path = kast_home.join("path-projection-transaction.json");
    let predictable = journal_path.with_extension(format!("json.tmp-{}", child.id()));
    std::os::unix::fs::symlink(&victim, &predictable).expect("predictable temporary symlink");
    release_setup_barrier(&barrier, "before-control-identity-journal-write");

    let output = child.wait_with_output().expect("development setup output");

    assert!(
        output.status.success(),
        "safe journal publication should ignore the attacker path: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(&victim).expect("preserved victim"),
        "preserve",
    );
    assert_eq!(
        std::fs::read_link(&predictable).expect("untouched attacker symlink"),
        victim,
    );
}

#[test]
fn projection_journal_rejects_invalid_semantics_and_external_namespaces() {
    for case in ["development-remove", "standard-create", "external-remove"] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let kast_home = home.join(".local/share/kast");
        let source = write_install_bundle_source(temp.path(), "v9.8.7");
        let initial_profile = if case == "standard-create" {
            "standard"
        } else {
            "development"
        };
        assert!(
            setup_with_profile(&home, &kast_home, &source, initial_profile)
                .status
                .success(),
        );
        let receipt_path = kast_home.join("current/receipt.json");
        let receipt: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&receipt_path).expect("receipt"))
                .expect("receipt JSON");
        let control_path = home.join(".local/bin/kastctl");
        let control_target = kast_home.join("current/libexec/kastctl");
        let protected = temp.path().join(format!("{case}-protected"));
        let (intended_profile, mutation) = match case {
            "development-remove" => {
                std::fs::write(&protected, "preserve").expect("protected file");
                (
                    "DEVELOPMENT",
                    serde_json::json!({
                        "kind": "REMOVE",
                        "quarantine_path": protected.display().to_string(),
                        "prior_target": control_target.display().to_string(),
                        "prior_identity": projection_identity_json(&protected),
                    }),
                )
            }
            "standard-create" => {
                std::os::unix::fs::symlink(&control_target, &protected).expect("protected symlink");
                (
                    "STANDARD",
                    serde_json::json!({
                        "kind": "CREATE_MATERIALIZED",
                        "temporary_path": protected.display().to_string(),
                        "projected_identity": projection_identity_json(&protected),
                    }),
                )
            }
            "external-remove" => {
                std::os::unix::fs::symlink(&control_target, &protected).expect("protected symlink");
                (
                    "STANDARD",
                    serde_json::json!({
                        "kind": "REMOVE",
                        "quarantine_path": protected.display().to_string(),
                        "prior_target": control_target.display().to_string(),
                        "prior_identity": projection_identity_json(&protected),
                    }),
                )
            }
            _ => unreachable!("closed journal test case"),
        };
        std::fs::write(
            kast_home.join("path-projection-transaction.json"),
            serde_json::to_vec_pretty(&serde_json::json!({
                "schemaVersion": 3,
                "controlPath": control_path.display().to_string(),
                "controlTarget": control_target.display().to_string(),
                "receiptPath": receipt_path.display().to_string(),
                "releaseDigest": receipt["releaseDigest"],
                "intendedProfile": intended_profile,
                "transactionNonce": "1-2-3",
                "mutation": mutation,
            }))
            .expect("projection journal JSON"),
        )
        .expect("projection journal");

        let output = setup_with_profile(&home, &kast_home, &source, initial_profile);

        assert!(!output.status.success(), "case {case} must fail closed");
        let error: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("typed journal failure");
        assert_eq!(
            error["code"], "PATH_PROJECTION_TRANSACTION_INVALID",
            "case {case}: {error}",
        );
        assert!(
            std::fs::symlink_metadata(&protected).is_ok(),
            "case {case} must preserve its external path",
        );
        assert!(kast_home.join("path-projection-transaction.json").is_file());
    }
}

#[test]
fn setup_durability_remove_sync_failure_retains_recovery_journal() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );

    let interrupted = setup_with_durability_failure(
        &home,
        &kast_home,
        &source,
        "standard",
        "after-control-remove-before-parent-sync",
    );

    assert!(
        !interrupted.status.success(),
        "setup must not publish the standard receipt before removal is durable",
    );
    assert!(
        kast_home.join("path-projection-transaction.json").is_file(),
        "the recovery journal must remain until removal is durable",
    );
    let recovered = setup_with_profile(&home, &kast_home, &source, "development");
    assert!(
        recovered.status.success(),
        "development setup must recover the uncommitted removal: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(home.join(".local/bin/kastctl").is_symlink());
    assert!(!kast_home.join("path-projection-transaction.json").exists());
}

#[test]
fn remove_rollback_restores_a_quarantine_replacement_after_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let expected_target = kast_home.join("current/libexec/kastctl");
    let barrier = temp
        .path()
        .join("control-remove-rollback-validation-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-control-remove-rollback-validation",
        )
        .spawn()
        .expect("spawn standard setup with remove rollback barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-control-remove-rollback-validation",
    );
    let quarantine = std::fs::read_dir(home.join(".local/bin"))
        .expect("local bin directory")
        .map(|entry| entry.expect("local bin entry").path())
        .find(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with("kastctl.kast-remove-"))
        })
        .expect("control quarantine");
    let captured_projection = quarantine.with_file_name(format!(
        "{}.captured",
        quarantine
            .file_name()
            .expect("quarantine file name")
            .to_string_lossy(),
    ));
    assert_eq!(
        std::fs::read_link(&quarantine).expect("validated quarantine"),
        expected_target,
    );
    std::fs::rename(&quarantine, &captured_projection).expect("preserve validated quarantine");
    std::fs::write(&quarantine, "late unmanaged").expect("replacement quarantine");
    release_setup_barrier(&barrier, "after-control-remove-rollback-validation");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "remove rollback race must fail closed"
    );
    assert!(
        std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err(),
        "an unproven quarantine object must not remain public",
    );
    assert_eq!(
        std::fs::read_to_string(&quarantine).expect("restored quarantine replacement"),
        "late unmanaged",
    );
    assert_eq!(
        std::fs::read_link(&captured_projection).expect("preserved validated projection"),
        expected_target,
    );
    assert!(kast_home.join("path-projection-transaction.json").is_file());
}

#[test]
fn setup_durability_cleanup_sync_failure_retains_recovery_journal() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );

    let interrupted = setup_with_durability_failure(
        &home,
        &kast_home,
        &source,
        "standard",
        "after-control-cleanup-before-parent-sync",
    );

    assert!(
        !interrupted.status.success(),
        "setup must not remove the journal before quarantine cleanup is durable",
    );
    assert!(
        kast_home.join("path-projection-transaction.json").is_file(),
        "the recovery journal must remain until cleanup is durable",
    );
    let recovered = setup(&home, &kast_home, &source);
    assert!(
        recovered.status.success(),
        "standard setup must finish the committed removal: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err());
    assert!(!kast_home.join("path-projection-transaction.json").exists());
}
