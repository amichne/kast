use super::*;

pub(crate) fn spawn_sequenced_indexer_backend(
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
    let listener = UnixListener::bind(socket_path).expect("bind sequenced backend");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([runtime_descriptor_for_test(
            &workspace,
            socket_path,
            "indexer",
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
pub(super) fn default_socket_path_for_test(workspace: &Path) -> PathBuf {
    use sha2::{Digest, Sha256};

    let normalized: PathBuf = workspace.components().collect();
    let digest = Sha256::digest(normalized.to_string_lossy().as_bytes());
    std::env::temp_dir().join(format!(
        "kast-indexer-{}.sock",
        &hex::encode(digest)[0..12]
    ))
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
