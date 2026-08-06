#[test]
fn setup_rollback_preserves_late_unmanaged_kast_and_archived_original() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let local_bin = home.join(".local/bin");
    std::fs::create_dir_all(&local_bin).expect("local bin directory");
    let user_command = local_bin.join("kast");
    std::fs::write(&user_command, "original unmanaged").expect("original unmanaged command");
    let barrier = temp.path().join("legacy-restore-barrier");
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
    std::fs::remove_file(&user_command).expect("replace projected kast");
    std::fs::write(&user_command, "late unmanaged").expect("late unmanaged command");
    release_setup_barrier(&barrier, "before-control-restore");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "injected receipt failure must fail setup"
    );
    assert_eq!(
        std::fs::read_to_string(&user_command).expect("preserved late unmanaged command"),
        "late unmanaged",
    );
    let archives = legacy_kast_archives(&kast_home);
    assert_eq!(archives.len(), 1);
    assert_eq!(
        std::fs::read_to_string(&archives[0]).expect("preserved archived original"),
        "original unmanaged",
    );
}

#[test]
fn legacy_restore_preserves_a_backup_replaced_after_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let local_bin = home.join(".local/bin");
    std::fs::create_dir_all(&local_bin).expect("local bin directory");
    let user_command = local_bin.join("kast");
    std::fs::write(&user_command, "original unmanaged").expect("original unmanaged command");
    let barrier = temp.path().join("legacy-restore-move-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-legacy-restore-move",
        )
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .spawn()
        .expect("spawn failing development setup");
    wait_for_setup_barrier(&mut child, &barrier, "before-legacy-restore-move");
    let archives = legacy_kast_archives(&kast_home);
    assert_eq!(archives.len(), 1);
    let backup = &archives[0];
    let captured_backup = backup.with_extension("captured");
    std::fs::rename(backup, &captured_backup).expect("preserve validated backup");
    std::fs::write(backup, "late backup replacement").expect("replacement backup");
    release_setup_barrier(&barrier, "before-legacy-restore-move");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "injected setup failure must remain failed"
    );
    assert!(
        std::fs::symlink_metadata(&user_command).is_err(),
        "replacement backup must not be moved onto the public command",
    );
    assert_eq!(
        std::fs::read_to_string(backup).expect("preserved replacement backup"),
        "late backup replacement",
    );
    assert_eq!(
        std::fs::read_to_string(captured_backup).expect("preserved validated backup"),
        "original unmanaged",
    );
}

#[test]
fn legacy_archive_restores_a_source_replaced_after_final_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let local_bin = home.join(".local/bin");
    std::fs::create_dir_all(&local_bin).expect("local bin directory");
    let user_command = local_bin.join("kast");
    std::fs::write(&user_command, "original unmanaged").expect("original unmanaged command");
    let preserved_original = local_bin.join("kast.original");
    let barrier = temp.path().join("legacy-archive-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-legacy-archive-validation",
        )
        .spawn()
        .expect("spawn setup");
    wait_for_setup_barrier(&mut child, &barrier, "after-legacy-archive-validation");
    std::fs::rename(&user_command, &preserved_original).expect("preserve captured source");
    std::fs::write(&user_command, "late unmanaged").expect("replacement source");
    release_setup_barrier(&barrier, "after-legacy-archive-validation");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "changed archive source must fail closed"
    );
    assert_eq!(
        std::fs::read_to_string(&user_command).expect("preserved replacement"),
        "late unmanaged",
    );
    assert_eq!(
        std::fs::read_to_string(&preserved_original).expect("preserved captured source"),
        "original unmanaged",
    );
    assert!(legacy_kast_archives(&kast_home).is_empty());
}
