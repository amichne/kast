#[test]
fn setup_waits_for_a_runtime_shared_install_lock_before_mutating() {
    use std::os::fd::AsRawFd as _;

    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let kast_home = home.join(".local/share/kast");
    let first_source = write_install_bundle_source(temp.path(), "v1.0.0");
    let second_source = write_install_bundle_source(temp.path(), "v2.0.0");
    assert!(setup(&home, &kast_home, &first_source).status.success());
    let current_before = std::fs::read_link(kast_home.join("current")).expect("current release");
    let lock_path = kast_home.join("setup.lock");
    let barrier = temp.path().join("before-install-lock");
    let mut child = setup_command(&home, &kast_home, &second_source)
        .env("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION", "1")
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER", &barrier)
        .env(
            "KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE",
            "before-install-lock-acquire",
        )
        .env("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_PATH", &lock_path)
        .spawn()
        .expect("spawn setup lock contender");
    wait_for_setup_barrier(&mut child, &barrier, "before-install-lock-acquire");

    let shared_lock = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(&lock_path)
        .expect("runtime install-use lock");
    assert_eq!(
        unsafe { libc::flock(shared_lock.as_raw_fd(), libc::LOCK_SH) },
        0,
        "acquire runtime shared lock",
    );
    release_setup_barrier(&barrier, "before-install-lock-acquire");
    std::thread::sleep(std::time::Duration::from_millis(150));
    assert!(
        child.try_wait().expect("setup process state").is_none(),
        "setup did not wait for the runtime install-use lock",
    );
    assert_eq!(
        std::fs::read_link(kast_home.join("current")).expect("unchanged current release"),
        current_before,
    );

    assert_eq!(
        unsafe { libc::flock(shared_lock.as_raw_fd(), libc::LOCK_UN) },
        0,
        "release runtime shared lock",
    );
    let output = child.wait_with_output().expect("setup output");
    assert!(
        output.status.success(),
        "setup should continue after the runtime lock is released: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert_ne!(
        std::fs::read_link(kast_home.join("current")).expect("replacement current release"),
        current_before,
    );
}
