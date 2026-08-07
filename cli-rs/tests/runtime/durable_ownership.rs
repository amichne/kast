#[path = "../support/mod.rs"]
mod support;

use sha2::{Digest as _, Sha256};
use std::os::unix::fs::PermissionsExt as _;
use std::time::Duration;
use support::*;

#[path = "durable_ownership/fixture.rs"]
mod fixture;
use fixture::RuntimeServiceFixture;

#[path = "durable_ownership/review_regressions.rs"]
mod review_regressions;

#[path = "durable_ownership/remaining_review_regressions.rs"]
mod remaining_review_regressions;

#[cfg(not(target_os = "macos"))]
#[test]
fn requested_socket_path_is_persisted_by_start_review_regression() {
    let temp = tempfile::tempdir().expect("runtime registration fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let kast_home = default_install_root(&home);
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    write_current_cli_install_manifest_for_test(&home, &config_home);

    let java_home = temp.path().join("java-home");
    let java = java_home.join("bin/java");
    std::fs::create_dir_all(java.parent().expect("Java bin directory"))
        .expect("Java bin directory");
    std::fs::write(&java, "#!/bin/sh\nexit 0\n").expect("fake Java");
    std::fs::set_permissions(&java, std::fs::Permissions::from_mode(0o755))
        .expect("fake Java mode");
    let manager_root = temp.path().join("test-manager");
    let requested_socket = workspace.join("requested.sock");
    let start = kast(&home, &config_home)
        .current_dir(&workspace)
        .env("JAVA_HOME", &java_home)
        .env("KAST_TEST_ALLOW_RUNTIME_SERVICE_MANAGER", "1")
        .env("KAST_TEST_RUNTIME_SERVICE_MANAGER_ROOT", &manager_root)
        .args([
            "--output",
            "json",
            "start",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--socket-path",
            "requested.sock",
            "--wait-timeout-ms",
            "1",
        ])
        .output()
        .expect("runtime start");
    assert_error(&start, "RUNTIME_TIMEOUT");

    let workspace_key = hex::encode(Sha256::digest(workspace.to_string_lossy().as_bytes()));
    let service_root = kast_home.join("state/runtime/services").join(workspace_key);
    let active: serde_json::Value = serde_json::from_slice(
        &std::fs::read(service_root.join("active.json")).expect("active registration"),
    )
    .expect("active registration JSON");
    let launch: serde_json::Value = serde_json::from_slice(
        &std::fs::read(
            service_root
                .join(
                    active["runtimeInstanceId"]
                        .as_str()
                        .expect("runtime instance identity"),
                )
                .join("launch.json"),
        )
        .expect("launch registration"),
    )
    .expect("launch registration JSON");
    let expected = requested_socket.display().to_string();
    let expected_argument = format!("--socket-path={expected}");
    assert_eq!(launch["socketPath"], expected);
    assert_eq!(
        launch["command"]
            .as_array()
            .expect("registered command")
            .iter()
            .filter(|argument| argument.as_str() == Some(expected_argument.as_str()))
            .count(),
        1,
    );
}

#[test]
fn stop_uses_digest_bound_ownership_when_the_semantic_endpoint_is_unservable() {
    let mut fixture = RuntimeServiceFixture::new();
    let stop = fixture
        .command()
        .arg("stop")
        .args([
            "--workspace-root",
            fixture.workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("runtime stop");

    assert_success(&stop, "digest-bound endpoint-independent stop");
    assert!(
        wait_until(Duration::from_secs(1), || fixture
            .runtime
            .try_wait()
            .expect("runtime status")
            .is_some()),
        "registered runtime did not terminate"
    );
    assert!(!fixture.registration.exists(), "registration remains");
    assert!(!fixture.descriptor_registry.exists(), "descriptor remains");
    assert!(!fixture.socket_path.exists(), "socket remains");
}

#[test]
fn stop_signals_an_exact_live_orphan_after_the_service_manager_loses_it() {
    let mut fixture = RuntimeServiceFixture::new();
    fixture.orphan_runtime_from_manager();

    let repair = fixture
        .repair_command(false)
        .output()
        .expect("orphan repair inspection");
    assert_success(&repair, "orphan repair inspection");
    let repair = output_json(&repair);
    assert_eq!(repair["state"], "BLOCKED");
    assert_eq!(repair["blockers"].as_array().map(Vec::len), Some(1));

    let stop = fixture
        .command()
        .arg("stop")
        .args([
            "--workspace-root",
            fixture.workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("runtime stop");

    assert_success(&stop, "exact orphan stop");
    assert!(
        wait_until(Duration::from_secs(1), || fixture
            .runtime
            .try_wait()
            .expect("runtime status")
            .is_some()),
        "registered orphan did not terminate"
    );
    assert!(!fixture.registration.exists(), "registration remains");
    assert!(!fixture.descriptor_registry.exists(), "descriptor remains");
    assert!(!fixture.socket_path.exists(), "socket remains");
}

#[test]
fn truly_unregistered_live_runtime_is_not_admitted_as_absent() {
    let mut fixture = RuntimeServiceFixture::new();
    fixture.remove_registration_and_descriptor();

    let repair = fixture
        .repair_command(false)
        .output()
        .expect("unregistered runtime inspection");

    assert_success(&repair, "unregistered runtime inspection");
    let repair = output_json(&repair);
    assert_eq!(repair["state"], "BLOCKED");
    assert_eq!(repair["blockers"].as_array().map(Vec::len), Some(1));
    assert!(
        fixture
            .runtime
            .try_wait()
            .expect("runtime status")
            .is_none(),
        "inspection terminated the unregistered runtime"
    );
}

#[test]
fn descriptor_for_unrelated_live_process_never_signals() {
    let temp = tempfile::tempdir().expect("runtime ownership fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("unresponsive.sock");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let listener = UnixListener::bind(&socket_path).expect("unresponsive endpoint");
    let mut unrelated = Command::new("/bin/sleep")
        .arg("30")
        .spawn()
        .expect("unrelated process");
    let descriptor_dir = default_descriptor_dir(&home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor directory");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_process_test(
            &workspace,
            &socket_path,
            "indexer",
            "durable-ownership-test",
            unrelated.id(),
        )]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor registry");

    let stop = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "stop",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("runtime stop");

    assert_error(&stop, "RUNTIME_OWNERSHIP_AMBIGUOUS");
    assert!(
        unrelated.try_wait().expect("unrelated status").is_none(),
        "an unregistered process was signaled"
    );
    assert!(descriptor_dir.join("daemons.json").exists());
    assert!(socket_path.exists());
    unrelated.kill().expect("test process cleanup");
    unrelated.wait().expect("test process exit");
    drop(listener);
    std::fs::remove_file(socket_path).expect("test socket cleanup");
}

#[test]
fn repair_is_dry_run_by_default_and_execute_is_idempotent() {
    let mut fixture = RuntimeServiceFixture::new();
    fixture.runtime.kill().expect("stop fixture runtime");
    fixture.runtime.wait().expect("fixture runtime exit");

    let dry_run = fixture
        .repair_command(false)
        .output()
        .expect("dry-run repair");
    assert_success(&dry_run, "dry-run repair");
    let dry_run = output_json(&dry_run);
    assert_eq!(dry_run["mode"], "DRY_RUN");
    assert_eq!(dry_run["state"], "REPAIRABLE");
    assert_eq!(dry_run["actions"][0]["executed"], false);
    assert!(
        fixture.registration.exists(),
        "dry-run changed registration"
    );
    assert!(
        fixture.descriptor_registry.exists(),
        "dry-run changed descriptor"
    );

    let execute = fixture
        .repair_command(true)
        .output()
        .expect("executing repair");
    assert_success(&execute, "executing repair");
    let execute = output_json(&execute);
    assert_eq!(execute["mode"], "EXECUTE");
    assert_eq!(execute["state"], "CLEAN");
    assert_eq!(execute["actions"][0]["executed"], true);
    assert!(!fixture.registration.exists());
    assert!(!fixture.descriptor_registry.exists());
    assert!(!fixture.socket_path.exists());

    let repeated = fixture
        .repair_command(true)
        .output()
        .expect("repeated repair");
    assert_success(&repeated, "repeated repair");
    let repeated = output_json(&repeated);
    assert_eq!(repeated["state"], "CLEAN");
    assert_eq!(repeated["actions"].as_array().map(Vec::len), Some(0));
}

#[test]
fn unregistered_missing_workspace_stays_blocked_deleted_workspace_registration_review_regression() {
    let temp = tempfile::tempdir().expect("missing workspace fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let missing = temp.path().join("missing-workspace");

    let repair = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "repair",
            "--workspace-root",
            missing.to_str().expect("workspace path"),
            "--execute",
        ])
        .output()
        .expect("missing workspace repair");

    assert_error(&repair, "WORKSPACE_ROOT_INVALID");
    assert!(!missing.exists(), "workspace was created");
}

#[test]
fn repair_removes_nonactive_dead_registration_before_active_registration() {
    let mut fixture = RuntimeServiceFixture::new();
    let nonactive = fixture.add_dead_registration();
    fixture.runtime.kill().expect("stop fixture runtime");
    fixture.runtime.wait().expect("fixture runtime exit");

    let execute = fixture
        .repair_command(true)
        .output()
        .expect("multi-registration repair");
    assert_success(&execute, "multi-registration repair");
    let execute = output_json(&execute);
    assert_eq!(execute["state"], "CLEAN");
    assert_eq!(execute["actions"].as_array().map(Vec::len), Some(2));
    assert!(!nonactive.exists());
    assert!(!fixture.registration.exists());
}

fn assert_success(output: &std::process::Output, context: &str) {
    assert!(
        output.status.success(),
        "{context}: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
}

fn assert_error(output: &std::process::Output, code: &str) {
    assert!(
        !output.status.success(),
        "expected {code}: stdout={}",
        String::from_utf8_lossy(&output.stdout),
    );
    assert!(
        String::from_utf8_lossy(&output.stdout).contains(code),
        "expected {code}: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
}

fn output_json(output: &std::process::Output) -> serde_json::Value {
    serde_json::from_slice(&output.stdout).expect("command JSON")
}

fn wait_until(timeout: Duration, mut predicate: impl FnMut() -> bool) -> bool {
    let deadline = std::time::Instant::now() + timeout;
    while std::time::Instant::now() < deadline {
        if predicate() {
            return true;
        }
        thread::sleep(Duration::from_millis(10));
    }
    predicate()
}
