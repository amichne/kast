use super::fixture::RuntimeTerminalBehavior;
use super::*;

#[test]
fn stop_reports_observed_terminal_state_and_is_idempotent() {
    let fixture = RuntimeServiceFixture::new_with_terminal_behavior(
        RuntimeTerminalBehavior::RemoveOwnedArtifacts,
    );
    let pid = fixture.runtime.id();

    let stop = fixture
        .command()
        .arg("stop")
        .args([
            "--workspace-root",
            fixture.workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("runtime stop");

    assert_success(&stop, "terminal-state stop");
    let stop = output_json(&stop);
    assert_eq!(stop["stopped"], true);
    assert_eq!(stop["stoppedCount"], 1);
    assert_eq!(stop["pid"], u64::from(pid));
    assert_eq!(stop["candidates"][0]["pidAlive"], false);
    assert_eq!(stop["candidates"][0]["terminated"], true);
    assert_eq!(stop["candidates"][0]["descriptorDeleted"], true);
    assert!(!fixture.registration.exists(), "registration remains");
    assert!(!fixture.descriptor_registry.exists(), "descriptor remains");
    assert!(!fixture.socket_path.exists(), "socket remains");

    let repeated = fixture
        .command()
        .arg("stop")
        .args([
            "--workspace-root",
            fixture.workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("repeated runtime stop");

    assert_success(&repeated, "repeated terminal-state stop");
    let repeated = output_json(&repeated);
    assert_eq!(repeated["stopped"], false);
    assert_eq!(repeated["stoppedCount"], 0);
    assert!(repeated.get("pid").is_none());
    assert_eq!(repeated["candidates"].as_array().map(Vec::len), None);
}

#[test]
fn stop_retains_typed_evidence_when_terminal_cleanup_is_incomplete() {
    let mut fixture = RuntimeServiceFixture::new_with_terminal_behavior(
        RuntimeTerminalBehavior::LeaveIncompleteCleanup,
    );

    let stop = fixture
        .command()
        .arg("stop")
        .args([
            "--workspace-root",
            fixture.workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("runtime stop");

    assert_error(&stop, "RUNTIME_OWNERSHIP_CHANGED");
    assert!(
        wait_until(Duration::from_secs(1), || fixture
            .runtime
            .try_wait()
            .expect("runtime status")
            .is_some()),
        "registered runtime did not terminate"
    );
    assert!(fixture.registration.exists(), "registration was removed");
    assert!(
        fixture.descriptor_registry.exists(),
        "descriptor was removed"
    );
    assert!(fixture.socket_path.exists(), "socket was removed");
}

#[test]
fn deleted_workspace_registration_is_repaired_deleted_workspace_registration_review_regression() {
    let mut fixture = RuntimeServiceFixture::new();
    fixture.runtime.kill().expect("stop fixture runtime");
    fixture.runtime.wait().expect("fixture runtime exit");
    std::fs::remove_dir_all(&fixture.workspace).expect("delete workspace");

    let dry_run = fixture
        .repair_command(false)
        .output()
        .expect("deleted workspace dry run");
    assert_success(&dry_run, "deleted workspace dry run");
    let dry_run = output_json(&dry_run);
    assert_eq!(dry_run["state"], "REPAIRABLE");
    assert_eq!(dry_run["actions"][0]["executed"], false);
    assert!(
        fixture.registration.exists(),
        "dry run removed registration"
    );
    assert!(!fixture.workspace.exists(), "dry run recreated workspace");

    let repair = fixture
        .repair_command(true)
        .output()
        .expect("deleted workspace repair");
    assert_success(&repair, "deleted workspace repair");
    let repair = output_json(&repair);
    assert_eq!(repair["state"], "CLEAN");
    assert_eq!(repair["actions"].as_array().map(Vec::len), Some(1));
    assert!(!fixture.workspace.exists(), "repair recreated workspace");
    assert!(!fixture.registration.exists(), "registration remains");
    assert!(!fixture.descriptor_registry.exists(), "descriptor remains");
    assert!(!fixture.socket_path.exists(), "socket remains");
}

#[test]
fn stale_registered_pid_reuse_is_repaired_without_signaling_replacement_review_regression() {
    let mut fixture = RuntimeServiceFixture::new();
    fixture.replace_registered_process_claim_with_reused_pid();
    fixture.orphan_runtime_from_manager();

    let repair = fixture
        .repair_command(true)
        .output()
        .expect("reused PID repair");

    assert_success(&repair, "reused PID repair");
    let repair = output_json(&repair);
    assert_eq!(repair["state"], "CLEAN");
    assert_eq!(repair["actions"].as_array().map(Vec::len), Some(1));
    assert_eq!(repair["actions"][0]["action"], "REMOVE_PROVEN_DEAD_RUNTIME");
    assert_eq!(repair["actions"][0]["executed"], true);
    assert!(
        fixture
            .runtime
            .try_wait()
            .expect("replacement status")
            .is_none(),
        "unrelated replacement was signaled"
    );
    assert!(!fixture.registration.exists(), "registration remains");
    assert!(!fixture.descriptor_registry.exists(), "descriptor remains");
    assert!(!fixture.socket_path.exists(), "socket remains");
}

#[test]
fn missing_workspace_live_manager_stays_blocking_deleted_workspace_registration_review_regression()
{
    let mut fixture = RuntimeServiceFixture::new();
    fixture.replace_registered_process_claim_with_reused_pid();
    std::fs::remove_dir_all(&fixture.workspace).expect("delete workspace");

    let repair = fixture
        .repair_command(true)
        .output()
        .expect("running manager repair");

    assert_success(&repair, "running manager repair");
    let repair = output_json(&repair);
    assert_eq!(repair["state"], "BLOCKED");
    assert_eq!(repair["actions"].as_array().map(Vec::len), Some(0));
    assert!(
        fixture
            .runtime
            .try_wait()
            .expect("replacement status")
            .is_none(),
        "manager process was signaled"
    );
    assert!(fixture.registration.exists(), "registration was removed");
    assert!(
        fixture.descriptor_registry.exists(),
        "descriptor was removed"
    );
    assert!(fixture.socket_path.exists(), "socket was removed");
    assert!(!fixture.workspace.exists(), "workspace was recreated");
}

#[test]
fn matching_descriptor_blocks_reused_pid_cleanup_review_regression() {
    let mut fixture = RuntimeServiceFixture::new();
    fixture.replace_registered_process_claim_with_reused_pid();
    fixture.replace_descriptor_claim_with_current_process();
    fixture.orphan_runtime_from_manager();

    let repair = fixture
        .repair_command(true)
        .output()
        .expect("contradictory descriptor repair");

    assert_success(&repair, "contradictory descriptor repair");
    let repair = output_json(&repair);
    assert_eq!(repair["state"], "BLOCKED");
    assert_eq!(repair["actions"].as_array().map(Vec::len), Some(0));
    assert!(
        fixture
            .runtime
            .try_wait()
            .expect("process status")
            .is_none()
    );
    assert!(fixture.registration.exists(), "registration was removed");
    assert!(
        fixture.descriptor_registry.exists(),
        "descriptor was removed"
    );
    assert!(fixture.socket_path.exists(), "socket was removed");
}

#[test]
fn persisted_descriptor_directory_is_used_for_service_reconciliation_review_regression() {
    let mut fixture = RuntimeServiceFixture::new_with_persisted_descriptor_directory();

    let stop = fixture
        .command()
        .arg("stop")
        .args([
            "--workspace-root",
            fixture.workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("runtime stop");

    assert_success(&stop, "persisted descriptor directory stop");
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
fn nonauthoritative_duplicate_descriptor_blocks_service_stop_review_regression() {
    let mut fixture = RuntimeServiceFixture::new_with_persisted_descriptor_directory();
    let caller_registry = fixture.copy_descriptor_to_caller_projection();

    let stop = fixture
        .command()
        .arg("stop")
        .args([
            "--workspace-root",
            fixture.workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("runtime stop");

    assert_error(&stop, "RUNTIME_OWNERSHIP_AMBIGUOUS");
    assert!(
        fixture
            .runtime
            .try_wait()
            .expect("process status")
            .is_none()
    );
    assert!(fixture.registration.exists(), "registration was removed");
    assert!(
        fixture.descriptor_registry.exists(),
        "descriptor was removed"
    );
    assert!(caller_registry.exists(), "caller descriptor was removed");
    assert!(fixture.socket_path.exists(), "socket was removed");
}
