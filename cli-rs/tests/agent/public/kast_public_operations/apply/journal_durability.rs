use super::*;

#[test]
fn dangling_recovery_namespace_entry_is_rejected_without_overwrite() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/DanglingRecovery.kt");
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/DanglingRecovery.kt",
        "package sample\nclass DanglingRecovery\n",
    );
    let journal_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.recovery.json"));
    let dangling_target = fixture.path().join("missing-recovery.json");
    std::os::unix::fs::symlink(&dangling_target, &journal_path).expect("dangling recovery link");
    let shutdown = fixture.path().join("dangling-recovery.shutdown");
    let backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("dangling-recovery.sock"),
        &shutdown,
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("apply with occupied recovery namespace");
    std::fs::write(&shutdown, "stop\n").expect("stop dangling-recovery backend");
    backend.join().expect("dangling-recovery backend");

    assert_eq!(apply.status.code(), Some(1), "{apply:?}");
    let failure = decode(&apply);
    assert_eq!(failure["error"], "KAST_PLAN_INVALID", "{failure:#}");
    assert!(
        std::fs::symlink_metadata(&journal_path)
            .expect("recovery namespace entry retained")
            .file_type()
            .is_symlink(),
        "the recovery namespace entry must not be overwritten",
    );
    assert!(
        !dangling_target.exists(),
        "the dangling target remains absent"
    );
    assert!(!target.exists(), "no source write is allowed");
}

#[test]
fn post_rename_directory_sync_failure_requires_recovery_and_retains_release_failure() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/JournalDurability.kt");
    let content = "package sample\nclass JournalDurability\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/JournalDurability.kt",
        content,
    );
    let shutdown = fixture.path().join("journal-durability.shutdown");
    let backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("journal-durability.sock"),
        &shutdown,
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env(
            "KAST_TEST_MUTATION_FAILURE_POINT",
            "RECOVERY_JOURNAL_DIRECTORY_SYNC",
        )
        .env("KAST_TEST_MUTATION_LEASE_RELEASE_FAILURE", "1")
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("post-rename directory-sync failure");
    std::fs::write(&shutdown, "stop\n").expect("stop journal-durability backend");
    backend.join().expect("journal-durability backend");

    assert_eq!(apply.status.code(), Some(1), "{apply:?}");
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "RECOVERY_REQUIRED", "{receipt:#}");
    assert_eq!(receipt["recoveryId"], plan_id, "{receipt:#}");
    assert!(
        receipt["reason"].as_str().is_some_and(|reason| {
            reason.contains(
                "Recovery journal directory sync failed at the deterministic post-rename test seam.",
            ) && reason.contains("Lease release also failed")
        }),
        "{receipt:#}",
    );
    assert!(!target.exists(), "no source write is allowed");
    let journal_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.recovery.json"));
    let metadata = std::fs::symlink_metadata(&journal_path).expect("recovery namespace entry");
    assert!(metadata.is_file(), "the renamed recovery journal remains");
    let journal: Value = serde_json::from_slice(
        &std::fs::read(&journal_path).expect("read renamed recovery journal"),
    )
    .expect("valid recovery journal");
    assert_eq!(journal["state"]["phase"], "PREPARED", "{journal:#}");
}
