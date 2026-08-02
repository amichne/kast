pub(crate) fn spawn_scripted_headless_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "headless",
        1,
        false,
        vec![],
        scripted_results,
    )
}

pub(crate) fn spawn_scripted_mutating_headless_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "headless",
        1,
        false,
        vec!["RENAME", "APPLY_EDITS"],
        scripted_results,
    )
}

pub(crate) fn runtime_descriptor_for_test(
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    backend_version: &str,
) -> serde_json::Value {
    use std::os::unix::fs::MetadataExt;

    let socket = std::fs::metadata(socket_path).expect("bound runtime socket identity");
    let output = Command::new("ps")
        .env("LC_ALL", "C")
        .args(["-o", "lstart=", "-p", &std::process::id().to_string()])
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
        "runtimeInstanceId": format!("test-{}-{}", std::process::id(), socket.ino()),
        "processStartEpochMillis": u64::try_from(start_epoch_seconds).expect("process start") * 1_000,
        "ownerUid": u64::from(unsafe { libc::geteuid() }),
        "socketFileIdentity": {"device": socket.dev(), "inode": socket.ino()},
        "transport": "uds",
        "socketPath": socket_path.display().to_string(),
        "pid": std::process::id(),
        "schemaVersion": 5
    })
}

pub(crate) fn spawn_scripted_headless_backend_for_invocations(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    invocation_count: usize,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "headless",
        invocation_count,
        false,
        vec![],
        scripted_results,
    )
}

pub(crate) fn spawn_ready_headless_backend_after_marker(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    marker: &Path,
    invocation_count: usize,
) -> std::thread::JoinHandle<Option<Vec<serde_json::Value>>> {
    let home = home.to_path_buf();
    let config_home = config_home.to_path_buf();
    let workspace = workspace.to_path_buf();
    let socket_path = socket_path.to_path_buf();
    let marker = marker.to_path_buf();
    thread::spawn(move || {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while !marker.is_file() && std::time::Instant::now() < deadline {
            thread::sleep(std::time::Duration::from_millis(10));
        }
        if !marker.is_file() {
            return None;
        }
        let observation_deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
        while std::time::Instant::now() < observation_deadline {
            let launches = std::fs::read_to_string(&marker)
                .unwrap_or_default()
                .lines()
                .filter(|line| *line == "__KAST_SIDECAR_LAUNCH__")
                .count();
            if launches >= invocation_count {
                break;
            }
            thread::sleep(std::time::Duration::from_millis(10));
        }
        Some(
            spawn_scripted_backend(
                &home,
                &config_home,
                &workspace,
                &socket_path,
                "headless",
                invocation_count,
                true,
                vec![],
                vec![],
            )
            .join()
            .expect("ready headless backend"),
        )
    })
}

#[allow(clippy::too_many_arguments)]
fn spawn_scripted_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    invocation_count: usize,
    semantic_ready: bool,
    mutation_capabilities: Vec<&'static str>,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    assert!(invocation_count > 0, "scripted backend needs an invocation");
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = std::fs::canonicalize(workspace).expect("canonical scripted workspace");
    std::fs::write(
        config_home.join("config.toml"),
        format!("[runtime]\ndefaultBackend = \"{backend_name}\"\n"),
    )
    .expect("config");
    let listener = UnixListener::bind(socket_path).expect("bind scripted backend");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_test(
            &workspace,
            socket_path,
            backend_name,
            "scripted-test",
        )]))
        .expect("descriptor json"),
    )
    .expect("descriptor");
    listener
        .set_nonblocking(true)
        .expect("nonblocking scripted backend");
    let server_workspace = workspace;
    let server_backend_name = backend_name.to_string();
    thread::spawn(move || {
        let mut requests = Vec::new();
        let mut scripted_results = scripted_results.into_iter();
        let expected_requests = 2 * invocation_count + scripted_results.len();
        let mut idle_deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        while requests.len() < expected_requests || scripted_results.len() > 0 {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    if std::time::Instant::now() >= idle_deadline {
                        return requests;
                    }
                    thread::sleep(std::time::Duration::from_millis(10));
                    continue;
                }
                Err(error) => panic!("accept scripted backend client: {error}"),
            };
            stream
                .set_nonblocking(false)
                .expect("blocking scripted backend stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            let method = request["method"].as_str().expect("method");
            let result = match method {
                "runtime/status" => serde_json::json!({
                    "state": "READY",
                    "healthy": true,
                    "active": true,
                    "indexing": false,
                    "backendName": server_backend_name.as_str(),
                    "backendVersion": "scripted-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "sourceModuleNames": if semantic_ready { vec![":fixture"] } else { vec![] },
                    "referenceIndexReady": semantic_ready,
                    "schemaVersion": 5
                }),
                "capabilities" => serde_json::json!({
                    "backendName": server_backend_name.as_str(),
                    "backendVersion": "scripted-test",
                    "workspaceRoot": server_workspace.display().to_string(),
                    "readCapabilities": [
                        "symbol/resolve",
                        "symbol/references",
                        "symbol/callers",
                        "symbol/implementations",
                        "symbol/hierarchy",
                        "raw/call-hierarchy",
                        "raw/implementations",
                        "raw/type-hierarchy"
                    ],
                    "mutationCapabilities": mutation_capabilities.clone(),
                    "limits": {
                        "requestTimeoutMillis": 60000,
                        "maxResults": 1000,
                        "maxConcurrentRequests": 4
                    },
                    "schemaVersion": 5
                }),
                _ => {
                    let (expected_method, result) = scripted_results
                        .next()
                        .unwrap_or_else(|| panic!("unexpected scripted method: {method}"));
                    assert_eq!(method, expected_method, "scripted method order");
                    result
                }
            };
            requests.push(request);
            idle_deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write scripted response");
        }
        requests
    })
}

pub(crate) fn spawn_sequenced_headless_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    responses: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = std::fs::canonicalize(workspace).expect("canonical sequenced workspace");
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"headless\"\n",
    )
    .expect("config");
    let listener = UnixListener::bind(socket_path).expect("bind sequenced backend");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_test(
            &workspace,
            socket_path,
            "headless",
            "scripted-test",
        )]))
        .expect("descriptor json"),
    )
    .expect("descriptor");
    listener
        .set_nonblocking(true)
        .expect("nonblocking sequenced backend");

    thread::spawn(move || {
        let mut requests = Vec::with_capacity(responses.len());
        for (expected_method, result) in responses {
            let idle_deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
            let (mut stream, _) = loop {
                match listener.accept() {
                    Ok(connection) => break connection,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        if std::time::Instant::now() >= idle_deadline {
                            return requests;
                        }
                        thread::sleep(std::time::Duration::from_millis(10));
                    }
                    Err(error) => panic!("accept sequenced client: {error}"),
                }
            };
            stream
                .set_nonblocking(false)
                .expect("blocking sequenced backend stream");
            let mut reader = BufReader::new(stream.try_clone().expect("clone stream"));
            let mut request_line = String::new();
            reader.read_line(&mut request_line).expect("read request");
            let request: serde_json::Value =
                serde_json::from_str(&request_line).expect("request json");
            assert_eq!(request["method"], expected_method, "scripted method order");
            writeln!(
                stream,
                "{}",
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            )
            .expect("write sequenced response");
            requests.push(request);
        }
        requests
    })
}

#[cfg(target_os = "macos")]
fn default_socket_path_for_test(workspace: &Path) -> PathBuf {
    use sha2::{Digest, Sha256};

    let normalized: PathBuf = workspace.components().collect();
    let digest = Sha256::digest(normalized.to_string_lossy().as_bytes());
    std::env::temp_dir().join(format!("kast-{}.sock", &hex::encode(digest)[0..12]))
}

pub(crate) fn path_report_entry<'a>(
    report: &'a serde_json::Value,
    key: &str,
) -> &'a serde_json::Value {
    report["entries"]
        .as_array()
        .expect("path report entries")
        .iter()
        .find(|entry| entry["key"] == key)
        .unwrap_or_else(|| panic!("missing path report entry {key}: {report:#?}"))
}
