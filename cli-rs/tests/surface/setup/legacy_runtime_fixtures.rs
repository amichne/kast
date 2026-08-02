fn spawn_legacy_headless_status_server(
    listener: UnixListener,
    workspace: PathBuf,
) -> std::thread::JoinHandle<bool> {
    listener
        .set_nonblocking(true)
        .expect("nonblocking legacy listener");
    thread::spawn(move || {
        for _ in 0..2 {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
            let (mut stream, _) = loop {
                match listener.accept() {
                    Ok(connection) => break connection,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        if std::time::Instant::now() >= deadline {
                            return false;
                        }
                        thread::sleep(std::time::Duration::from_millis(10));
                    }
                    Err(error) => panic!("accept legacy runtime request: {error}"),
                }
            };
            let mut request_line = String::new();
            BufReader::new(stream.try_clone().expect("clone legacy stream"))
                .read_line(&mut request_line)
                .expect("read legacy request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("legacy request JSON");
            assert_eq!(request["method"], "runtime/status");
            writeln!(
                stream,
                "{}",
                serde_json::json!({
                    "jsonrpc": "2.0",
                    "id": request["id"],
                    "result": {
                        "state": "READY",
                        "healthy": true,
                        "active": true,
                        "indexing": false,
                        "backendName": "headless",
                        "backendVersion": "legacy-test",
                        "workspaceRoot": workspace.display().to_string(),
                        "schemaVersion": 5
                    }
                })
            )
            .expect("write legacy response");
        }
        true
    })
}

fn spawn_reapable_process() -> (u32, std::sync::mpsc::Receiver<std::process::ExitStatus>) {
    let mut child = Command::new("sleep").arg("30").spawn().expect("fixture process");
    let pid = child.id();
    let (sender, receiver) = std::sync::mpsc::channel();
    thread::spawn(move || {
        let status = child.wait().expect("reap fixture process");
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
