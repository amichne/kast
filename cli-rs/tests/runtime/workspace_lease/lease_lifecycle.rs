#[test]
fn agent_exposes_the_typed_workspace_lease_lifecycle() {
    for command in ["acquire", "status", "release"] {
        let output = Command::new(env!("CARGO_BIN_EXE_kast"))
            .args(["agent", "lease", command, "--help"])
            .output()
            .expect("workspace lease help");

        assert!(
            output.status.success(),
            "agent lease {command} must be a typed command: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}

#[cfg(target_os = "macos")]
#[test]
fn borrowed_idea_lease_is_exact_authenticated_conflict_safe_and_idempotent() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let other_workspace = temp.path().join("other-workspace");
    let socket = temp.path().join("idea.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::create_dir_all(&other_workspace).expect("other workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let other_workspace =
        std::fs::canonicalize(other_workspace).expect("canonical other workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"lease\"\n",
    )
    .expect("settings");
    let binary = write_active_kast_for_test(&home, &config_home);
    let backend = spawn_scripted_idea_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        ScriptedCliAuthority::new(&binary, env!("CARGO_PKG_VERSION")),
        4,
        vec![],
    );

    let acquire = lease_command(&binary, &home, &config_home, &["acquire"], &workspace);
    assert_success(&acquire, "acquire");
    let acquire_json = output_json(&acquire);
    assert_eq!(acquire_json["result"]["state"], "READY");
    assert_eq!(acquire_json["result"]["ownership"], "BORROWED");
    assert_eq!(
        acquire_json["result"]["workspaceKind"],
        "DISPOSABLE_CHECKOUT"
    );
    assert_eq!(
        acquire_json["result"]["workspaceRoot"],
        workspace.to_str().expect("workspace")
    );
    let lease_id = acquire_json["result"]["leaseId"]
        .as_str()
        .expect("lease id")
        .to_string();

    let conflict = lease_command(&binary, &home, &config_home, &["acquire"], &workspace);
    assert_error(&conflict, "WORKSPACE_LEASE_CONFLICT");

    let status = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--lease-id", &lease_id],
        &workspace,
    );
    assert_success(&status, "status");
    assert_eq!(output_json(&status)["result"]["state"], "READY");

    let foreign_session = kast_at(&binary, &home, &config_home)
        .env_remove("CODEX_HOME")
        .env("KAST_AGENT_SESSION_ID", "foreign-session")
        .args([
            "--output",
            "json",
            "agent",
            "lease",
            "status",
            "--lease-id",
            &lease_id,
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .output()
        .expect("foreign session status");
    assert_error(&foreign_session, "WORKSPACE_LEASE_FOREIGN_SESSION");

    let wrong_root = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--lease-id", &lease_id],
        &other_workspace,
    );
    assert_error(&wrong_root, "WORKSPACE_LEASE_ROOT_MISMATCH");

    let backend_selector = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--backend", "headless", "--lease-id", &lease_id],
        &workspace,
    );
    assert!(!backend_selector.status.success());
    assert_eq!(output_json(&backend_selector)["code"], "CLI_USAGE");

    let mut tampered = lease_id.clone().into_bytes();
    let last = tampered.last_mut().expect("token byte");
    *last = if *last == b'0' { b'1' } else { b'0' };
    let tampered = String::from_utf8(tampered).expect("token UTF-8");
    let tamper = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--lease-id", &tampered],
        &workspace,
    );
    assert_error(&tamper, "WORKSPACE_LEASE_TAMPERED");

    let release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--lease-id", &lease_id],
        &workspace,
    );
    assert_success(&release, "release");
    let release_json = output_json(&release);
    assert_eq!(release_json["result"]["state"], "RELEASED");
    assert_eq!(
        release_json["result"]["releaseReceipt"]["reason"],
        "BORROWED_RUNTIME_PRESERVED"
    );
    assert_eq!(
        release_json["result"]["releaseReceipt"]["runtimeStopped"],
        false
    );

    let second_release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--lease-id", &lease_id],
        &workspace,
    );
    assert_success(&second_release, "idempotent release");
    assert_eq!(
        output_json(&second_release)["result"]["releaseReceipt"],
        release_json["result"]["releaseReceipt"]
    );

    let leased_verify = kast_at(&binary, &home, &config_home)
        .env_remove("CODEX_HOME")
        .args([
            "--output",
            "json",
            "agent",
            "verify",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--backend",
            "idea",
            "--lease-id",
            &lease_id,
        ])
        .output()
        .expect("leased verify");
    assert_error(&leased_verify, "WORKSPACE_LEASE_RELEASED");

    let runtime_status = kast_at(&binary, &home, &config_home)
        .env_remove("CODEX_HOME")
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "status",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
            "--backend",
            "idea",
        ])
        .output()
        .expect("runtime status");
    assert_success(&runtime_status, "borrowed runtime status after release");
    assert_eq!(output_json(&runtime_status)["selected"]["ready"], true);
    assert_eq!(backend.join().expect("scripted backend").len(), 8);
}

#[cfg(target_os = "macos")]
#[test]
fn abandoned_owner_is_observable_and_recovered_without_stopping_borrowed_idea() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("idea.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"lease\"\n",
    )
    .expect("settings");
    let binary = write_active_kast_for_test(&home, &config_home);
    let backend = spawn_scripted_idea_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        ScriptedCliAuthority::new(&binary, env!("CARGO_PKG_VERSION")),
        4,
        vec![],
    );

    let python = r#"
import os
import subprocess
import sys
environment = os.environ.copy()
environment.pop("CODEX_HOME", None)
completed = subprocess.run(sys.argv[1:], env=environment, capture_output=True)
sys.stdout.buffer.write(completed.stdout)
sys.stderr.buffer.write(completed.stderr)
raise SystemExit(completed.returncode)
"#;
    let abandoned_acquire = Command::new("python3")
        .arg("-c")
        .arg(python)
        .arg(&binary)
        .args([
            "--output",
            "json",
            "agent",
            "lease",
            "acquire",
            "--workspace-root",
            workspace.to_str().expect("workspace"),
        ])
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .output()
        .expect("abandoned acquire wrapper");
    assert_success(&abandoned_acquire, "abandoned acquire");
    let abandoned_id = output_json(&abandoned_acquire)["result"]["leaseId"]
        .as_str()
        .expect("abandoned lease id")
        .to_string();

    let abandoned_status = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--lease-id", &abandoned_id],
        &workspace,
    );
    assert_success(&abandoned_status, "abandoned status");
    assert_eq!(
        output_json(&abandoned_status)["result"]["state"],
        "ABANDONED"
    );

    let recovered = lease_command(&binary, &home, &config_home, &["acquire"], &workspace);
    assert_success(&recovered, "recovered acquire");
    let recovered_json = output_json(&recovered);
    assert_eq!(recovered_json["result"]["state"], "READY");
    assert_eq!(recovered_json["result"]["ownership"], "BORROWED");
    let recovered_id = recovered_json["result"]["leaseId"]
        .as_str()
        .expect("recovered lease id");

    let release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--lease-id", recovered_id],
        &workspace,
    );
    assert_success(&release, "recovered release");
    assert_eq!(
        output_json(&release)["result"]["releaseReceipt"]["reason"],
        "BORROWED_RUNTIME_PRESERVED"
    );
    assert_eq!(backend.join().expect("scripted backend").len(), 8);
}
