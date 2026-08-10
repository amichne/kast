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
    assert!(
        help.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&help.stdout),
        String::from_utf8_lossy(&help.stderr),
    );
    let help = String::from_utf8(help.stdout).expect("UTF-8 help");
    for operation in ["rename", "replace", "add-file", "add-declaration"] {
        assert!(help.contains(operation), "missing {operation}:\n{help}");
    }
    for operation in ["add-implementation", "add-statement"] {
        assert!(!help.contains(operation), "unexpected {operation}:\n{help}");
    }
}

#[test]
fn public_apply_owns_lease_and_returns_verified_receipt() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"verified-apply\"\n",
    )
    .expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);

    let change = change_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    assert!(
        change.status.success(),
        "change should succeed: stdout={} stderr={}",
        String::from_utf8_lossy(&change.stdout),
        String::from_utf8_lossy(&change.stderr),
    );
    let change = decode(&change);
    let plan_id = change["planId"].as_str().expect("plan id");

    let socket = fixture.path().join("verified-apply.sock");
    let backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &socket,
        &target,
        content,
        successful_verified_add_file_script(&target, content),
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", plan_id])
        .output()
        .expect("public apply");
    assert!(
        apply.status.success(),
        "public apply should acquire and release its own lease: stdout={} stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr),
    );
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert_eq!(receipt["schemaVersion"], 7, "{receipt:#}");
    assert_eq!(receipt["lease"]["state"], "RELEASED", "{receipt:#}");
    assert_eq!(
        receipt["lease"]
            .as_object()
            .expect("public lease receipt")
            .keys()
            .map(String::as_str)
            .collect::<std::collections::BTreeSet<_>>(),
        ["ownership", "releaseReceipt", "state"]
            .into_iter()
            .collect(),
        "private lease authority escaped public output: {receipt:#}",
    );
    assert_eq!(std::fs::read(&target).expect("created source"), content);

    let requests = backend.join().expect("mutation backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        1,
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", plan_id])
        .output()
        .expect("terminal receipt replay");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "terminal replay must be stable");
}

#[test]
fn terminal_replay_rejects_lease_evidence_substituted_across_private_files() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Substitution.kt");
    let content = b"package sample\nclass Substitution\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Substitution.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("substitution.sock"),
        &target,
        content,
        successful_verified_add_file_script(&target, content),
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
    let original_binding = plan["state"]["receipt"]["lease"]["leaseBindingSha256"]
        .as_str()
        .expect("private lease binding");
    let substituted_binding = if original_binding == "f".repeat(64) {
        "e".repeat(64)
    } else {
        "f".repeat(64)
    };
    plan["state"]["receipt"]["lease"]["leaseBindingSha256"] = json!(substituted_binding);
    let mut encoded = serde_json::to_vec(&plan).expect("substituted plan JSON");
    encoded.push(b'\n');
    std::fs::write(&plan_path, encoded).expect("substitute terminal lease evidence");

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("substituted replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(
        decode(&replay)["error"],
        "KAST_TERMINAL_RECOVERY_EVIDENCE_MISMATCH",
    );
    assert_eq!(std::fs::read(&target).expect("retained source"), content);
}

#[test]
fn terminal_verified_receipt_persistence_failure_replays_from_durable_journal() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Terminal.kt");
    let content = b"package sample\nclass Terminal\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Terminal.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let apply_backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("terminal-persistence-apply.sock"),
        &target,
        content,
        successful_verified_add_file_script(&target, content),
    );

    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env(
            "KAST_TEST_MUTATION_FAILURE_POINT",
            "TERMINAL_RECEIPT_PERSISTENCE",
        )
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("terminal persistence failure");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    assert_eq!(decode(&interrupted)["outcome"], "RECOVERY_REQUIRED");
    assert_eq!(std::fs::read(&target).expect("retained postimage"), content);
    apply_backend.join().expect("apply backend");

    let recover_shutdown = fixture.path().join("terminal-persistence-recover.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("terminal-persistence-recover.sock"),
        &recover_shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("new-process recovery");
    assert!(recovered.status.success(), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    std::fs::write(&recover_shutdown, "stop\n").expect("stop recovery backend");
    let recovery_requests = recover_backend.join().expect("recovery backend");
    assert_eq!(
        recovery_requests
            .iter()
            .filter(|request| {
                matches!(
                    request["method"].as_str(),
                    Some("raw/apply-edits" | "raw/exact-file-image-cas")
                )
            })
            .count(),
        0,
        "verified journal replay must not write source again"
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("terminal retry");
    assert!(replay.status.success(), "{replay:?}");
    assert_eq!(decode(&replay), receipt);
}
