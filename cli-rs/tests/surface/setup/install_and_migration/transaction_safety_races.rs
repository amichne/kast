#[test]
fn same_release_rollback_preserves_a_concurrent_previous_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    std::fs::remove_file(home.join(".local/bin/kast")).expect("remove agent projection");
    std::fs::remove_file(kast_home.join("current/libexec/kastctl"))
        .expect("force same-release activation");
    let barrier = temp.path().join("previous-rollback-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-agent-rollback-validation",
        )
        .spawn()
        .expect("setup with previous rollback barrier");
    wait_for_setup_barrier(&mut child, &barrier, "after-agent-rollback-validation");
    let previous = kast_home.join("previous");
    std::fs::remove_file(&previous).expect("replace published previous");
    std::fs::write(&previous, "operator previous\n").expect("operator replacement");
    release_setup_barrier(&barrier, "after-agent-rollback-validation");

    let failed = child.wait_with_output().expect("failed setup output");

    assert!(!failed.status.success(), "injected setup failure must fail");
    assert_eq!(
        std::fs::read_to_string(previous).expect("preserved previous replacement"),
        "operator previous\n",
    );
}

#[test]
fn config_migration_preserves_a_public_replacement_after_exchange() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(&config, "[runtime]\ndefaultBackend = \"idea\"\n")
        .expect("legacy config");
    let barrier = temp.path().join("public-config-exchange-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-current-config-migration-exchange-before-validation",
        )
        .spawn()
        .expect("setup with migration barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-current-config-migration-exchange-before-validation",
    );
    let observed = temp.path().join("observed-migrated-config");
    std::fs::rename(&config, &observed).expect("observe migrated config");
    std::fs::write(&config, "operator config\n").expect("operator config");
    release_setup_barrier(
        &barrier,
        "after-current-config-migration-exchange-before-validation",
    );

    let failed = child.wait_with_output().expect("failed setup output");

    assert!(!failed.status.success(), "changed public config must fail");
    assert_eq!(
        std::fs::read_to_string(config).expect("preserved public config"),
        "operator config\n",
    );
    assert!(!std::fs::read_to_string(observed)
        .expect("observed migrated config")
        .contains("defaultBackend"));
}

#[test]
fn activation_publication_preserves_a_concurrent_current_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first).status.success());
    let barrier = temp.path().join("current-publication-barrier");
    let mut child = setup_command(&home, &kast_home, &second)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-current-activation-publication",
        )
        .spawn()
        .expect("setup with current publication barrier");
    wait_for_setup_barrier(&mut child, &barrier, "before-current-activation-publication");
    let current = kast_home.join("current");
    std::fs::remove_file(&current).expect("replace prior current");
    std::fs::write(&current, "operator current\n").expect("operator current");
    release_setup_barrier(&barrier, "before-current-activation-publication");

    let failed = child.wait_with_output().expect("failed setup output");

    assert!(!failed.status.success(), "changed current must fail activation");
    assert_eq!(
        std::fs::read_to_string(current).expect("preserved current"),
        "operator current\n",
    );
}

#[test]
fn activation_publication_preserves_a_concurrent_previous_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first).status.success());
    let barrier = temp.path().join("previous-publication-barrier");
    let mut child = setup_command(&home, &kast_home, &second)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-previous-activation-publication",
        )
        .spawn()
        .expect("setup with previous publication barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "before-previous-activation-publication",
    );
    let previous = kast_home.join("previous");
    assert!(std::fs::symlink_metadata(&previous).is_err());
    std::fs::write(&previous, "operator previous\n").expect("operator previous");
    release_setup_barrier(&barrier, "before-previous-activation-publication");

    let failed = child.wait_with_output().expect("failed setup output");

    assert!(!failed.status.success(), "changed previous must fail activation");
    assert_eq!(
        std::fs::read_to_string(previous).expect("preserved previous"),
        "operator previous\n",
    );
}

#[test]
fn activation_rollback_preserves_a_changed_candidate_child() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first).status.success());
    let prior_release =
        std::fs::canonicalize(kast_home.join("current")).expect("prior release");
    std::fs::remove_file(home.join(".local/bin/kast")).expect("remove agent projection");
    let barrier = temp.path().join("candidate-child-rollback-barrier");
    let mut child = setup_command(&home, &kast_home, &second)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT",
            "before-receipt-write",
        )
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-agent-rollback-validation",
        )
        .spawn()
        .expect("setup with candidate rollback barrier");
    wait_for_setup_barrier(&mut child, &barrier, "after-agent-rollback-validation");
    let candidate =
        std::fs::canonicalize(kast_home.join("current")).expect("published candidate");
    let candidate_config = candidate.join("config/config.toml");
    std::fs::write(&candidate_config, "operator candidate config\n")
        .expect("change candidate child");
    release_setup_barrier(&barrier, "after-agent-rollback-validation");

    let failed = child.wait_with_output().expect("failed setup output");

    assert!(!failed.status.success(), "injected setup failure must fail");
    let error: serde_json::Value =
        serde_json::from_slice(&failed.stdout).expect("typed setup failure");
    let quarantine = std::path::Path::new(
        error["details"]["candidateQuarantine"]
            .as_str()
            .expect("candidate quarantine"),
    );
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("restored prior release"),
        prior_release,
    );
    assert_eq!(
        std::fs::read_to_string(quarantine.join("config/config.toml"))
            .expect("preserved changed candidate"),
        "operator candidate config\n",
    );
    assert!(!candidate_config.exists(), "candidate moved to quarantine");
}

#[cfg(unix)]
#[test]
fn activation_candidate_publication_preserves_a_concurrent_destination() {
    use std::os::unix::fs::MetadataExt;

    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first = write_install_bundle_source(temp.path(), "v9.8.7");
    let second = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first).status.success());
    let prior_release =
        std::fs::canonicalize(kast_home.join("current")).expect("prior release");
    let barrier = temp.path().join("candidate-publication-barrier");
    let mut child = setup_command(&home, &kast_home, &second)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-bundle-candidate-publication",
        )
        .spawn()
        .expect("setup with candidate publication barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "before-bundle-candidate-publication",
    );
    let staged = std::fs::read_dir(kast_home.join("staging"))
        .expect("staging directory")
        .next()
        .expect("staged candidate")
        .expect("staged candidate entry")
        .path();
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(staged.join("receipt.json")).expect("staged receipt"),
    )
    .expect("staged receipt JSON");
    let destination = kast_home.join("releases").join(
        receipt["releaseDigest"]
            .as_str()
            .expect("staged release digest"),
    );
    std::fs::create_dir(&destination).expect("concurrent destination");
    let destination_metadata = std::fs::symlink_metadata(&destination)
        .expect("concurrent destination metadata");
    let destination_identity = (destination_metadata.dev(), destination_metadata.ino());
    release_setup_barrier(&barrier, "before-bundle-candidate-publication");

    let failed = child.wait_with_output().expect("failed setup output");

    assert!(!failed.status.success(), "occupied destination must fail setup");
    let preserved_metadata =
        std::fs::symlink_metadata(&destination).expect("preserved operator destination");
    assert_eq!(
        (preserved_metadata.dev(), preserved_metadata.ino()),
        destination_identity,
    );
    assert_eq!(
        std::fs::canonicalize(kast_home.join("current")).expect("restored prior release"),
        prior_release,
    );
}

#[test]
fn same_release_archive_preserves_a_concurrent_pid_named_destination() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let current_release =
        std::fs::canonicalize(kast_home.join("current")).expect("current release");
    let release_digest = current_release.file_name().expect("release digest");
    std::fs::remove_file(kast_home.join("current/libexec/kastctl"))
        .expect("force same-release activation");
    let agent = home.join(".local/bin/kast");
    std::fs::remove_file(&agent).expect("managed agent projection");
    std::fs::write(&agent, "operator agent\n").expect("operator agent");
    let barrier = temp.path().join("same-release-archive-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-legacy-archive-validation",
        )
        .spawn()
        .expect("same-release setup at archive barrier");
    wait_for_setup_barrier(&mut child, &barrier, "after-legacy-archive-validation");
    let collision = kast_home.join("backups").join(format!(
        "{}-replaced-{}",
        release_digest.to_string_lossy(),
        child.id(),
    ));
    std::fs::create_dir_all(&collision).expect("concurrent archive destination");
    let marker = collision.join("operator-marker");
    std::fs::write(&marker, "operator archive\n").expect("operator marker");
    release_setup_barrier(&barrier, "after-legacy-archive-validation");

    let output = child.wait_with_output().expect("setup output");

    assert!(
        output.status.success(),
        "setup should select another archive path: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(marker).expect("preserved operator marker"),
        "operator archive\n",
    );
}

#[test]
fn legacy_current_archive_preserves_a_concurrent_pid_named_destination() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let legacy_current = kast_home.join("current");
    std::fs::create_dir_all(&legacy_current).expect("legacy current");
    std::fs::write(legacy_current.join("legacy-marker"), "legacy\n")
        .expect("legacy current marker");
    let agent = home.join(".local/bin/kast");
    std::fs::create_dir_all(agent.parent().expect("agent parent")).expect("agent parent");
    std::fs::write(&agent, "operator agent\n").expect("operator agent");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let barrier = temp.path().join("legacy-current-archive-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-legacy-archive-validation",
        )
        .spawn()
        .expect("legacy-current setup at archive barrier");
    wait_for_setup_barrier(&mut child, &barrier, "after-legacy-archive-validation");
    let collision = kast_home
        .join("backups")
        .join(format!("legacy-current-{}", child.id()));
    std::fs::create_dir_all(&collision).expect("concurrent legacy archive destination");
    let marker = collision.join("operator-marker");
    std::fs::write(&marker, "operator archive\n").expect("operator marker");
    release_setup_barrier(&barrier, "after-legacy-archive-validation");

    let output = child.wait_with_output().expect("setup output");

    assert!(
        output.status.success(),
        "setup should select another archive path: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(marker).expect("preserved operator marker"),
        "operator archive\n",
    );
}
