use super::*;

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
fn running_service_manager_blocks_reused_pid_cleanup_review_regression() {
    let mut fixture = RuntimeServiceFixture::new();
    fixture.replace_registered_process_claim_with_reused_pid();

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
