pub(crate) struct ExactTestRuntimeProcess(std::process::Child);

impl Drop for ExactTestRuntimeProcess {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

pub(crate) fn publish_exact_test_runtime(
    home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    backend_version: &str,
    descriptor_directory: &Path,
) -> ExactTestRuntimeProcess {
    use sha2::{Digest as _, Sha256};
    use std::os::unix::fs::PermissionsExt as _;

    let workspace_key = hex::encode(Sha256::digest(workspace.to_string_lossy().as_bytes()));
    let workspace_directory = default_install_root(home)
        .join("state/runtime/services")
        .join(&workspace_key);
    if workspace_directory.join("active.json").is_file() {
        discard_dead_exact_test_runtime(&workspace_directory, home);
    }

    let runtime_instance_id = uuid::Uuid::new_v4();
    let runtime_directory = workspace_directory.join(runtime_instance_id.to_string());
    let test_manager_root = default_install_root(home).join("state/runtime/test-manager");
    for directory in [
        workspace_directory.as_path(),
        runtime_directory.as_path(),
        test_manager_root.as_path(),
    ] {
        std::fs::create_dir_all(directory).expect("exact scripted runtime directory");
        std::fs::set_permissions(directory, std::fs::Permissions::from_mode(0o700))
            .expect("exact scripted runtime directory permissions");
    }
    let workspace_directory =
        std::fs::canonicalize(workspace_directory).expect("canonical service workspace");
    let runtime_directory =
        std::fs::canonicalize(runtime_directory).expect("canonical runtime registration");

    let shell = std::fs::canonicalize("/bin/sh").expect("canonical test runtime shell");
    let shell_program = "trap 'exit 0' TERM INT; while :; do sleep 1; done";
    let socket_argument = format!("--socket-path={}", socket_path.display());
    let child = Command::new(&shell)
        .args(["-c", shell_program, &socket_argument])
        .spawn()
        .expect("exact scripted runtime process");
    let process = ExactTestRuntimeProcess(child);
    let pid = process.0.id();
    let command = vec![
        shell.display().to_string(),
        "-c".to_string(),
        shell_program.to_string(),
        socket_argument,
    ];

    let runtime_config_path = runtime_directory.join("runtime-config.json");
    let runtime_config_bytes = b"{}";
    write_private_test_file(&runtime_config_path, runtime_config_bytes);
    let launcher = std::fs::canonicalize(std::env::current_exe().expect("test executable"))
        .expect("canonical test executable");
    let launcher_bytes = std::fs::read(&launcher).expect("test executable bytes");
    let launch = serde_json::json!({
        "schemaVersion": 1,
        "workspaceRoot": workspace.display().to_string(),
        "workspaceKey": workspace_key.clone(),
        "runtimeInstanceId": runtime_instance_id,
        "ownerUid": u64::from(unsafe { libc::geteuid() }),
        "workingDirectory": workspace.display().to_string(),
        "command": command,
        "environment": [],
        "logFile": runtime_directory.join("scripted-runtime.log").display().to_string(),
        "descriptorDirectory": descriptor_directory.display().to_string(),
        "socketPath": socket_path.display().to_string(),
        "launcherPath": launcher.display().to_string(),
        "launcherSha256": hex::encode(Sha256::digest(&launcher_bytes)),
        "runtimeConfigPath": runtime_config_path.display().to_string(),
        "runtimeConfigSha256": hex::encode(Sha256::digest(runtime_config_bytes)),
    });
    let launch_bytes = serde_json::to_vec_pretty(&launch).expect("test launch JSON");
    let launch_sha256 = hex::encode(Sha256::digest(&launch_bytes));
    let launch_path = runtime_directory.join("launch.json");
    write_private_test_file(&launch_path, &launch_bytes);

    let definition_path = runtime_directory.join("service.test.json");
    let definition = serde_json::to_vec_pretty(&serde_json::json!({
        "launcher": launcher,
        "registration": launch_path,
        "registrationSha256": launch_sha256.clone(),
    }))
    .expect("test service definition JSON");
    write_private_test_file(&definition_path, &definition);
    let manager_state_path = test_manager_root.join(format!("{runtime_instance_id}.json"));
    write_private_test_file(
        &manager_state_path,
        &serde_json::to_vec(&serde_json::json!({"pid": pid}))
            .expect("test manager state JSON"),
    );
    let receipt = serde_json::json!({
        "schemaVersion": 1,
        "workspaceRoot": workspace.display().to_string(),
        "workspaceKey": workspace_key,
        "runtimeInstanceId": runtime_instance_id,
        "launchPath": launch_path.display().to_string(),
        "launchSha256": launch_sha256,
        "definitionSha256": hex::encode(Sha256::digest(&definition)),
        "manager": {
            "kind": "TEST",
            "state_path": manager_state_path.display().to_string(),
            "definition_path": definition_path.display().to_string(),
        },
    });
    let receipt_bytes = serde_json::to_vec_pretty(&receipt).expect("test receipt JSON");
    write_private_test_file(&runtime_directory.join("receipt.json"), &receipt_bytes);
    write_private_test_file(
        &workspace_directory.join("active.json"),
        &serde_json::to_vec_pretty(&serde_json::json!({
            "schemaVersion": 1,
            "runtimeInstanceId": runtime_instance_id,
            "receiptSha256": hex::encode(Sha256::digest(&receipt_bytes)),
        }))
        .expect("test active registration JSON"),
    );
    let mut descriptor = runtime_descriptor_for_process_test(
        workspace,
        socket_path,
        backend_name,
        backend_version,
        pid,
    );
    descriptor["runtimeInstanceId"] = runtime_instance_id.to_string().into();
    std::fs::write(
        descriptor_directory.join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([descriptor]))
            .expect("exact scripted descriptor JSON"),
    )
    .expect("exact scripted descriptor");

    process
}

fn discard_dead_exact_test_runtime(workspace_directory: &Path, home: &Path) {
    let active: serde_json::Value = serde_json::from_slice(
        &std::fs::read(workspace_directory.join("active.json"))
            .expect("prior exact test runtime active registration"),
    )
    .expect("prior exact test runtime active JSON");
    let runtime_instance_id = active["runtimeInstanceId"]
        .as_str()
        .expect("prior exact test runtime instance");
    let state_path = default_install_root(home)
        .join("state/runtime/test-manager")
        .join(format!("{runtime_instance_id}.json"));
    if let Ok(bytes) = std::fs::read(&state_path) {
        let state: serde_json::Value =
            serde_json::from_slice(&bytes).expect("prior exact test manager state JSON");
        let pid = state["pid"].as_u64().expect("prior exact test manager PID");
        let live = pid <= i32::MAX as u64
            && unsafe { libc::kill(pid as libc::pid_t, 0) } == 0;
        assert!(!live, "prior exact test runtime process {pid} is still live");
        std::fs::remove_file(&state_path).expect("remove dead exact test manager state");
    }
    std::fs::remove_dir_all(workspace_directory)
        .expect("remove dead exact test runtime registration");
}

fn write_private_test_file(path: &Path, bytes: &[u8]) {
    use std::os::unix::fs::PermissionsExt as _;

    std::fs::write(path, bytes).expect("private test runtime file");
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
        .expect("private test runtime file permissions");
}
