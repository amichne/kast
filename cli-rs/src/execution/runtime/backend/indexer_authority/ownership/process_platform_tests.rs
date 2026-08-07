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

#[test]
fn macos_argument_disappearance_is_gone_final_review_regression() {
    for code in [libc::ESRCH, libc::ENOENT] {
        let observed = classify_macos_arguments(
            42,
            Err(std::io::Error::from_raw_os_error(code)),
        )
        .expect("disappearance means the process exited during argument collection");

        assert_eq!(observed, MacosArguments::Gone);
    }
}

#[test]
fn macos_argument_bytes_are_exact_final_review_regression() {
    let mut bytes = 2_i32.to_ne_bytes().to_vec();
    bytes.extend_from_slice(b"/usr/bin/java\0\0java\0--version\0");

    let observed = classify_macos_arguments(42, Ok(bytes))
        .expect("valid KERN_PROCARGS2 bytes are exact argument evidence");

    assert_eq!(
        observed,
        MacosArguments::Exact(vec!["java".to_string(), "--version".to_string()]),
    );
}

#[test]
fn macos_argument_non_esrch_error_is_unavailable_final_review_regression() {
    let error = classify_macos_arguments(
        42,
        Err(std::io::Error::from_raw_os_error(libc::EACCES)),
    )
    .expect_err("non-ESRCH argument failures must remain unavailable evidence");

    assert_eq!(error.code, "RUNTIME_PROCESS_EVIDENCE_UNAVAILABLE");
    assert!(error.message.contains("command arguments"));
}
