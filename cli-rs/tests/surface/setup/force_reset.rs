#[test]
fn force_setup_removes_only_validated_kast_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let registered = home.join("workspaces/registered");
    let current = home.join("workspaces/current/nested");
    let unrelated = home.join("unrelated");

    for directory in [&registered, &current, &unrelated] {
        std::fs::create_dir_all(directory).expect("fixture directory");
    }
    assert!(
        setup(&home, &kast_home, &source).status.success(),
        "initial setup should succeed"
    );
    let unmanaged_control = home.join(".local/bin/kastctl");
    std::fs::write(&unmanaged_control, "unmanaged").expect("unmanaged control command");
    std::fs::write(current.join("source.kt"), "class Source\n").expect("workspace source");
    for root in [
        registered.as_path(),
        current.as_path(),
        current.parent().expect("current parent"),
        home.as_path(),
        unrelated.as_path(),
    ] {
        std::fs::create_dir_all(root.join(".kast")).expect("legacy local Kast state");
        std::fs::write(root.join(".kast/state"), "legacy").expect("legacy state marker");
    }
    std::fs::create_dir_all(kast_home.join("state/data/workspaces"))
        .expect("workspace registry directory");
    std::fs::write(
        kast_home.join("state/data/workspaces/local-workspaces.json"),
        serde_json::to_vec(&serde_json::json!({
            registered.display().to_string(): "registered-id",
            "../../../unrelated": "invalid-relative-root"
        }))
        .expect("registry JSON"),
    )
    .expect("workspace registry");
    std::fs::create_dir_all(kast_home.join("state/cache")).expect("cache");
    std::fs::write(kast_home.join("state/cache/source-index.db"), "database")
        .expect("database");
    std::fs::create_dir_all(kast_home.join("releases/obsolete")).expect("obsolete release");
    std::fs::write(kast_home.join("releases/obsolete/junk"), "obsolete")
        .expect("obsolete release marker");
    let output = setup_command(&home, &kast_home, &source)
        .current_dir(&current)
        .arg("--force")
        .output()
        .expect("forced Kast setup");

    assert!(
        output.status.success(),
        "forced setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(current.join("source.kt").is_file(), "workspace source was removed");
    for root in [
        registered.as_path(),
        current.as_path(),
        current.parent().expect("current parent"),
        home.as_path(),
    ] {
        assert!(
            root.join(".kast/state").is_file(),
            "workspace-owned state was removed at {}",
            root.display(),
        );
    }
    assert!(
        unrelated.join(".kast/state").is_file(),
        "unregistered state outside the ancestor chain was removed"
    );
    assert!(!kast_home.join("state/cache/source-index.db").exists());
    assert!(!kast_home.join("releases/obsolete").exists());
    assert!(kast_home.join("current/libexec/kastctl").is_file());
    assert!(home.join(".local/bin/kast").exists());
    assert_eq!(
        std::fs::read_to_string(unmanaged_control).expect("preserved unmanaged control command"),
        "unmanaged",
    );
    assert!(!home.join(".local/bin/_kastctl").exists());
}

#[test]
fn force_setup_archives_replaced_unmanaged_kast_instead_of_deleting_it() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("managed user command");
    std::fs::write(&user_command, "unmanaged").expect("replacement user command");

    let output = setup_command(&home, &kast_home, &source)
        .arg("--force")
        .output()
        .expect("forced setup");

    assert!(
        output.status.success(),
        "forced setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let archives = legacy_kast_archives(&kast_home);
    assert_eq!(archives.len(), 1);
    assert_eq!(
        std::fs::read_to_string(&archives[0]).expect("archived unmanaged command"),
        "unmanaged",
    );
    assert_eq!(
        std::fs::read_link(user_command).expect("managed user command"),
        kast_home.join("current/bin/kast"),
    );
}

#[test]
fn repeated_normal_and_force_setup_preserve_every_unmanaged_kast_archive() {
    for force in [false, true] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let kast_home = home.join(".local/share/kast");
        let source = write_install_bundle_source(temp.path(), "v9.8.7");
        assert!(setup(&home, &kast_home, &source).status.success());
        let user_command = home.join(".local/bin/kast");
        std::fs::remove_file(&user_command).expect("first managed user command");
        std::fs::write(&user_command, "first unmanaged").expect("first unmanaged command");
        assert!(setup(&home, &kast_home, &source).status.success());
        std::fs::remove_file(&user_command).expect("second managed user command");
        std::fs::write(&user_command, "second unmanaged").expect("second unmanaged command");

        let mut command = setup_command(&home, &kast_home, &source);
        if force {
            command.arg("--force");
        }
        let output = command.output().expect("repeated setup");

        assert!(
            output.status.success(),
            "repeated force={force} setup should succeed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let contents = legacy_kast_archives(&kast_home)
            .iter()
            .map(|path| std::fs::read_to_string(path).expect("archived command"))
            .collect::<std::collections::BTreeSet<_>>();
        assert_eq!(
            contents,
            std::collections::BTreeSet::from([
                "first unmanaged".to_string(),
                "second unmanaged".to_string(),
            ]),
            "force={force}",
        );
    }
}

#[test]
fn force_path_projection_race_preserves_an_unmanaged_kastctl_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let barrier = temp.path().join("force-path-projection-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .arg("--force")
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-remove",
        )
        .spawn()
        .expect("spawn forced setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-control-remove");
    let control = home.join(".local/bin/kastctl");
    std::fs::remove_file(&control).expect("replace owned control projection");
    std::fs::write(&control, "unmanaged").expect("late unmanaged control command");
    release_setup_barrier(&barrier, "before-control-remove");

    let output = child.wait_with_output().expect("forced setup output");

    assert!(
        !output.status.success(),
        "force must fail closed after ownership changes: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(control).expect("preserved unmanaged control command"),
        "unmanaged",
    );
}

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

    assert_eq!(interrupted.status.code(), Some(86), "injected force crash must stop setup");
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
    assert!(!interrupted.status.success(), "injected force failure must stop setup");
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
