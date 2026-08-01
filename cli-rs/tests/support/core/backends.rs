pub(crate) fn spawn_scripted_idea_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    write_macos_plugin_workspace_metadata(workspace);
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "idea",
        1,
        false,
        scripted_results,
    )
}

pub(crate) struct ScriptedCliAuthority<'a> {
    binary: &'a Path,
    version: &'a str,
}

impl<'a> ScriptedCliAuthority<'a> {
    pub(crate) fn new(binary: &'a Path, version: &'a str) -> Self {
        assert!(binary.is_file(), "scripted CLI authority binary");
        assert!(!version.trim().is_empty(), "scripted CLI authority version");
        Self { binary, version }
    }
}

pub(crate) fn spawn_scripted_idea_backend_for_invocations(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    cli_authority: ScriptedCliAuthority<'_>,
    invocation_count: usize,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    write_macos_plugin_workspace_metadata_for_cli(
        workspace,
        cli_authority.binary,
        cli_authority.version,
    );
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "idea",
        invocation_count,
        false,
        scripted_results,
    )
}

pub(crate) fn spawn_scripted_headless_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "headless",
        1,
        false,
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
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    assert!(invocation_count > 0, "scripted backend needs an invocation");
    let descriptor_dir = default_descriptor_dir(home);
    std::fs::create_dir_all(home).expect("home");
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::create_dir_all(config_home).expect("config home");
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor dir");
    std::fs::write(
        config_home.join("config.toml"),
        format!("[runtime]\ndefaultBackend = \"{backend_name}\"\n"),
    )
    .expect("config");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([{
            "workspaceRoot": workspace.display().to_string(),
            "backendName": backend_name,
            "backendVersion": "scripted-test",
            "transport": "uds",
            "socketPath": socket_path.display().to_string(),
            "pid": std::process::id(),
            "schemaVersion": 5
        }]))
        .expect("descriptor json"),
    )
    .expect("descriptor");

    let listener = UnixListener::bind(socket_path).expect("bind scripted backend");
    listener
        .set_nonblocking(true)
        .expect("nonblocking scripted backend");
    let server_workspace = workspace.to_path_buf();
    let server_backend_name = backend_name.to_string();
    thread::spawn(move || {
        let mut requests = Vec::new();
        let mut scripted_results = scripted_results.into_iter();
        let expected_requests = 2 * invocation_count + scripted_results.len();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
        while (requests.len() < expected_requests || scripted_results.len() > 0)
            && std::time::Instant::now() < deadline
        {
            let (mut stream, _) = match listener.accept() {
                Ok(connection) => connection,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
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
                    "mutationCapabilities": [],
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

pub(crate) fn spawn_sequenced_idea_backend(
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
    write_macos_plugin_workspace_metadata(workspace);
    std::fs::write(
        config_home.join("config.toml"),
        "[runtime]\ndefaultBackend = \"idea\"\n",
    )
    .expect("config");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([{
            "workspaceRoot": workspace.display().to_string(),
            "backendName": "idea",
            "backendVersion": "scripted-test",
            "transport": "uds",
            "socketPath": socket_path.display().to_string(),
            "pid": std::process::id(),
            "schemaVersion": 5
        }]))
        .expect("descriptor json"),
    )
    .expect("descriptor");

    let listener = UnixListener::bind(socket_path).expect("bind sequenced backend");
    thread::spawn(move || {
        let mut requests = Vec::with_capacity(responses.len());
        for (expected_method, result) in responses {
            let (mut stream, _) = listener.accept().expect("accept sequenced client");
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
