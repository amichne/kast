use std::process::Command;

#[cfg(target_os = "macos")]
mod support;
#[cfg(target_os = "macos")]
use support::*;

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
    let binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &binary);
    let backend = spawn_scripted_idea_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        ScriptedCliAuthority::new(&binary, env!("CARGO_PKG_VERSION")),
        4,
        vec![],
    );

    let acquire = lease_command(
        &binary,
        &home,
        &config_home,
        &["acquire", "--backend", "idea"],
        &workspace,
    );
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

    let conflict = lease_command(
        &binary,
        &home,
        &config_home,
        &["acquire", "--backend", "idea"],
        &workspace,
    );
    assert_error(&conflict, "WORKSPACE_LEASE_CONFLICT");

    let status = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--backend", "idea", "--lease-id", &lease_id],
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
            "--backend",
            "idea",
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
        &["status", "--backend", "idea", "--lease-id", &lease_id],
        &other_workspace,
    );
    assert_error(&wrong_root, "WORKSPACE_LEASE_ROOT_MISMATCH");

    let wrong_backend = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--backend", "headless", "--lease-id", &lease_id],
        &workspace,
    );
    assert_error(&wrong_backend, "WORKSPACE_LEASE_BACKEND_MISMATCH");

    let mut tampered = lease_id.clone().into_bytes();
    let last = tampered.last_mut().expect("token byte");
    *last = if *last == b'0' { b'1' } else { b'0' };
    let tampered = String::from_utf8(tampered).expect("token UTF-8");
    let tamper = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--backend", "idea", "--lease-id", &tampered],
        &workspace,
    );
    assert_error(&tamper, "WORKSPACE_LEASE_TAMPERED");

    let release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--backend", "idea", "--lease-id", &lease_id],
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
        &["release", "--backend", "idea", "--lease-id", &lease_id],
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
    let binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &binary);
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
            "--backend",
            "idea",
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
        &["status", "--backend", "idea", "--lease-id", &abandoned_id],
        &workspace,
    );
    assert_success(&abandoned_status, "abandoned status");
    assert_eq!(
        output_json(&abandoned_status)["result"]["state"],
        "ABANDONED"
    );

    let recovered = lease_command(
        &binary,
        &home,
        &config_home,
        &["acquire", "--backend", "idea"],
        &workspace,
    );
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
        &["release", "--backend", "idea", "--lease-id", recovered_id],
        &workspace,
    );
    assert_success(&release, "recovered release");
    assert_eq!(
        output_json(&release)["result"]["releaseReceipt"]["reason"],
        "BORROWED_RUNTIME_PRESERVED"
    );
    assert_eq!(backend.join().expect("scripted backend").len(), 8);
}

#[cfg(target_os = "macos")]
#[test]
fn runtime_loss_is_failed_before_a_leased_semantic_session_opens() {
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
    let binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &binary);
    let backend = spawn_scripted_idea_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        ScriptedCliAuthority::new(&binary, env!("CARGO_PKG_VERSION")),
        2,
        vec![],
    );
    let acquire = lease_command(
        &binary,
        &home,
        &config_home,
        &["acquire", "--backend", "idea"],
        &workspace,
    );
    assert_success(&acquire, "acquire before runtime loss");
    let lease_id = output_json(&acquire)["result"]["leaseId"]
        .as_str()
        .expect("lease id")
        .to_string();
    std::fs::remove_file(default_descriptor_dir(&home).join("daemons.json"))
        .expect("simulate runtime loss");

    let status = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--backend", "idea", "--lease-id", &lease_id],
        &workspace,
    );
    assert_success(&status, "failed lease status");
    let status_json = output_json(&status);
    assert_eq!(status_json["result"]["state"], "FAILED");
    assert_eq!(
        status_json["result"]["failureReason"],
        "RUNTIME_UNAVAILABLE"
    );

    let verify = kast_at(&binary, &home, &config_home)
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
        .expect("verify after runtime loss");
    assert_error(&verify, "WORKSPACE_LEASE_RUNTIME_UNAVAILABLE");
    assert_eq!(backend.join().expect("scripted backend").len(), 4);
}

#[cfg(target_os = "macos")]
#[test]
fn indexing_idea_runtime_never_becomes_lease_ready() {
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
    let binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &binary);
    let backend = spawn_sequenced_idea_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![
            (
                "runtime/status",
                serde_json::json!({
                    "state": "INDEXING",
                    "healthy": true,
                    "active": true,
                    "indexing": true,
                    "backendName": "idea",
                    "backendVersion": "scripted-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "schemaVersion": 3
                }),
            ),
            (
                "capabilities",
                serde_json::json!({
                    "backendName": "idea",
                    "backendVersion": "scripted-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "readCapabilities": [],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 3
                }),
            ),
        ],
    );
    write_macos_plugin_workspace_metadata_for_cli(&workspace, &binary, env!("CARGO_PKG_VERSION"));

    let acquire = lease_command(
        &binary,
        &home,
        &config_home,
        &["acquire", "--backend", "idea", "--wait-timeout-ms", "100"],
        &workspace,
    );
    assert_error(&acquire, "RUNTIME_TIMEOUT");
    let records = default_install_root(&home).join("runtime/workspace-leases");
    assert!(
        !records.exists()
            || std::fs::read_dir(&records)
                .expect("lease records")
                .next()
                .is_none(),
        "failed acquisition must not commit a lease record"
    );
    assert!(
        default_descriptor_dir(&home).join("daemons.json").is_file(),
        "failed IDEA acquisition must preserve the borrowed runtime"
    );
    assert_eq!(backend.join().expect("indexing backend").len(), 2);
}

#[cfg(target_os = "macos")]
#[test]
fn primary_and_linked_worktree_leases_keep_distinct_exact_roots() {
    let fixture_parent =
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("target/workspace-lease-fixtures");
    std::fs::create_dir_all(&fixture_parent).expect("fixture parent");
    let temp = tempfile::tempdir_in(fixture_parent).expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let primary = temp.path().join("primary");
    let linked = temp.path().join("linked");
    std::fs::create_dir_all(primary.join(".git/worktrees/linked")).expect("primary Git dir");
    std::fs::create_dir_all(&linked).expect("linked root");
    std::fs::write(
        linked.join(".git"),
        "gitdir: ../primary/.git/worktrees/linked\n",
    )
    .expect("linked Git file");
    std::fs::write(
        primary.join("settings.gradle.kts"),
        "rootProject.name = \"primary\"\n",
    )
    .expect("primary settings");
    std::fs::write(
        linked.join("settings.gradle.kts"),
        "rootProject.name = \"linked\"\n",
    )
    .expect("linked settings");
    let primary = std::fs::canonicalize(primary).expect("canonical primary");
    let linked = std::fs::canonicalize(linked).expect("canonical linked");
    let binary = write_homebrew_kast_for_test(temp.path());
    write_macos_homebrew_receipt_for_test(&home, &binary);
    let primary_socket = std::env::temp_dir().join(format!(
        "kast-{}-primary.sock",
        uuid::Uuid::new_v4().simple()
    ));
    let linked_socket = std::env::temp_dir().join(format!(
        "kast-{}-linked.sock",
        uuid::Uuid::new_v4().simple()
    ));

    let primary_backend = spawn_scripted_idea_backend_for_invocations(
        &home,
        &config_home,
        &primary,
        &primary_socket,
        ScriptedCliAuthority::new(&binary, env!("CARGO_PKG_VERSION")),
        2,
        vec![],
    );
    let primary_acquire = lease_command(
        &binary,
        &home,
        &config_home,
        &["acquire", "--backend", "idea"],
        &primary,
    );
    assert_success(&primary_acquire, "primary acquire");
    let primary_json = output_json(&primary_acquire);
    assert_eq!(primary_json["result"]["workspaceKind"], "PRIMARY_CHECKOUT");
    let primary_id = primary_json["result"]["leaseId"]
        .as_str()
        .expect("primary lease id");
    let primary_release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--backend", "idea", "--lease-id", primary_id],
        &primary,
    );
    assert_success(&primary_release, "primary release");
    assert_eq!(primary_backend.join().expect("primary backend").len(), 4);
    std::fs::remove_file(&primary_socket).expect("primary socket cleanup");

    let linked_backend = spawn_scripted_idea_backend_for_invocations(
        &home,
        &config_home,
        &linked,
        &linked_socket,
        ScriptedCliAuthority::new(&binary, env!("CARGO_PKG_VERSION")),
        2,
        vec![],
    );
    let linked_acquire = lease_command(
        &binary,
        &home,
        &config_home,
        &["acquire", "--backend", "idea"],
        &linked,
    );
    assert_success(&linked_acquire, "linked acquire");
    let linked_json = output_json(&linked_acquire);
    assert_eq!(linked_json["result"]["workspaceKind"], "LINKED_WORKTREE");
    assert_ne!(
        primary_json["result"]["workspaceRoot"],
        linked_json["result"]["workspaceRoot"]
    );
    let linked_id = linked_json["result"]["leaseId"]
        .as_str()
        .expect("linked lease id");
    let cross_root = lease_command(
        &binary,
        &home,
        &config_home,
        &["status", "--backend", "idea", "--lease-id", linked_id],
        &primary,
    );
    assert_error(&cross_root, "WORKSPACE_LEASE_ROOT_MISMATCH");
    let linked_release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--backend", "idea", "--lease-id", linked_id],
        &linked,
    );
    assert_success(&linked_release, "linked release");
    assert_eq!(linked_backend.join().expect("linked backend").len(), 4);
    std::fs::remove_file(&linked_socket).expect("linked socket cleanup");
}

#[cfg(target_os = "macos")]
fn lease_command(
    binary: &std::path::Path,
    home: &std::path::Path,
    config_home: &std::path::Path,
    command: &[&str],
    workspace: &std::path::Path,
) -> std::process::Output {
    let mut args = vec!["--output", "json", "agent", "lease"];
    args.extend_from_slice(command);
    args.extend_from_slice(&[
        "--workspace-root",
        workspace.to_str().expect("workspace path"),
    ]);
    kast_at(binary, home, config_home)
        .env_remove("CODEX_HOME")
        .args(args)
        .output()
        .expect("workspace lease command")
}

#[cfg(target_os = "macos")]
fn output_json(output: &std::process::Output) -> serde_json::Value {
    serde_json::from_slice(&output.stdout).unwrap_or_else(|error| {
        panic!(
            "output JSON: {error}; stdout={}; stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        )
    })
}

#[cfg(target_os = "macos")]
fn assert_success(output: &std::process::Output, label: &str) {
    assert!(
        output.status.success(),
        "{label}: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

#[cfg(target_os = "macos")]
fn assert_error(output: &std::process::Output, code: &str) {
    assert!(!output.status.success(), "{code} must fail");
    assert_eq!(output_json(output)["error"]["code"], code);
}
