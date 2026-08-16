#[test]
fn background_runtime_start_requires_explicit_indexing_consent_before_resolution() {
    let args = semantic_runtime_args(Some(PathBuf::from("/missing-workspace")), false, true);

    let error = semantic_runtime_request_for_background(args)
        .expect_err("background start without consent must fail closed");

    assert_eq!(error.code, "BACKGROUND_INDEXER_CONSENT_REQUIRED");
}

#[cfg(unix)]
#[test]
fn launch_lock_respects_the_runtime_start_deadline() {
    let temp = tempfile::tempdir().expect("fixture");
    let workspace = temp.path().join("workspace");
    fs::create_dir(&workspace).expect("workspace");
    let mut config = KastConfig::defaults();
    config.paths.cache_dir = temp.path().join("cache");
    let identity = daemon::IndexerStorageIdentity::resolve(&workspace, &config)
        .expect("storage identity");
    let holder = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(identity.launch_lock_file())
        .expect("launch lock holder");
    assert_eq!(
        unsafe { libc::flock(std::os::fd::AsRawFd::as_raw_fd(&holder), libc::LOCK_EX) },
        0,
    );
    let started = Instant::now();

    let result = WorkspaceLaunchLock::acquire_until(
        &config,
        &workspace,
        RuntimeStartDeadline::after_millis(40),
    );

    let error = result.err().expect("contended launch lock must time out");
    assert_eq!(error.code, "RUNTIME_LAUNCH_LOCK_TIMEOUT");
    assert!(started.elapsed() < Duration::from_secs(1));
}
