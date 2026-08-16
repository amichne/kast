#[path = "../support/mod.rs"]
mod support;

use std::os::unix::process::ExitStatusExt;
use std::time::Duration;
use support::*;

#[test]
fn stop_uses_proven_ownership_when_the_semantic_endpoint_is_unservable() {
    let temp = tempfile::tempdir().expect("runtime ownership fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("unresponsive.sock");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");

    let listener = UnixListener::bind(&socket_path).expect("unresponsive endpoint");
    let endpoint = thread::spawn(move || {
        let (_stream, _) = listener.accept().expect("endpoint connection");
    });
    let mut runtime = Command::new("sleep")
        .arg("30")
        .spawn()
        .expect("owned runtime process");
    let descriptor_dir = default_descriptor_dir(&home);
    std::fs::create_dir_all(&descriptor_dir).expect("descriptor directory");
    std::fs::write(
        descriptor_dir.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([
            runtime_descriptor_for_process_test(
                &workspace,
                &socket_path,
                "indexer",
                "durable-ownership-test",
                runtime.id(),
            )
        ]))
        .expect("descriptor JSON"),
    )
    .expect("descriptor registry");

    let stop = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "stop",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("runtime stop");

    endpoint.join().expect("unresponsive endpoint");
    if runtime
        .try_wait()
        .expect("runtime status")
        .is_none()
    {
        runtime.kill().expect("test cleanup");
    }
    let status = runtime.wait().expect("runtime exit");

    assert!(
        stop.status.success(),
        "owned stop failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&stop.stdout),
        String::from_utf8_lossy(&stop.stderr),
    );
    assert!(
        status.success() || status.signal().is_some(),
        "runtime did not terminate: {status}"
    );
    assert!(
        !descriptor_dir.join("daemons.json").exists(),
        "owned descriptor was not removed"
    );
    assert!(
        wait_until(Duration::from_secs(1), || !socket_path.exists()),
        "owned socket was not removed"
    );
}

fn wait_until(timeout: Duration, predicate: impl Fn() -> bool) -> bool {
    let deadline = std::time::Instant::now() + timeout;
    while std::time::Instant::now() < deadline {
        if predicate() {
            return true;
        }
        thread::sleep(Duration::from_millis(10));
    }
    predicate()
}
