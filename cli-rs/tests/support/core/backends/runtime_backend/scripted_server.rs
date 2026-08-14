#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ScriptedRuntimeAuthority {
    PublishExact,
    ReuseRegistered,
}

#[allow(clippy::too_many_arguments)]
fn spawn_scripted_backend_with_additional_runtime_status_requests(
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
    additional_runtime_status_requests: usize,
    reject_unexpected_methods: bool,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
    runtime_authority: ScriptedRuntimeAuthority,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    assert!(invocation_count > 0, "scripted backend needs an invocation");
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    let workspace = std::fs::canonicalize(workspace).expect("canonical scripted workspace");
    publish_scripted_workspace_capabilities(&workspace);
    let listener = UnixListener::bind(socket_path).expect("bind scripted backend");
    let exact_test_runtime = match runtime_authority {
        ScriptedRuntimeAuthority::PublishExact => Some(publish_exact_test_runtime(
            home,
            &workspace,
            socket_path,
            backend_name,
            "scripted-test",
            &descriptor_dir,
        )),
        ScriptedRuntimeAuthority::ReuseRegistered => None,
    };
    listener
        .set_nonblocking(true)
        .expect("nonblocking scripted backend");
    let server_workspace = workspace;
    let server_backend_name = backend_name.to_string();
    thread::spawn(move || {
        let _exact_test_runtime = exact_test_runtime;
        let mut requests = Vec::new();
        let mut mutation_gate = mutation_gate;
        let mut scratch_crash_gate = scratch_crash_gate;
        let mut scripted_results = scripted_results.into_iter();
        let expected_requests =
            2 * invocation_count + additional_runtime_status_requests + scripted_results.len();
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
                "runtime/status" => {
                    let mut status = serde_json::json!({
                        "state": "READY",
                        "backendName": server_backend_name.as_str(),
                        "backendVersion": "scripted-test",
                        "workspaceRoot": server_workspace.display().to_string(),
                        "sourceModuleNames": [":fixture"],
                        "readiness": {
                            "runtime": {"type": "READY"},
                            "model": {"type": "READY"},
                            "references": {"type": if semantic_ready { "READY" } else { "BLOCKED" }},
                            "semanticGraph": {"type": if semantic_ready { "READY" } else { "BLOCKED" }},
                            "mutation": {"type": "READY"}
                        },
                        "schemaVersion": 7
                    });
                    if let Some(published) =
                        published_workspace_generation_for_test(&server_workspace)
                    {
                        status["publishedWorkspaceGeneration"] = published;
                    }
                    status
                }
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
                    "schemaVersion": 7
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
                    } else if reject_unexpected_methods {
                        panic!(
                            "strict scripted backend rejected method: {method}; next={scripted_method:?}"
                        );
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

include!("scripted_server/exact_runtime.rs");

pub(crate) fn publish_scripted_workspace_capabilities(workspace: &Path) {
    let database = workspace_database_path_for_test(workspace);
    if !database.is_file() {
        crate::support::metrics::seed_source_index(workspace);
    }
    let connection = rusqlite::Connection::open(&database).expect("scripted source index");
    connection
        .execute("UPDATE schema_version SET generation = 1 WHERE generation = 0", [])
        .expect("scripted source revision");
    publish_workspace_database_for_test(workspace);
}
