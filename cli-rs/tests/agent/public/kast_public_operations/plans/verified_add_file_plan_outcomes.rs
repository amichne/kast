#[test]
fn identical_verified_add_file_plan_retry_replays_persisted_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Replay.kt");
    let content = "package sample\nclass Replay\n";
    let plan_id = verified_add_file_plan_id(&target, content.as_bytes());
    let response = json!({
        "planId": plan_id,
        "planVersion": 0,
        "stage": "AWAITING_APPROVAL",
        "operation": "add-file",
        "preview": {
            "targetPath": target,
            "proposedContent": content,
            "generation": 7,
        },
        "schemaVersion": 7,
    });
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("replayed-plan.sock"),
        vec![
            ("change/plan-add-file", response.clone()),
            ("change/plan-add-file", response.clone()),
        ],
    );
    let binary = write_active_kast_for_test(&home, &config_home);

    let mut first = installed_public_kast(&binary, &home, &config_home, &workspace);
    first.args(["change", "plan", "add-file", "--file", "src/main/kotlin/Replay.kt"]);
    let first = run_with_stdin(first, content);
    assert!(first.status.success(), "{first:?}");

    let mut retry = installed_public_kast(&binary, &home, &config_home, &workspace);
    retry.args(["change", "plan", "add-file", "--file", "src/main/kotlin/Replay.kt"]);
    let retry = run_with_stdin(retry, content);

    assert!(retry.status.success(), "{retry:?}");
    assert_eq!(decode(&retry), decode(&first));
    let requests = backend.join().expect("replayed plan backend");
    assert_eq!(
        requests.iter().filter(|request| request["method"] == "change/plan-add-file").count(),
        2,
    );
}

#[test]
fn verified_add_file_plan_rejection_preserves_the_typed_native_failure() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let backend = spawn_scripted_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("rejected-plan.sock"),
        vec![(
            "change/plan-add-file",
            json!({
                "failure": "TARGET_GENERATED",
                "operation": "add-file",
                "schemaVersion": 7,
            }),
        )],
    );
    let binary = write_active_kast_for_test(&home, &config_home);
    let mut plan = installed_public_kast(&binary, &home, &config_home, &workspace);
    plan.args([
        "change",
        "plan",
        "add-file",
        "--file",
        "src/main/kotlin/Generated.kt",
    ]);

    let rejected = run_with_stdin(plan, "package sample\nclass Generated\n");

    assert_eq!(rejected.status.code(), Some(1), "{rejected:?}");
    assert_eq!(decode_envelope(&rejected)["status"], "rejected");
    assert_eq!(decode(&rejected)["failure"], "TARGET_GENERATED");
    backend.join().expect("rejected plan backend");
}

#[test]
fn verified_add_file_prewrite_rejection_retains_retryable_plan_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Retryable.kt");
    let content = b"package sample\nclass Retryable\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Retryable.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let rejection = verified_add_file_rejected(&target, content, "REVALIDATION", "CANCELLED");
    let receipt = verified_add_file_receipt(&target, content);
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("retryable-prewrite.sock"),
        vec![
            ("change/apply-add-file", rejection.clone()),
            ("change/apply-add-file", receipt.clone()),
        ],
    );

    let first = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("cancelled apply");
    assert_eq!(first.status.code(), Some(1), "{first:?}");
    assert_eq!(decode(&first), rejection);

    let retry = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("retried apply");
    assert!(retry.status.success(), "{retry:?}");
    assert_eq!(decode(&retry), receipt);
    let requests = backend.join().expect("retryable pre-write backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        2,
    );
}
