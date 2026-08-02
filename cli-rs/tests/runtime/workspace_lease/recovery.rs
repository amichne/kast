#[cfg(target_os = "macos")]
#[test]
fn runtime_loss_is_failed_before_a_leased_semantic_session_opens_and_recovers_boundedly() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("indexer.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"lease\"\n",
    )
    .expect("settings");
    let binary = write_active_kast_for_test(&home, &config_home);
    let backend = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        100,
        vec![],
    );
    let acquire = lease_command(&binary, &home, &config_home, &["acquire"], &workspace);
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
        &["status", "--lease-id", &lease_id],
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
            "--lease-id",
            &lease_id,
        ])
        .output()
        .expect("verify after runtime loss");
    assert_error(&verify, "WORKSPACE_LEASE_RUNTIME_UNAVAILABLE");
    assert_indexer_runtime_observation_only(&backend.join().expect("scripted backend"));

    let failed_release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--lease-id", &lease_id],
        &workspace,
    );
    assert_success(&failed_release, "release failed lease");
    assert_eq!(
        output_json(&failed_release)["result"]["releaseReceipt"]["reason"],
        "BORROWED_RUNTIME_PRESERVED"
    );

    std::fs::remove_file(&socket).expect("stale socket cleanup");
    let replacement = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &socket,
        100,
        vec![],
    );
    let recovery = lease_command(&binary, &home, &config_home, &["acquire"], &workspace);
    assert_success(&recovery, "bounded recovery acquire");
    let recovery_id = output_json(&recovery)["result"]["leaseId"]
        .as_str()
        .expect("recovery lease id")
        .to_string();
    let recovery_release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--lease-id", &recovery_id],
        &workspace,
    );
    assert_success(&recovery_release, "bounded recovery release");
    assert_indexer_runtime_observation_only(&replacement.join().expect("replacement backend"));
}

#[cfg(target_os = "macos")]
#[test]
fn indexing_indexer_runtime_never_becomes_lease_ready() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket = temp.path().join("indexer.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"lease\"\n",
    )
    .expect("settings");
    let binary = write_active_kast_for_test(&home, &config_home);
    let mut responses = Vec::new();
    for _ in 0..5 {
        responses.push((
            "runtime/status",
            serde_json::json!({
                "state": "INDEXING",
                "healthy": true,
                "active": true,
                "indexing": true,
                "backendName": "indexer",
                "backendVersion": "scripted-test",
                "workspaceRoot": workspace.display().to_string(),
                "schemaVersion": 5
            }),
        ));
        responses.push((
            "capabilities",
            serde_json::json!({
                "backendName": "indexer",
                "backendVersion": "scripted-test",
                "workspaceRoot": workspace.display().to_string(),
                "readCapabilities": [],
                "mutationCapabilities": [],
                "limits": {
                    "requestTimeoutMillis": 60000,
                    "maxResults": 1000,
                    "maxConcurrentRequests": 4
                },
                "schemaVersion": 5
            }),
        ));
    }
    let backend =
        spawn_sequenced_indexer_backend(&home, &config_home, &workspace, &socket, responses);
    let acquire = lease_command(
        &binary,
        &home,
        &config_home,
        &["acquire", "--wait-timeout-ms", "100"],
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
        "failed indexer acquisition must preserve the borrowed runtime"
    );
    let requests = backend.join().expect("indexing backend");
    assert_indexer_runtime_observation_only(&requests);
    assert!(
        requests
            .iter()
            .filter(|request| request["method"] == "runtime/status")
            .count()
            > 1,
        "INDEXING acquisition must poll the same indexer runtime"
    );
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
    let binary = write_active_kast_for_test(&home, &config_home);
    let primary_socket = std::env::temp_dir().join(format!(
        "kast-{}-primary.sock",
        uuid::Uuid::new_v4().simple()
    ));
    let linked_socket = std::env::temp_dir().join(format!(
        "kast-{}-linked.sock",
        uuid::Uuid::new_v4().simple()
    ));

    let primary_backend = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &primary,
        &primary_socket,
        100,
        vec![],
    );
    let primary_acquire = lease_command(&binary, &home, &config_home, &["acquire"], &primary);
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
        &["release", "--lease-id", primary_id],
        &primary,
    );
    assert_success(&primary_release, "primary release");
    assert_indexer_runtime_observation_only(&primary_backend.join().expect("primary backend"));
    std::fs::remove_file(&primary_socket).expect("primary socket cleanup");

    let linked_backend = spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &linked,
        &linked_socket,
        100,
        vec![],
    );
    let linked_acquire = lease_command(&binary, &home, &config_home, &["acquire"], &linked);
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
        &["status", "--lease-id", linked_id],
        &primary,
    );
    assert_error(&cross_root, "WORKSPACE_LEASE_ROOT_MISMATCH");
    let linked_release = lease_command(
        &binary,
        &home,
        &config_home,
        &["release", "--lease-id", linked_id],
        &linked,
    );
    assert_success(&linked_release, "linked release");
    assert_indexer_runtime_observation_only(&linked_backend.join().expect("linked backend"));
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
    let payload = output_json(output);
    assert_eq!(payload["error"]["code"], code, "{payload:#}");
}
