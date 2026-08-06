#[test]
fn different_release_migration_rejects_a_config_change_before_promotion() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first_source).status.success());
    let prior_release = std::fs::canonicalize(kast_home.join("current"))
        .expect("prior current release");
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(
        &config,
        "# captured configuration\n[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("legacy configuration");
    let agent_command = home.join(".local/bin/kast");
    std::fs::remove_file(&agent_command).expect("managed agent projection");
    std::fs::write(&agent_command, "unmanaged command\n").expect("unmanaged agent command");
    let barrier = temp.path().join("upgrade-config-promotion-barrier");
    let mut child = setup_command(&home, &kast_home, &second_source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-legacy-archive-validation",
        )
        .spawn()
        .expect("spawn upgrade with migration plan");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-legacy-archive-validation",
    );
    let operator_contents = b"# operator replacement\n[server]\nmaxResults = 17\n";
    std::fs::remove_file(&config).expect("remove captured configuration");
    std::fs::write(&config, operator_contents).expect("operator replacement");
    release_setup_barrier(&barrier, "after-legacy-archive-validation");

    let failed = child.wait_with_output().expect("failed upgrade output");

    assert!(
        !failed.status.success(),
        "upgrade must reject stale configuration authority: stdout={}, stderr={}",
        String::from_utf8_lossy(&failed.stdout),
        String::from_utf8_lossy(&failed.stderr),
    );
    let error: serde_json::Value =
        serde_json::from_slice(&failed.stdout).expect("typed setup failure");
    assert_eq!(error["code"], "SETUP_CONFIG_MIGRATION_CONFLICT");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("preserved current release"),
        prior_release,
    );
    assert_eq!(
        std::fs::read(&config).expect("preserved operator configuration"),
        operator_contents,
    );
    assert_eq!(
        std::fs::read_to_string(agent_command).expect("restored unmanaged agent command"),
        "unmanaged command\n",
    );
}

#[test]
fn different_release_migration_rolls_back_when_source_changes_during_publication() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first_source).status.success());
    let prior_release =
        std::fs::canonicalize(kast_home.join("current")).expect("prior current release");
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(
        &config,
        "# captured configuration\n[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("legacy configuration");
    let barrier = temp.path().join("upgrade-config-publication-barrier");
    let mut child = setup_command(&home, &kast_home, &second_source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-current-activation-publication",
        )
        .spawn()
        .expect("spawn upgrade with migration plan");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "before-current-activation-publication",
    );
    let operator_contents = b"# operator replacement\n[server]\nmaxResults = 29\n";
    std::fs::remove_file(&config).expect("remove captured configuration");
    std::fs::write(&config, operator_contents).expect("operator replacement");
    release_setup_barrier(&barrier, "before-current-activation-publication");

    let failed = child.wait_with_output().expect("failed upgrade output");

    assert!(
        !failed.status.success(),
        "upgrade must reject a source change during publication: stdout={}, stderr={}",
        String::from_utf8_lossy(&failed.stdout),
        String::from_utf8_lossy(&failed.stderr),
    );
    let error: serde_json::Value =
        serde_json::from_slice(&failed.stdout).expect("typed setup failure");
    assert_eq!(error["code"], "SETUP_CONFIG_MIGRATION_CONFLICT");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("restored current release"),
        prior_release,
    );
    assert_eq!(
        std::fs::read(prior_release.join("config/config.toml"))
            .expect("preserved operator configuration"),
        operator_contents,
    );
}

#[cfg(unix)]
#[test]
fn different_release_migration_preserves_the_exact_config_mode() {
    use std::os::unix::fs::PermissionsExt;

    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first_source).status.success());
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(
        &config,
        "# preserve mode\n[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("legacy configuration");
    std::fs::set_permissions(&config, std::fs::Permissions::from_mode(0o400))
        .expect("restrict configuration");

    let upgraded = setup(&home, &kast_home, &second_source);

    assert!(
        upgraded.status.success(),
        "upgrade must succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&upgraded.stdout),
        String::from_utf8_lossy(&upgraded.stderr),
    );
    let migrated = std::fs::read_to_string(&config).expect("migrated configuration");
    assert!(!migrated.contains("defaultBackend"));
    assert!(migrated.contains("# preserve mode"));
    assert_eq!(
        std::fs::metadata(config)
            .expect("configuration metadata")
            .permissions()
            .mode()
            & 0o7777,
        0o400,
    );
}

#[test]
fn failed_third_release_restores_the_prior_previous_target() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    let third = write_install_bundle_source(temp.path(), "v9.8.9");
    assert!(setup(&home, &kast_home, &first).status.success());
    assert!(setup(&home, &kast_home, &second).status.success());
    let prior_current =
        std::fs::canonicalize(kast_home.join("current")).expect("prior current release");
    let prior_previous_target =
        std::fs::read_link(kast_home.join("previous")).expect("prior previous target");

    let failed = setup_command(&home, &kast_home, &third)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .output()
        .expect("failed third release setup");

    assert!(!failed.status.success(), "injected setup failure must fail");
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("restored current release"),
        prior_current,
    );
    assert_eq!(
        std::fs::read_link(kast_home.join("previous")).expect("restored previous target"),
        prior_previous_target,
    );
}

#[test]
fn different_release_archive_preserves_an_occupied_digest_backup() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first).status.success());
    let first_release =
        std::fs::canonicalize(kast_home.join("current")).expect("first release");
    let occupied = kast_home
        .join("backups")
        .join(first_release.file_name().expect("first release digest"));
    std::fs::create_dir_all(&occupied).expect("occupied digest backup");
    let marker = occupied.join("operator-marker");
    std::fs::write(&marker, "operator backup\n").expect("operator backup marker");

    let output = setup(&home, &kast_home, &second);

    assert!(
        output.status.success(),
        "setup should select another backup path: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("setup result JSON");
    let backup = std::path::Path::new(result["backup"].as_str().expect("reported backup"));
    assert_ne!(backup, occupied);
    assert_eq!(
        std::fs::canonicalize(backup).expect("reachable release backup"),
        first_release,
    );
    assert_eq!(
        std::fs::read_to_string(marker).expect("preserved operator marker"),
        "operator backup\n",
    );
}

#[test]
fn candidate_move_preserves_a_replacement_after_publication() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first).status.success());
    let prior_release =
        std::fs::canonicalize(kast_home.join("current")).expect("prior release");
    let barrier = temp.path().join("candidate-post-publication-barrier");
    let mut child = setup_command(&home, &kast_home, &second)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-bundle-candidate-publication-before-validation",
        )
        .spawn()
        .expect("setup at candidate post-publication barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-bundle-candidate-publication-before-validation",
    );
    let candidate = std::fs::read_dir(kast_home.join("releases"))
        .expect("release directory")
        .map(|entry| entry.expect("release entry").path())
        .find(|path| std::fs::canonicalize(path).ok().as_ref() != Some(&prior_release))
        .expect("published candidate");
    let observed_candidate = temp.path().join("observed-candidate");
    std::fs::rename(&candidate, &observed_candidate).expect("observe published candidate");
    std::fs::create_dir(&candidate).expect("operator candidate replacement");
    let marker = candidate.join("operator-marker");
    std::fs::write(&marker, "operator candidate\n").expect("operator marker");
    release_setup_barrier(
        &barrier,
        "after-bundle-candidate-publication-before-validation",
    );

    let output = child.wait_with_output().expect("setup output");

    assert!(!output.status.success(), "changed publication must fail setup");
    assert_eq!(
        std::fs::read_to_string(marker).expect("operator replacement at destination"),
        "operator candidate\n",
    );
    assert!(observed_candidate.join("bin/kast").is_file());
}

#[test]
fn archive_move_preserves_a_replacement_after_publication() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    std::fs::remove_file(kast_home.join("current/libexec/kastctl"))
        .expect("force same-release activation");
    let barrier = temp.path().join("archive-post-publication-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-current-archive-publication-before-validation",
        )
        .spawn()
        .expect("setup at archive post-publication barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-current-archive-publication-before-validation",
    );
    let archive = std::fs::read_dir(kast_home.join("backups"))
        .expect("backup directory")
        .map(|entry| entry.expect("backup entry").path())
        .find(|path| path.join("bin/kast").is_file())
        .expect("published release archive");
    let observed_archive = temp.path().join("observed-archive");
    std::fs::rename(&archive, &observed_archive).expect("observe published archive");
    std::fs::create_dir(&archive).expect("operator archive replacement");
    let marker = archive.join("operator-marker");
    std::fs::write(&marker, "operator archive\n").expect("operator marker");
    release_setup_barrier(
        &barrier,
        "after-current-archive-publication-before-validation",
    );

    let output = child.wait_with_output().expect("setup output");

    assert!(!output.status.success(), "changed publication must fail setup");
    assert_eq!(
        std::fs::read_to_string(marker).expect("operator replacement at archive path"),
        "operator archive\n",
    );
    assert!(observed_archive.join("bin/kast").is_file());
}
