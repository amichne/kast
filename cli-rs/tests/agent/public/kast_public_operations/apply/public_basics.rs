use super::*;

#[test]
fn public_change_exposes_only_the_four_verified_mutations() {
    let fixture = tempfile::tempdir().expect("fixture");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");

    let help = Command::new(env!("CARGO_BIN_EXE_kast"))
        .arg0("kast")
        .current_dir(&workspace)
        .args(["change", "plan", "--help"])
        .output()
        .expect("public change help");
    assert!(help.status.success(), "{help:?}");
    let help = String::from_utf8(help.stdout).expect("UTF-8 help");
    for operation in ["rename", "replace", "add-file", "add-declaration"] {
        assert!(help.contains(operation), "missing {operation}:\n{help}");
    }
    for operation in ["add-implementation", "add-statement"] {
        assert!(!help.contains(operation), "unexpected {operation}:\n{help}");
    }
}

#[test]
fn public_apply_returns_verified_receipt_without_client_side_source_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let expected = verified_add_file_receipt(&target, content);
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("verified-apply.sock"),
        vec![("change/apply-add-file", expected.clone())],
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("public apply");
    assert!(apply.status.success(), "{apply:?}");
    assert_eq!(decode(&apply), expected);
    assert!(
        !target.exists(),
        "the client must not perform a second source write"
    );
    assert_eq!(
        backend
            .join()
            .expect("verified backend")
            .iter()
            .filter_map(|request| request["method"].as_str())
            .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
            .collect::<Vec<_>>(),
        ["change/apply-add-file"],
    );

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("verified-replay.sock"),
        vec![("change/apply-add-file", expected.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("terminal receipt replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), expected);
    replay_backend.join().expect("verified replay backend");
}
#[test]
fn terminal_replay_rejects_authority_substituted_inside_the_private_plan() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Substitution.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Substitution.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("substitution.sock"),
        vec![(
            "change/apply-add-file",
            verified_add_file_receipt(&target, content),
        )],
    );
    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("verified apply");
    assert!(apply.status.success(), "{apply:?}");
    backend.join().expect("substitution backend");

    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let mut plan: Value =
        serde_json::from_slice(&std::fs::read(&plan_path).expect("private terminal plan"))
            .expect("private terminal plan JSON");
    plan["state"]["result"]["planId"] = json!(format!("af-{}", "f".repeat(64)));
    std::fs::write(
        &plan_path,
        serde_json::to_vec(&plan).expect("substituted plan JSON"),
    )
    .expect("substitute terminal authority");

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("substituted replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(
        decode(&replay)["error"],
        "KAST_VERIFIED_ADD_FILE_RESULT_INVALID",
    );
    assert!(!target.exists());
}

#[test]
fn terminal_persistence_failure_after_rpc_leaves_recover_only_in_flight_authority() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Terminal.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Terminal.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let plan_directory = home.join(".local/share/kast/state/agent-plans");
    let plan_path = plan_directory.join(format!("{plan_id}.json"));
    let expected = verified_add_file_receipt(&target, content);
    let entered = fixture.path().join("terminal-persistence.entered");
    let release = fixture.path().join("terminal-persistence.release");
    let first_backend = spawn_gated_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("terminal-persistence-first.sock"),
        &target,
        content,
        &entered,
        &release,
        vec![("change/apply-add-file", expected.clone())],
    );
    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn terminal persistence failure");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while !entered.is_file() && std::time::Instant::now() < deadline {
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(entered.is_file(), "canonical apply RPC was not reached");
    std::fs::set_permissions(&plan_directory, std::fs::Permissions::from_mode(0o500))
        .expect("make state directory read-only after RPC dispatch");
    std::fs::write(&release, "release\n").expect("release canonical apply RPC");
    let interrupted = interrupted
        .wait_with_output()
        .expect("terminal persistence failure");
    std::fs::set_permissions(&plan_directory, std::fs::Permissions::from_mode(0o700))
        .expect("restore state directory");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    let first_requests = first_backend.join().expect("first persistence backend");
    assert_eq!(
        first_requests
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
        "the server outcome must precede the terminal persistence failure",
    );
    let stored: Value = serde_json::from_slice(&std::fs::read(&plan_path).expect("retained plan"))
        .expect("retained plan JSON");
    assert_eq!(stored["state"]["state"], "APPLY_OUTCOME_UNKNOWN");

    let direct_apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("in-flight direct apply rejection");
    assert_eq!(direct_apply.status.code(), Some(1), "{direct_apply:?}");
    assert_eq!(
        decode(&direct_apply)["error"],
        "KAST_VERIFIED_ADD_FILE_RECOVERY_REQUIRED",
    );

    let retry_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("terminal-persistence-retry.sock"),
        vec![("change/apply-add-file", expected.clone())],
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal persistence recovery");
    assert!(recovered.status.success(), "{recovered:?}");
    assert_eq!(decode(&recovered), expected);
    retry_backend.join().expect("retry persistence backend");

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("terminal-persistence-replay.sock"),
        vec![("change/apply-add-file", expected.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("terminal replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), expected);
    replay_backend.join().expect("terminal replay backend");
}
