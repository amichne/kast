fn process_identity(start_key: &str) -> ManagedProcessIdentity {
    ManagedProcessIdentity {
        pid: 42,
        start_key: start_key.to_string(),
        start_epoch_millis: 1_000,
        owner_uid: 501,
    }
}

#[test]
fn macos_process_disappearance_is_absent_remaining_review_regression() {
    let observed = confirm_macos_process_identity(process_identity("original"), None)
        .expect("a process that exited during observation is absent");

    assert_eq!(observed, None);
}

#[test]
fn macos_pid_reuse_is_conflict_remaining_review_regression() {
    let error = confirm_macos_process_identity(
        process_identity("original"),
        Some(process_identity("replacement")),
    )
    .expect_err("PID reuse must remain an identity conflict");

    assert_eq!(error.code, "RUNTIME_PROCESS_IDENTITY_CHANGED");
}
