use std::process::Stdio;
use support::*;

fn setup_command(home: &Path, kast_home: &Path, source: &Path) -> Command {
    let mut command = kast(home, &kast_home.join("unused-config"));
    command
        .env_remove("KAST_CONFIG_HOME")
        .env("KAST_HOME", kast_home)
        .args([
            "--output",
            "json",
            "setup",
            "--source",
            source.to_str().expect("bundle source"),
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    command
}

fn setup(home: &Path, kast_home: &Path, source: &Path) -> std::process::Output {
    setup_command(home, kast_home, source)
        .output()
        .expect("kast setup")
}

fn setup_with_profile(
    home: &Path,
    kast_home: &Path,
    source: &Path,
    profile: &str,
) -> std::process::Output {
    setup_command(home, kast_home, source)
        .args(["--profile", profile])
        .output()
        .expect("profiled kast setup")
}

fn wait_for_setup_barrier(
    child: &mut std::process::Child,
    barrier_directory: &Path,
    stage: &str,
) {
    let ready = barrier_directory.join(format!("{stage}.ready"));
    for _ in 0..3_000 {
        if ready.is_file() {
            return;
        }
        if let Some(status) = child.try_wait().expect("setup process state") {
            panic!("setup exited before barrier `{stage}` with {status}");
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    panic!("setup did not reach barrier `{stage}`");
}

fn release_setup_barrier(barrier_directory: &Path, stage: &str) {
    std::fs::write(
        barrier_directory.join(format!("{stage}.continue")),
        "continue\n",
    )
    .expect("release setup barrier");
}

fn setup_with_durability_failure(
    home: &Path,
    kast_home: &Path,
    source: &Path,
    profile: &str,
    failure_point: &str,
) -> std::process::Output {
    setup_command(home, kast_home, source)
        .args(["--profile", profile])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_DURABILITY_FAILURE_POINT",
            failure_point,
        )
        .output()
        .expect("setup with durability failure")
}

fn control_create_temporaries(local_bin: &Path) -> Vec<PathBuf> {
    let mut paths = std::fs::read_dir(local_bin)
        .expect("local bin directory")
        .map(|entry| entry.expect("local bin entry").path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with("kastctl.kast-create-"))
        })
        .collect::<Vec<_>>();
    paths.sort();
    paths
}

fn legacy_kast_archives(kast_home: &Path) -> Vec<PathBuf> {
    let backups = kast_home.join("backups");
    let mut paths = std::fs::read_dir(backups)
        .expect("backup directory")
        .map(|entry| entry.expect("backup entry").path())
        .filter(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with("legacy-local-bin-kast-"))
        })
        .collect::<Vec<_>>();
    paths.sort();
    paths
}

fn projection_identity_json(path: &Path) -> serde_json::Value {
    use std::os::unix::fs::MetadataExt;
    let metadata = std::fs::symlink_metadata(path).expect("projection identity metadata");
    let kind = if metadata.file_type().is_symlink() {
        "SYMLINK"
    } else if metadata.file_type().is_file() {
        "FILE"
    } else if metadata.file_type().is_dir() {
        "DIRECTORY"
    } else {
        "OTHER"
    };
    serde_json::json!({
        "device": metadata.dev(),
        "inode": metadata.ino(),
        "kind": kind,
    })
}

#[test]
fn setup_durability_agent_projection_precedes_receipt_publication() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    let interrupted = setup_with_durability_failure(
        &home,
        &kast_home,
        &source,
        "development",
        "after-agent-create-before-parent-sync",
    );

    assert!(
        !interrupted.status.success(),
        "setup must not publish its receipt before the agent projection directory is durable",
    );
    assert!(
        !kast_home.join("current/receipt.json").exists(),
        "failed initial setup must not retain a published receipt",
    );
    assert!(
        std::fs::symlink_metadata(home.join(".local/bin/kast")).is_err(),
        "failed initial setup must remove its exact unsynced agent projection",
    );
    let recovered = setup_with_profile(&home, &kast_home, &source, "development");
    assert!(
        recovered.status.success(),
        "a retry must durably recover the agent projection: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
}

#[test]
fn agent_projection_sync_failure_preserves_a_concurrent_replacement() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let barrier = temp.path().join("agent-projection-cleanup-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_DURABILITY_FAILURE_POINT",
            "after-agent-create-before-parent-sync",
        )
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-agent-create-failure-cleanup",
        )
        .spawn()
        .expect("spawn setup with agent durability failure");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "before-agent-create-failure-cleanup",
    );
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("replace unsynced agent projection");
    std::fs::write(&user_command, "unmanaged").expect("concurrent replacement");
    release_setup_barrier(&barrier, "before-agent-create-failure-cleanup");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(!output.status.success(), "durability failure must fail setup");
    assert_eq!(
        std::fs::read_to_string(&user_command).expect("preserved concurrent replacement"),
        "unmanaged",
    );
    assert!(!kast_home.join("current").exists());
}

#[test]
fn agent_rollback_preserves_a_replacement_after_identity_validation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let barrier = temp.path().join("agent-rollback-validation-barrier");
    let mut child = setup_command(&home, &kast_home, &source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_DURABILITY_FAILURE_POINT",
            "after-agent-create-before-parent-sync",
        )
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "after-agent-rollback-validation",
        )
        .spawn()
        .expect("spawn setup with agent rollback barrier");
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-agent-rollback-validation",
    );
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("replace validated agent projection");
    std::fs::write(&user_command, "late unmanaged").expect("late agent replacement");
    release_setup_barrier(&barrier, "after-agent-rollback-validation");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(!output.status.success(), "durability failure must fail setup");
    assert_eq!(
        std::fs::read_to_string(&user_command).expect("preserved agent replacement"),
        "late unmanaged",
    );
    assert!(
        std::fs::read_dir(home.join(".local/bin"))
            .expect("local bin directory")
            .map(|entry| entry.expect("local bin entry"))
            .all(|entry| !entry.file_name().to_string_lossy().starts_with("kast.kast-rollback-")),
        "no unmanaged replacement may be stranded on an internal rollback path",
    );
    assert!(!kast_home.join("current").exists());
}

#[test]
fn setup_durability_create_sync_failure_retains_recovery_journal() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());

    let interrupted = setup_with_durability_failure(
        &home,
        &kast_home,
        &source,
        "development",
        "after-control-create-before-parent-sync",
    );

    assert!(
        !interrupted.status.success(),
        "setup must not commit an unsynced control projection",
    );
    assert!(
        kast_home.join("path-projection-transaction.json").is_file(),
        "the recovery journal must remain until the projection directory is durable",
    );
    let recovered = setup(&home, &kast_home, &source);
    assert!(
        recovered.status.success(),
        "standard setup must recover the uncommitted create: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err());
    assert!(!kast_home.join("path-projection-transaction.json").exists());
}

#[test]
fn setup_durability_receipt_sync_failure_retains_recovery_journal() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());

    let interrupted = setup_with_durability_failure(
        &home,
        &kast_home,
        &source,
        "development",
        "after-receipt-rename-before-parent-sync",
    );

    assert!(
        !interrupted.status.success(),
        "setup must not declare an unsynced receipt complete",
    );
    assert!(
        kast_home.join("path-projection-transaction.json").is_file(),
        "the recovery journal must remain until the receipt directory is durable",
    );
    assert!(home.join(".local/bin/kastctl").is_symlink());
    let recovered = setup_with_profile(&home, &kast_home, &source, "development");
    assert!(
        recovered.status.success(),
        "development setup must complete the visible receipt transaction: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(!kast_home.join("path-projection-transaction.json").exists());
}

#[test]
fn visible_receipt_sync_failure_preserves_the_activated_release() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first_source).status.success());

    let interrupted = setup_command(&home, &kast_home, &second_source)
        .args(["--profile", "development"])
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_DURABILITY_FAILURE_POINT",
            "after-receipt-rename-before-parent-sync",
        )
        .env(
            "KAST_TEST_SETUP_DURABILITY_FAILURE_PATH",
            kast_home.join("current/receipt.json"),
        )
        .output()
        .expect("setup with final receipt durability failure");

    assert!(
        !interrupted.status.success(),
        "setup must report the receipt durability failure",
    );
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("visible receipt"),
    )
    .expect("visible receipt JSON");
    assert_eq!(receipt["activeVersion"], "v9.8.8");
    assert_eq!(receipt["setupProfile"], "DEVELOPMENT");
    assert!(home.join(".local/bin/kast").is_symlink());
    assert!(home.join(".local/bin/kastctl").is_symlink());
    assert!(kast_home.join("path-projection-transaction.json").is_file());

    let recovered = setup_with_profile(&home, &kast_home, &second_source, "development");

    assert!(
        recovered.status.success(),
        "retry must finish the visible receipt transaction: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(!kast_home.join("path-projection-transaction.json").exists());
}

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
        let receipt: serde_json::Value = serde_json::from_slice(
            &std::fs::read(&receipt_path).expect("receipt"),
        )
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
                std::os::unix::fs::symlink(&control_target, &protected)
                    .expect("protected symlink");
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
                std::os::unix::fs::symlink(&control_target, &protected)
                    .expect("protected symlink");
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
    let barrier = temp.path().join("control-remove-rollback-validation-barrier");
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
    std::fs::rename(&quarantine, &captured_projection)
        .expect("preserve validated quarantine");
    std::fs::write(&quarantine, "late unmanaged").expect("replacement quarantine");
    release_setup_barrier(
        &barrier,
        "after-control-remove-rollback-validation",
    );

    let output = child.wait_with_output().expect("failed setup output");

    assert!(!output.status.success(), "remove rollback race must fail closed");
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

    assert!(!output.status.success(), "changed temporary path must fail closed");
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

    assert!(!output.status.success(), "injected receipt failure must fail setup");
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
    let barrier = temp.path().join("control-create-rollback-validation-barrier");
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

    assert!(!output.status.success(), "injected receipt failure must fail setup");
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

    assert!(!output.status.success(), "injected receipt failure must fail setup");
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

    assert!(!output.status.success(), "injected setup failure must remain failed");
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
    wait_for_setup_barrier(
        &mut child,
        &barrier,
        "after-legacy-archive-validation",
    );
    std::fs::rename(&user_command, &preserved_original).expect("preserve captured source");
    std::fs::write(&user_command, "late unmanaged").expect("replacement source");
    release_setup_barrier(&barrier, "after-legacy-archive-validation");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(!output.status.success(), "changed archive source must fail closed");
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
    assert!(!interrupted.status.success(), "setup must stop at the crash point");

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
        !kast_home
            .join("path-projection-transaction.json")
            .exists(),
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

    assert!(!interrupted.status.success(), "setup must stop at the crash point");
    assert!(kast_home.join("path-projection-transaction.json").is_file());
    assert_eq!(control_create_temporaries(&home.join(".local/bin")).len(), 1);

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
    assert!(!interrupted.status.success(), "setup must stop at the crash point");
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
    assert!(!interrupted.status.success(), "setup must stop after prepare");
    let journal_path = kast_home.join("path-projection-transaction.json");
    let journal: serde_json::Value = serde_json::from_slice(
        &std::fs::read(&journal_path).expect("projection journal"),
    )
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
    assert!(!interrupted.status.success(), "setup must stop at the crash point");
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
        !kast_home
            .join("path-projection-transaction.json")
            .exists(),
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
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(&receipt_path).expect("published receipt"),
    )
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
fn path_projection_upgrade_retains_ownership_across_an_activation_crash() {
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
    let interrupted = setup_command(&home, &kast_home, &second_source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT",
            "after-bundle-activation",
        )
        .output()
        .expect("interrupted upgrade");
    assert!(!interrupted.status.success(), "upgrade must stop at the crash point");

    let recovered = setup(&home, &kast_home, &second_source);

    assert!(
        recovered.status.success(),
        "recovered upgrade should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr),
    );
    assert!(
        std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err(),
        "stable upgrade must remove the receipt-owned developer projection",
    );
}

#[test]
fn path_projection_unsupported_receipt_schema_is_not_ownership_proof() {
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
    let mut receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(&receipt_path).expect("receipt"),
    )
    .expect("receipt JSON");
    receipt["schemaVersion"] = serde_json::json!(999);
    std::fs::write(
        &receipt_path,
        serde_json::to_vec_pretty(&receipt).expect("receipt JSON"),
    )
    .expect("unsupported receipt");

    let output = setup(&home, &kast_home, &source);

    assert!(output.status.success(), "standard setup may preserve unproven state");
    assert!(
        home.join(".local/bin/kastctl").is_symlink(),
        "unsupported receipt schema must not authorize deletion",
    );
}

#[test]
fn missing_receipt_authority_fields_never_authorize_control_deletion() {
    for missing_field in ["schemaVersion", "tool"] {
        for force in [false, true] {
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
            let receipt_path = kast_home.join("current/receipt.json");
            let mut receipt: serde_json::Value = serde_json::from_slice(
                &std::fs::read(&receipt_path).expect("receipt"),
            )
            .expect("receipt JSON");
            receipt
                .as_object_mut()
                .expect("receipt object")
                .remove(missing_field);
            std::fs::write(
                &receipt_path,
                serde_json::to_vec_pretty(&receipt).expect("receipt JSON"),
            )
            .expect("receipt without authority field");

            let mut command = setup_command(&home, &kast_home, &source);
            if force {
                command.arg("--force");
            }
            let output = command.output().expect("standard setup");

            assert!(
                output.status.success(),
                "missing {missing_field} must be preserved during force={force}: stdout={}, stderr={}",
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr),
            );
            assert_eq!(
                std::fs::read_link(&control).expect("preserved unproven control projection"),
                expected_target,
                "missing {missing_field} with force={force}",
            );
        }
    }
}

#[test]
fn path_projection_in_tree_wrong_target_is_not_ownership_proof() {
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
    let wrong_target = kast_home.join("current/bin/kast");
    std::fs::remove_file(&control).expect("replace control projection");
    std::os::unix::fs::symlink(&wrong_target, &control).expect("wrong in-tree projection");
    let receipt_path = kast_home.join("current/receipt.json");
    let mut receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(&receipt_path).expect("receipt"),
    )
    .expect("receipt JSON");
    let projections = receipt["pathProjections"]
        .as_array_mut()
        .expect("path projections");
    projections
        .iter_mut()
        .find(|projection| projection["command"] == "KASTCTL")
        .expect("control projection")["target"] =
        serde_json::json!(wrong_target.display().to_string());
    std::fs::write(
        &receipt_path,
        serde_json::to_vec_pretty(&receipt).expect("receipt JSON"),
    )
    .expect("wrong-target receipt");

    let output = setup(&home, &kast_home, &source);

    assert!(output.status.success(), "standard setup may preserve unproven state");
    assert_eq!(
        std::fs::read_link(control).expect("preserved wrong-target projection"),
        wrong_target,
    );
}

#[test]
fn path_projection_distinct_version_upgrade_can_enable_development_profile() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v9.8.7");
    let second_source = write_install_bundle_source(temp.path(), "v9.8.8");
    assert!(setup(&home, &kast_home, &first_source).status.success());

    let output = setup_with_profile(&home, &kast_home, &second_source, "development");

    assert!(
        output.status.success(),
        "development upgrade should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kastctl")).expect("developer projection"),
        kast_home.join("current/libexec/kastctl"),
    );
}

#[test]
fn path_projection_distinct_version_upgrade_can_return_to_standard_profile() {
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

    let output = setup(&home, &kast_home, &second_source);

    assert!(
        output.status.success(),
        "standard upgrade should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(std::fs::symlink_metadata(home.join(".local/bin/kastctl")).is_err());
}

#[test]
fn development_setup_projects_both_commands_and_records_receipt_ownership() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    let output = setup_with_profile(&home, &kast_home, &source, "development");

    assert!(
        output.status.success(),
        "development setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let local_bin = home.join(".local/bin");
    assert_eq!(
        std::fs::read_link(local_bin.join("kast")).expect("kast projection"),
        kast_home.join("current/bin/kast"),
    );
    assert_eq!(
        std::fs::read_link(local_bin.join("kastctl")).expect("kastctl projection"),
        kast_home.join("current/libexec/kastctl"),
    );
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    assert_eq!(receipt["setupProfile"], "DEVELOPMENT");
    assert_eq!(
        receipt["pathProjections"],
        serde_json::json!([
            {
                "command": "KAST",
                "path": local_bin.join("kast").display().to_string(),
                "target": kast_home.join("current/bin/kast").display().to_string()
            },
            {
                "command": "KASTCTL",
                "path": local_bin.join("kastctl").display().to_string(),
                "target": kast_home.join("current/libexec/kastctl").display().to_string()
            }
        ]),
    );
}

#[test]
fn development_setup_rejects_unmanaged_kastctl_before_activation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let unmanaged = home.join(".local/bin/kastctl");
    std::fs::create_dir_all(unmanaged.parent().expect("local bin")).expect("local bin");
    std::fs::write(&unmanaged, "unmanaged").expect("unmanaged kastctl");

    let output = setup_with_profile(&home, &kast_home, &source, "development");

    assert!(!output.status.success(), "unmanaged kastctl must block development setup");
    let error: serde_json::Value = serde_json::from_slice(&output.stdout).expect("typed error");
    assert_eq!(error["code"], "PATH_PROJECTION_UNMANAGED");
    assert_eq!(error["details"]["path"], unmanaged.display().to_string());
    assert_eq!(
        std::fs::read_to_string(&unmanaged).expect("preserved unmanaged kastctl"),
        "unmanaged",
    );
    assert!(!kast_home.join("current").exists(), "bundle was not activated");
}

#[test]
fn standard_setup_preserves_unmanaged_kastctl() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let unmanaged = home.join(".local/bin/kastctl");
    std::fs::write(&unmanaged, "unmanaged").expect("unmanaged kastctl");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "standard setup should preserve an unmanaged command: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_to_string(unmanaged).expect("preserved unmanaged kastctl"),
        "unmanaged",
    );
}

#[test]
fn standard_setup_removes_only_receipt_owned_kastctl() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(
        setup_with_profile(&home, &kast_home, &source, "development")
            .status
            .success(),
    );
    let control_projection = home.join(".local/bin/kastctl");
    assert!(control_projection.is_symlink());

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "standard profile transition should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(!control_projection.exists());
    assert!(std::fs::symlink_metadata(control_projection).is_err());
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    assert_eq!(receipt["setupProfile"], "STANDARD");
    assert_eq!(receipt["pathProjections"].as_array().map(Vec::len), Some(1));
}

#[test]
fn forced_development_setup_recreates_its_receipt_owned_projection() {
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
        .args(["--profile", "development", "--force"])
        .output()
        .expect("forced development setup");

    assert!(
        output.status.success(),
        "forced development setup should converge: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_eq!(
        std::fs::read_link(home.join(".local/bin/kastctl")).expect("kastctl projection"),
        kast_home.join("current/libexec/kastctl"),
    );
}

#[test]
fn setup_installs_one_indexer_release_without_a_public_plugin() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let result: serde_json::Value = serde_json::from_slice(&output.stdout).expect("setup result");
    assert_eq!(
        result["artifacts"]
            .as_array()
            .expect("setup artifacts")
            .iter()
            .map(|artifact| artifact["role"].as_str().expect("artifact role"))
            .collect::<Vec<_>>(),
        vec!["cli", "agent-cli", "indexer"],
    );
    assert!(kast_home.join("current/libexec/kastctl").is_file());
    assert_eq!(
        std::fs::read(kast_home.join("current/libexec/kastctl")).expect("kastctl bytes"),
        std::fs::read(kast_home.join("current/bin/kast")).expect("kast bytes"),
    );
    assert!(!kast_home.join("current/plugins").exists());
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(kast_home.join("current/receipt.json")).expect("setup receipt"),
    )
    .expect("setup receipt JSON");
    assert_eq!(
        receipt["components"],
        serde_json::json!(["cli", "indexer", "manifest"]),
    );
    let config = std::fs::read_to_string(kast_home.join("current/config/config.toml"))
        .expect("installed config");
    assert!(!config.contains("defaultBackend"));
}

#[test]
fn ordinary_setup_removes_retired_public_plugins_without_controlling_the_ide() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let idea_plugins =
        home.join("Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins");
    let android_plugins =
        home.join("Library/Application Support/Google/AndroidStudio2026.1/plugins");
    for plugins in [&idea_plugins, &android_plugins] {
        std::fs::create_dir_all(plugins.join("kast/lib")).expect("retired public plugin");
        std::fs::write(plugins.join("kast/lib/plugin.jar"), "retired").expect("plugin payload");
        std::fs::create_dir_all(plugins.join("unrelated")).expect("unrelated plugin");
    }

    let output = setup_command(&home, &kast_home, &source)
        .env("KAST_MACHINE_IDE_STATE", "open")
        .output()
        .expect("kast setup");

    assert!(
        output.status.success(),
        "setup must not require foreground IDE control: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("setup migration result");
    assert_eq!(
        result["restartRequirement"]["code"],
        "FOREGROUND_IDE_RESTART_REQUIRED",
    );
    for plugins in [&idea_plugins, &android_plugins] {
        assert!(!plugins.join("kast").exists(), "retired public plugin removed");
        assert!(plugins.join("unrelated").is_dir(), "unrelated plugin preserved");
    }
}

#[test]
fn ordinary_setup_retires_an_owned_legacy_headless_daemon() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let socket_path = temp.path().join("legacy-headless.sock");
    let listener = UnixListener::bind(&socket_path).expect("legacy runtime socket");
    let server = spawn_legacy_headless_status_server(listener, workspace.clone());
    let (pid, reaped) = spawn_reapable_process();
    let current_socket = temp.path().join("current.sock");
    let _current_listener = UnixListener::bind(&current_socket).expect("current runtime socket");
    let (current_pid, current_reaped) = spawn_reapable_process();
    let preserved = vec![
        runtime_descriptor_for_process_test(
            &workspace,
            &current_socket,
            "indexer",
            "current-test",
            current_pid,
        ),
        serde_json::json!({"futureDescriptor": {"schemaVersion": 999}}),
    ];
    write_legacy_headless_descriptor(&home, &workspace, &socket_path, pid, None, &preserved);

    let output = setup(&home, &kast_home, &source);
    let stopped_by_setup = reaped
        .recv_timeout(std::time::Duration::from_secs(1))
        .is_ok();
    if !stopped_by_setup {
        terminate_fixture_process(pid);
        let _ = reaped.recv_timeout(std::time::Duration::from_secs(1));
    }
    let current_stopped_by_setup = current_reaped
        .recv_timeout(std::time::Duration::from_millis(100))
        .is_ok();
    if !current_stopped_by_setup {
        terminate_fixture_process(current_pid);
        let _ = current_reaped.recv_timeout(std::time::Duration::from_secs(1));
    }
    assert!(
        server.finish(),
        "setup did not inspect the owned legacy runtime: stopped={stopped_by_setup}, stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );

    assert!(
        output.status.success(),
        "setup should retire the owned legacy runtime: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(stopped_by_setup, "setup stopped the registered legacy process");
    assert!(!current_stopped_by_setup, "setup preserved the current indexer process");
    let remaining: serde_json::Value = serde_json::from_slice(
        &std::fs::read(default_descriptor_dir(&home).join("daemons.json"))
            .expect("remaining registry"),
    )
    .expect("remaining registry JSON");
    assert_eq!(
        remaining,
        serde_json::Value::Array(preserved),
        "setup removes only the retired descriptor",
    );
}

#[test]
fn ordinary_setup_rejects_an_unproven_legacy_headless_daemon() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let current_before = std::fs::read_link(kast_home.join("current")).expect("current release");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let socket_path = temp.path().join("unproven-headless.sock");
    let _listener = UnixListener::bind(&socket_path).expect("unproven runtime socket");
    let (pid, reaped) = spawn_reapable_process();
    write_legacy_headless_descriptor(&home, &workspace, &socket_path, pid, Some(1), &[]);
    let registry_path = default_descriptor_dir(&home).join("daemons.json");
    let registry_before = std::fs::read(&registry_path).expect("descriptor registry");

    let output = setup(&home, &kast_home, &source);
    let stopped_by_setup = reaped
        .recv_timeout(std::time::Duration::from_millis(100))
        .is_ok();
    if !stopped_by_setup {
        terminate_fixture_process(pid);
        let _ = reaped.recv_timeout(std::time::Duration::from_secs(1));
    }

    assert!(!output.status.success(), "unproven process identity must block setup");
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("typed setup failure");
    assert_eq!(result["code"], "RUNTIME_IDENTITY_MISMATCH");
    assert!(!stopped_by_setup, "setup did not signal an unproven process");
    assert_eq!(
        std::fs::read(registry_path).expect("unchanged descriptor registry"),
        registry_before,
    );
    assert_eq!(
        std::fs::read_link(kast_home.join("current")).expect("current release after failure"),
        current_before,
    );
}

#[test]
fn ordinary_setup_rejects_a_malformed_runtime_registry_without_activating() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    let registry_path = default_descriptor_dir(&home).join("daemons.json");
    std::fs::create_dir_all(registry_path.parent().expect("registry parent"))
        .expect("registry directory");
    let malformed = b"{not-json";
    std::fs::write(&registry_path, malformed).expect("malformed registry");

    let output = setup(&home, &kast_home, &source);

    assert!(!output.status.success(), "malformed registry must block setup");
    let result: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("typed setup failure");
    assert_eq!(result["code"], "RUNTIME_DESCRIPTOR_REGISTRY_INVALID");
    assert_eq!(std::fs::read(registry_path).expect("unchanged registry"), malformed);
    assert!(!kast_home.join("current").exists(), "setup did not activate a release");
}

#[test]
fn current_setup_archives_a_restored_unmanaged_user_command() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    assert!(setup(&home, &kast_home, &source).status.success());
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("managed user command");
    std::fs::write(&user_command, "unmanaged").expect("unmanaged user command");

    let current = setup(&home, &kast_home, &source);

    assert!(
        current.status.success(),
        "current setup should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&current.stdout),
        String::from_utf8_lossy(&current.stderr),
    );
    let result: serde_json::Value = serde_json::from_slice(&current.stdout).expect("setup result");
    let archives = legacy_kast_archives(&kast_home);
    assert_eq!(archives.len(), 1);
    let backup = &archives[0];
    assert_eq!(result["status"], "CURRENT");
    assert_eq!(result["backup"], backup.display().to_string());
    assert_eq!(
        std::fs::read_to_string(backup).expect("archived unmanaged command"),
        "unmanaged",
    );
    assert_eq!(
        std::fs::read_link(user_command).expect("restored managed user command"),
        kast_home.join("current/bin/kast"),
    );
}

#[test]
fn failed_current_setup_preserves_unrelated_legacy_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");

    assert!(setup(&home, &kast_home, &source).status.success());
    let local_bin = home.join(".local/bin");
    std::fs::remove_file(local_bin.join("kast")).expect("managed user command");
    std::fs::remove_dir(&local_bin).expect("empty command directory");
    std::fs::write(&local_bin, "blocks command projection").expect("blocking command path");
    let legacy_config = home.join(".config/kast/config.toml");
    std::fs::create_dir_all(legacy_config.parent().expect("legacy config parent"))
        .expect("legacy config directory");
    std::fs::write(&legacy_config, "legacy").expect("legacy config");

    let failed = setup(&home, &kast_home, &source);

    assert!(!failed.status.success(), "command projection should fail");
    assert_eq!(
        std::fs::read_to_string(legacy_config).expect("preserved legacy config"),
        "legacy",
    );
}

#[test]
fn ordinary_setup_persists_the_central_legacy_backend_patch() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let source = write_install_bundle_source(temp.path(), "v9.8.7");
    assert!(setup(&home, &kast_home, &source).status.success());
    let config = kast_home.join("current/config/config.toml");
    std::fs::write(
        &config,
        "# keep this operator note\n[runtime]\ndefaultBackend = \"idea\"\n\n[server]\nmaxResults = 321\n",
    )
    .expect("legacy configuration");

    let output = setup(&home, &kast_home, &source);

    assert!(
        output.status.success(),
        "migration should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let migrated = std::fs::read_to_string(config).expect("migrated configuration");
    assert!(!migrated.contains("defaultBackend"));
    assert!(!migrated.contains("[runtime]"));
    assert!(migrated.contains("# keep this operator note"));
    assert!(migrated.contains("maxResults = 321"));
}
