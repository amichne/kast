use std::process::Stdio;
use support::*;

fn setup_command(home: &Path, kast_home: &Path, source: &Path) -> SetupCommand {
    let mut command = SetupCommand::new(kast(home, &kast_home.join("unused-config")));
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

fn wait_for_setup_barrier(child: &mut SetupChild, barrier_directory: &Path, stage: &str) {
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
        .env("KAST_TEST_SETUP_DURABILITY_FAILURE_POINT", failure_point)
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
    wait_for_setup_barrier(&mut child, &barrier, "before-agent-create-failure-cleanup");
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("replace unsynced agent projection");
    std::fs::write(&user_command, "unmanaged").expect("concurrent replacement");
    release_setup_barrier(&barrier, "before-agent-create-failure-cleanup");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "durability failure must fail setup"
    );
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
    wait_for_setup_barrier(&mut child, &barrier, "after-agent-rollback-validation");
    let user_command = home.join(".local/bin/kast");
    std::fs::remove_file(&user_command).expect("replace validated agent projection");
    std::fs::write(&user_command, "late unmanaged").expect("late agent replacement");
    release_setup_barrier(&barrier, "after-agent-rollback-validation");

    let output = child.wait_with_output().expect("failed setup output");

    assert!(
        !output.status.success(),
        "durability failure must fail setup"
    );
    assert_eq!(
        std::fs::read_to_string(&user_command).expect("preserved agent replacement"),
        "late unmanaged",
    );
    assert!(
        std::fs::read_dir(home.join(".local/bin"))
            .expect("local bin directory")
            .map(|entry| entry.expect("local bin entry"))
            .all(|entry| !entry
                .file_name()
                .to_string_lossy()
                .starts_with("kast.kast-rollback-")),
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
