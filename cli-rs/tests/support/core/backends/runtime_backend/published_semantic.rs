pub(crate) struct PublishedSemanticReadBackend {
    stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
    handle: std::thread::JoinHandle<Vec<serde_json::Value>>,
}

impl PublishedSemanticReadBackend {
    pub(crate) fn finish(self) -> Vec<serde_json::Value> {
        self.stop
            .store(true, std::sync::atomic::Ordering::Release);
        self.handle
            .join()
            .expect("published semantic backend thread")
    }
}

pub(crate) fn spawn_open_published_semantic_read_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
) -> PublishedSemanticReadBackend {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = std::fs::canonicalize(workspace).expect("canonical published workspace");
    let listener = UnixListener::bind(socket_path).expect("bind published semantic backend");
    let exact_test_runtime = publish_exact_test_runtime(
        home,
        &workspace,
        socket_path,
        "indexer",
        "scripted-test",
        &descriptor_dir,
    );
    listener
        .set_nonblocking(true)
        .expect("nonblocking published semantic backend");
    let published = published_workspace_generation_for_test(&workspace);
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let server_stop = std::sync::Arc::clone(&stop);
    let handle = thread::spawn(move || {
        let _exact_test_runtime = exact_test_runtime;
        let mut requests = Vec::new();
        while !server_stop.load(std::sync::atomic::Ordering::Acquire) {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(std::time::Duration::from_millis(1));
                    continue;
                }
                Err(error) => panic!("accept published semantic client: {error}"),
            };
            stream
                .set_nonblocking(false)
                .expect("blocking published semantic stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request JSON");
            let result = match request["method"].as_str().expect("request method") {
                "runtime/status" => {
                    let mut status = serde_json::json!({
                        "state": "READY",
                        "backendName": "indexer",
                        "backendVersion": "scripted-test",
                        "workspaceRoot": workspace.display().to_string(),
                        "sourceModuleNames": [":fixture"],
                        "readiness": {
                            "runtime": {"type": "READY"},
                            "model": {"type": "READY"},
                            "references": {"type": "READY"},
                            "semanticGraph": {"type": "READY"},
                            "mutation": {"type": "READY"}
                        },
                        "schemaVersion": 7
                    });
                    if let Some(published) = &published {
                        status["publishedWorkspaceGeneration"] = published.clone();
                    }
                    status
                }
                "capabilities" => serde_json::json!({
                    "backendName": "indexer",
                    "backendVersion": "scripted-test",
                    "workspaceRoot": workspace.display().to_string(),
                    "readCapabilities": ["SEMANTIC_GRAPH"],
                    "mutationCapabilities": [],
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 7
                }),
                method => panic!("unexpected published semantic method: {method}"),
            };
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("published semantic response");
            requests.push(request);
        }
        requests
    });
    PublishedSemanticReadBackend { stop, handle }
}

pub(crate) fn runtime_descriptor_for_test(
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    backend_version: &str,
) -> serde_json::Value {
    runtime_descriptor_for_process_test(
        workspace,
        socket_path,
        backend_name,
        backend_version,
        std::process::id(),
    )
}
pub(crate) fn runtime_descriptor_for_process_test(
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    backend_version: &str,
    pid: u32,
) -> serde_json::Value {
    use std::os::unix::fs::MetadataExt;

    let socket = std::fs::metadata(socket_path).expect("bound runtime socket identity");
    let output = Command::new("ps")
        .env("LC_ALL", "C")
        .args(["-o", "lstart=", "-p", &pid.to_string()])
        .output()
        .expect("process start observation");
    assert!(output.status.success(), "process start observation");
    let started_at = std::ffi::CString::new(String::from_utf8(output.stdout).expect("UTF-8 ps output").trim())
        .expect("process start contains no NUL");
    let mut parsed = unsafe { std::mem::zeroed::<libc::tm>() };
    parsed.tm_isdst = -1;
    assert!(
        !unsafe { libc::strptime(started_at.as_ptr(), c"%a %b %e %T %Y".as_ptr(), &mut parsed) }
            .is_null(),
        "parse process start"
    );
    let start_epoch_seconds = unsafe { libc::mktime(&mut parsed) };
    assert!(start_epoch_seconds > 0, "positive process start");
    serde_json::json!({
        "workspaceRoot": workspace.display().to_string(),
        "backendName": backend_name,
        "backendVersion": backend_version,
        "runtimeInstanceId": format!("test-{pid}-{}", socket.ino()),
        "processStartEpochMillis": u64::try_from(start_epoch_seconds).expect("process start") * 1_000,
        "ownerUid": u64::from(unsafe { libc::geteuid() }),
        "socketFileIdentity": {"device": socket.dev(), "inode": socket.ino()},
        "transport": "uds",
        "socketPath": socket_path.display().to_string(),
        "pid": pid,
        "schemaVersion": 7
    })
}
