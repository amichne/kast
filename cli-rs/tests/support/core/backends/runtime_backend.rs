use super::*;

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
        "schemaVersion": 6
    })
}

pub(crate) fn spawn_scripted_indexer_backend_for_invocations(
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
        "indexer",
        invocation_count,
        false,
        vec![],
        None,
        None,
        None,
        None,
        scripted_results,
    )
}

pub(crate) fn spawn_ready_indexer_backend_after_marker(
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
                "indexer",
                invocation_count,
                true,
                vec![],
                None,
                None,
                None,
                None,
                vec![],
            )
            .join()
            .expect("ready indexer"),
        )
    })
}

#[allow(clippy::too_many_arguments)]
pub(super) fn spawn_scripted_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    invocation_count: usize,
    semantic_ready: bool,
    mutation_capabilities: Vec<&'static str>,
    _mutation_file_write: Option<(PathBuf, Vec<u8>)>,
    mutation_gate: Option<(PathBuf, PathBuf)>,
    keepalive_until: Option<PathBuf>,
    scratch_crash_gate: Option<ScriptedScratchCrashGate>,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    assert!(invocation_count > 0, "scripted backend needs an invocation");
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = std::fs::canonicalize(workspace).expect("canonical scripted workspace");
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
        let mut mutation_gate = mutation_gate;
        let mut scratch_crash_gate = scratch_crash_gate;
        let mut scripted_results = scripted_results.into_iter();
        let expected_requests = 2 * invocation_count + scripted_results.len();
        let mut unified_session_active = false;
        let mut unified_session_complete = false;
        let mut unified_semantic_verification_complete = false;
        let mut idle_deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        while requests.len() < expected_requests
            || scripted_results.len() > 0
            || (unified_session_active
                && !unified_session_complete
                && std::time::Instant::now() < idle_deadline)
            || keepalive_until
                .as_ref()
                .is_some_and(|marker| !marker.exists())
        {
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
                    "schemaVersion": 6
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
                    "schemaVersion": 6
                }),
                _ => {
                    if matches!(
                        method,
                        "raw/rename"
                            | "raw/plan-replacement"
                            | "raw/plan-add-file"
                            | "raw/plan-add-declaration"
                            | "raw/exact-file-observation"
                            | "raw/inspect-mutation-scratch"
                            | "raw/recover-mutation-scratch"
                            | "raw/exact-file-image-cas"
                            | "raw/apply-edits"
                            | "raw/workspace-refresh"
                            | "raw/diagnostics"
                            | "raw/verify-mutation-postcondition"
                    )
                    {
                        unified_session_active = true;
                    }
                    if method == "raw/apply-edits"
                        && let Some((entered_marker, release_marker)) = mutation_gate.take()
                    {
                        std::fs::write(&entered_marker, "entered\n")
                            .expect("mutation gate entered marker");
                        let deadline =
                            std::time::Instant::now() + std::time::Duration::from_secs(10);
                        while !release_marker.is_file() && std::time::Instant::now() < deadline {
                            thread::sleep(std::time::Duration::from_millis(10));
                        }
                        assert!(release_marker.is_file(), "mutation gate release marker");
                    }
                    let scratch_crash_result = scratch_crash_gate
                        .as_ref()
                        .is_some_and(|gate| gate.mode.method() == method)
                        .then(|| {
                            retain_declared_scratch_until_release(
                                &request,
                                scratch_crash_gate.take().expect("matching scratch crash gate"),
                            )
                        });
                    let scripted_method = scripted_results
                        .as_slice()
                        .first()
                        .map(|(expected, _)| *expected);
                    let legacy_mutation_result = (scratch_crash_result.is_none()
                        && method == "raw/apply-edits"
                        && scripted_method == Some("mutation/submit"))
                        .then(|| scripted_results.next().expect("legacy mutation result").1);
                    let result = if let Some(result) = scratch_crash_result {
                        result
                    } else if scripted_method == Some(method) {
                        let scripted = scripted_results.next().expect("scripted result").1;
                        if scripted["__kastTestApplyDefaultMutation"] == true {
                            unified_raw_result(
                                &server_workspace,
                                &request,
                                legacy_mutation_result,
                            )
                            .expect("default mutation side effect for scripted JSON-RPC error");
                        }
                        if let Some(path) = scripted["__kastTestRetainedArtifactPath"].as_str() {
                            use base64::{Engine as _, engine::general_purpose::STANDARD};
                            let contents = STANDARD
                                .decode(
                                    scripted["__kastTestRetainedArtifactBase64"]
                                        .as_str()
                                        .expect("retained artifact Base64"),
                                )
                                .expect("retained artifact bytes");
                            std::fs::write(path, contents).expect("retained backend artifact");
                        }
                        scripted
                    } else if let Some(result) = unified_raw_result(
                        &server_workspace,
                        &request,
                        legacy_mutation_result,
                    ) {
                        result
                    } else {
                        panic!(
                            "unexpected scripted method: {method}; next={scripted_method:?}"
                        );
                    };
                    if method == "raw/verify-mutation-postcondition"
                        && result.get("__kastTestJsonRpcError").is_none()
                    {
                        unified_semantic_verification_complete = true;
                    } else if method == "raw/inspect-mutation-scratch"
                        && unified_semantic_verification_complete
                    {
                        unified_session_complete = true;
                    }
                    result
                }
            };
            requests.push(request);
            idle_deadline = std::time::Instant::now()
                + if unified_session_active {
                    std::time::Duration::from_secs(1)
                } else {
                    std::time::Duration::from_secs(10)
                };
            let response = if let Some(error) = result.get("__kastTestJsonRpcError") {
                serde_json::json!({"jsonrpc":"2.0","id":1,"error":error})
            } else {
                serde_json::json!({"jsonrpc":"2.0","id":1,"result":result})
            };
            if let Err(error) = writeln!(stream, "{}", response) {
                if error.kind() == std::io::ErrorKind::BrokenPipe {
                    return requests;
                }
                panic!("write scripted response: {error}");
            }
        }
        requests
    })
}
