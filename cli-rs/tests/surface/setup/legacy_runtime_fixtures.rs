fn spawn_reapable_process() -> (u32, std::sync::mpsc::Receiver<std::process::ExitStatus>) {
    let mut child = Command::new("sleep")
        .arg("900")
        .spawn()
        .expect("fixture process");
    let pid = child.id();
    let (sender, receiver) = std::sync::mpsc::channel();
    thread::spawn(move || {
        let status = child.wait().expect("reap fixture process");
        let _ = sender.send(status);
    });
    (pid, receiver)
}

fn spawn_reapable_indexer_process(
    workspace: &Path,
    socket_path: &Path,
) -> (u32, std::sync::mpsc::Receiver<std::process::ExitStatus>) {
    let mut child = Command::new("python3")
        .args([
            "-c",
            "import signal, sys; signal.signal(signal.SIGTERM, lambda *_: sys.exit(0)); signal.pause()",
            "kast-indexer",
        ])
        .arg(format!("--workspace-root={}", workspace.display()))
        .arg(format!("--socket-path={}", socket_path.display()))
        .spawn()
        .expect("fixture indexer process");
    let pid = child.id();
    let (sender, receiver) = std::sync::mpsc::channel();
    thread::spawn(move || {
        let status = child.wait().expect("reap fixture indexer process");
        let _ = sender.send(status);
    });
    (pid, receiver)
}

fn write_legacy_headless_descriptor(
    home: &Path,
    workspace: &Path,
    socket_path: &Path,
    pid: u32,
    process_start_override: Option<u64>,
    preserved: &[serde_json::Value],
) {
    let mut descriptor = runtime_descriptor_for_process_test(
        workspace,
        socket_path,
        "headless",
        "legacy-test",
        pid,
    );
    descriptor["schemaVersion"] = prior_api_schema_version().into();
    if let Some(process_start_epoch_millis) = process_start_override {
        descriptor["processStartEpochMillis"] = process_start_epoch_millis.into();
    }
    let mut entries = vec![descriptor];
    entries.extend_from_slice(preserved);
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor directory");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&entries).expect("descriptor JSON"),
    )
    .expect("descriptor registry");
}

fn terminate_fixture_process(pid: u32) {
    unsafe {
        libc::kill(pid.cast_signed(), libc::SIGKILL);
    }
}
