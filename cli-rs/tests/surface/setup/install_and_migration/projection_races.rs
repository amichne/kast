#[test]
fn development_setup_does_not_overwrite_unmanaged_kastctl_created_after_inspection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let barrier = temp.path().join("path-projection-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-create",
        )
        .spawn()
        .expect("spawn development setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-control-create");
    let control = home.join(".local/bin/kastctl");
    std::fs::write(&control, "unmanaged").expect("late unmanaged kastctl");
    release_setup_barrier(&barrier, "before-control-create");

    let output = child.wait_with_output().expect("development setup output");

    assert!(
        !output.status.success(),
        "late unmanaged kastctl must fail closed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(control).expect("preserved late unmanaged kastctl"),
        "unmanaged",
    );
    assert!(
        std::fs::symlink_metadata(home.join(".local/bin/kast")).is_err(),
        "failed initial setup must not leave a dangling kast projection",
    );
}

#[test]
fn development_create_restores_a_temporary_replacement_after_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let barrier = temp.path().join("control-create-validation-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-create",
        )
        .spawn()
        .expect("spawn development setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-control-create");
    let temporary_paths = control_create_temporaries(&home.join(".local/bin"));
    assert_eq!(temporary_paths.len(), 1);
    let temporary_path = &temporary_paths[0];
    let captured_projection = temporary_path.with_file_name(format!(
        "{}.captured",
        temporary_path
            .file_name()
            .expect("temporary file name")
            .to_string_lossy(),
    ));
    let expected_target = kast_home.join("current/libexec/kastctl");
    assert_eq!(
        std::fs::read_link(temporary_path).expect("validated temporary projection"),
        expected_target,
    );
    std::fs::rename(temporary_path, &captured_projection)
        .expect("preserve validated temporary projection");
    std::fs::write(temporary_path, "late unproven").expect("replacement temporary path");
    release_setup_barrier(&barrier, "before-control-create");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "changed temporary path must fail closed"
    );
    assert!(
        std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err(),
        "an unproven temporary object must not remain public",
    );
    assert_eq!(
        std::fs::read_to_string(temporary_path).expect("restored temporary replacement"),
        "late unproven",
    );
    assert_eq!(
        std::fs::read_link(&captured_projection).expect("preserved validated projection"),
        expected_target,
    );
    assert!(kast_home.join("path-projection-transaction.json").is_file());
}

#[test]
fn path_projection_standard_setup_does_not_delete_unmanaged_kastctl_created_after_inspection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let barrier = temp.path().join("path-projection-remove-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-remove",
        )
        .spawn()
        .expect("spawn standard setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-control-remove");
    let control = home.join(".local/bin/kastctl");
    std::fs::remove_file(&control).expect("replace owned kastctl");
    std::fs::write(&control, "unmanaged").expect("late unmanaged kastctl");
    release_setup_barrier(&barrier, "before-control-remove");

    let output = child.wait_with_output().expect("standard setup output");

    assert!(
        !output.status.success(),
        "late unmanaged kastctl must fail closed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(control).expect("preserved late unmanaged kastctl"),
        "unmanaged",
    );
}

#[test]
fn path_projection_cleanup_preserves_a_quarantine_replaced_after_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let barrier = temp.path().join("control-cleanup-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-internal-cleanup",
        )
        .spawn()
        .expect("spawn standard setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-control-internal-cleanup");
    let local_bin = home.join(".local/bin");
    let quarantine = std::fs::read_dir(&local_bin)
        .expect("local bin directory")
        .map(|entry| entry.expect("local bin entry").path())
        .find(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with("kastctl.kast-remove-"))
        })
        .expect("control quarantine");
    std::fs::remove_file(&quarantine).expect("replace validated quarantine");
    std::fs::write(&quarantine, "unmanaged").expect("unmanaged quarantine replacement");
    release_setup_barrier(&barrier, "before-control-internal-cleanup");

    let output = child.wait_with_output().expect("standard setup output");

    assert!(
        !output.status.success(),
        "changed quarantine must fail closed",
    );
    assert_eq!(
        std::fs::read_to_string(&quarantine).expect("preserved quarantine replacement"),
        "unmanaged",
    );
    assert!(kast_home.join("path-projection-transaction.json").is_file());
}

#[test]
fn path_projection_rollback_does_not_delete_an_unmanaged_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let barrier = temp.path().join("path-projection-restore-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-control-restore",
        )
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .spawn()
        .expect("spawn failing development setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-control-restore");
    let control = home.join(".local/bin/kastctl");
    std::fs::remove_file(&control).expect("replace projected kastctl");
    std::fs::write(&control, "unmanaged").expect("unmanaged replacement");
    release_setup_barrier(&barrier, "before-control-restore");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "injected receipt failure must fail setup"
    );
    assert_eq!(
        std::fs::read_to_string(control).expect("preserved unmanaged replacement"),
        "unmanaged",
    );
}

#[test]
fn control_create_rollback_preserves_a_replacement_after_identity_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let barrier = temp
        .path()
        .join("control-create-rollback-validation-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-control-create-rollback-validation",
        )
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .spawn()
        .expect("spawn failing development setup");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-control-create-rollback-validation",
    );
    let control = home.join(".local/bin/kastctl");
    std::fs::remove_file(&control).expect("replace validated control projection");
    std::fs::write(&control, "late unmanaged").expect("late control replacement");
    release_setup_barrier(&barrier, "after-control-create-rollback-validation");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "injected receipt failure must fail setup"
    );
    assert_eq!(
        std::fs::read_to_string(&control).expect("preserved control replacement"),
        "late unmanaged",
    );
    assert!(
        control_create_temporaries(&home.join(".local/bin")).is_empty(),
        "no unmanaged replacement may be stranded on an internal create path",
    );
    assert!(kast_home.join("path-projection-transaction.json").is_file());
}

#[test]
fn agent_create_does_not_adopt_or_remove_a_replacement_before_identity_capture() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let agent = home.join(".local/bin/kast");
    let barrier = temp.path().join("agent-create-identity-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-projection-identity-capture",
        )
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_PATH",
            &agent,
        )
        .spawn()
        .expect("spawn setup at agent identity barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "before-projection-identity-capture",
    );
    if std::fs::symlink_metadata(&agent).is_ok() {
        std::fs::remove_file(&agent).expect("replace provisional agent projection");
    }
    std::fs::write(&agent, "operator agent\n").expect("operator agent replacement");
    release_setup_barrier(&barrier, "before-projection-identity-capture");

    let output = child.wait_with_output().expect("setup output");

    assert!(
        !output.status.success(),
        "setup must not adopt an operator replacement: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(agent).expect("preserved operator agent"),
        "operator agent\n",
    );
}

#[test]
fn agent_create_preserves_a_replacement_after_publication_before_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let agent = home.join(".local/bin/kast");
    let observed_projection = temp.path().join("observed-agent-projection");
    let barrier = temp.path().join("agent-post-publication-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-agent-create-publication-before-validation",
        )
        .spawn()
        .expect("setup at agent post-publication barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-agent-create-publication-before-validation",
    );
    std::fs::rename(&agent, &observed_projection).expect("observe published projection");
    std::fs::write(&agent, "operator agent\n").expect("operator replacement");
    release_setup_barrier(
        &barrier,
        "after-agent-create-publication-before-validation",
    );

    let output = child.wait_with_output().expect("setup output");

    assert!(!output.status.success(), "changed publication must fail setup");
    assert_eq!(
        std::fs::read_to_string(agent).expect("operator replacement at public path"),
        "operator agent\n",
    );
    assert!(observed_projection.is_symlink());
}
