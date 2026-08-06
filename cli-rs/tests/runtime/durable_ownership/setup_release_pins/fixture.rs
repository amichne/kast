use sha2::{Digest as _, Sha256};
use std::os::unix::fs::PermissionsExt as _;

struct PinnedRuntimeService {
    process: std::process::Child,
    manager_state: PathBuf,
    registration: PathBuf,
    release_root: PathBuf,
}

impl PinnedRuntimeService {
    fn new(fixture_root: &Path, kast_home: &Path) -> Self {
        let workspace = fixture_root.join("runtime-workspace");
        std::fs::create_dir_all(&workspace).expect("runtime workspace");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
        let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
        let install_root = std::fs::canonicalize(kast_home).expect("canonical install root");
        let release_root =
            std::fs::canonicalize(kast_home.join("current")).expect("canonical release root");
        let receipt_path = release_root.join("receipt.json");
        let install_receipt: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&receipt_path).expect("install receipt"))
                .expect("install receipt JSON");
        let release_digest = install_receipt["releaseDigest"]
            .as_str()
            .expect("release digest");
        let launcher =
            std::fs::canonicalize(release_root.join("libexec/kastctl")).expect("launcher");
        let socket_path = fixture_root.join("runtime.sock");
        let runtime_command = vec![
            "/bin/sh".to_string(),
            "-c".to_string(),
            "trap 'exit 0' TERM; read fixture_value".to_string(),
            "kast-runtime-fixture".to_string(),
            format!("--socket-path={}", socket_path.display()),
        ];
        let process = Command::new(&runtime_command[0])
            .args(&runtime_command[1..])
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .expect("registered runtime process");
        let runtime_instance_id = uuid::Uuid::new_v4();
        let workspace_key = runtime_fixture_sha256(workspace.to_string_lossy().as_bytes());
        let registration = install_root
            .join("state/runtime/services")
            .join(&workspace_key)
            .join(runtime_instance_id.to_string());
        let manager_state = fixture_root.join("runtime-service-manager.json");
        make_runtime_fixture_directory(&registration);

        let runtime_config = registration.join("runtime-config.json");
        write_runtime_fixture_file(&runtime_config, b"{}");
        let launch = serde_json::json!({
            "schemaVersion": 1,
            "workspaceRoot": workspace.display().to_string(),
            "workspaceKey": workspace_key,
            "runtimeInstanceId": runtime_instance_id,
            "ownerUid": u64::from(unsafe { libc::geteuid() }),
            "workingDirectory": workspace.display().to_string(),
            "command": runtime_command,
            "environment": {},
            "logFile": fixture_root.join("runtime.log").display().to_string(),
            "descriptorDirectory": install_root.join("state/runtime/daemons").display().to_string(),
            "socketPath": socket_path.display().to_string(),
            "launcherPath": launcher.display().to_string(),
            "launcherSha256": runtime_fixture_sha256(&std::fs::read(&launcher).expect("launcher bytes")),
            "installedRelease": {
                "installRoot": install_root.display().to_string(),
                "releaseRoot": release_root.display().to_string(),
                "releaseDigest": release_digest,
                "receiptPath": receipt_path.display().to_string()
            },
            "runtimeConfigPath": runtime_config.display().to_string(),
            "runtimeConfigSha256": runtime_fixture_sha256(b"{}")
        });
        let launch_bytes = serde_json::to_vec_pretty(&launch).expect("launch JSON");
        let launch_sha256 = runtime_fixture_sha256(&launch_bytes);
        let launch_path = registration.join("launch.json");
        write_runtime_fixture_file(&launch_path, &launch_bytes);
        let definition_path = registration.join("service.test.json");
        let definition = serde_json::to_vec_pretty(&serde_json::json!({
            "launcher": launcher,
            "registration": launch_path,
            "registrationSha256": launch_sha256
        }))
        .expect("service definition JSON");
        write_runtime_fixture_file(&definition_path, &definition);
        let receipt = serde_json::json!({
            "schemaVersion": 1,
            "workspaceRoot": workspace.display().to_string(),
            "workspaceKey": workspace_key,
            "runtimeInstanceId": runtime_instance_id,
            "launchPath": registration.join("launch.json").display().to_string(),
            "launchSha256": launch_sha256,
            "definitionSha256": runtime_fixture_sha256(&definition),
            "manager": {
                "kind": "TEST",
                "state_path": manager_state.display().to_string(),
                "definition_path": definition_path.display().to_string()
            }
        });
        let receipt_bytes = serde_json::to_vec_pretty(&receipt).expect("service receipt JSON");
        write_runtime_fixture_file(&registration.join("receipt.json"), &receipt_bytes);
        write_runtime_fixture_file(
            &registration
                .parent()
                .expect("service workspace")
                .join("active.json"),
            &serde_json::to_vec_pretty(&serde_json::json!({
                "schemaVersion": 1,
                "runtimeInstanceId": runtime_instance_id,
                "receiptSha256": runtime_fixture_sha256(&receipt_bytes)
            }))
            .expect("active service JSON"),
        );
        std::fs::write(
            &manager_state,
            serde_json::to_vec(&serde_json::json!({"pid": process.id()}))
                .expect("manager state JSON"),
        )
        .expect("manager state");
        Self {
            process,
            manager_state,
            registration,
            release_root,
        }
    }

    fn run(&self, mut command: SetupCommand) -> std::process::Output {
        command
            .env("KAST_TEST_ALLOW_RUNTIME_SERVICE_MANAGER", "1")
            .env(
                "KAST_TEST_RUNTIME_SERVICE_MANAGER_STATE",
                &self.manager_state,
            )
            .output()
            .expect("setup with registered runtime")
    }

    fn is_live(&mut self) -> bool {
        self.process.try_wait().expect("runtime status").is_none()
    }
}

impl Drop for PinnedRuntimeService {
    fn drop(&mut self) {
        if self.process.try_wait().ok().flatten().is_none() {
            let _ = self.process.kill();
            let _ = self.process.wait();
        }
    }
}

fn make_runtime_fixture_directory(path: &Path) {
    std::fs::create_dir_all(path).expect("private runtime directory");
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))
        .expect("private runtime directory mode");
}

fn write_runtime_fixture_file(path: &Path, bytes: &[u8]) {
    std::fs::write(path, bytes).expect("private runtime file");
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
        .expect("private runtime file mode");
}

fn runtime_fixture_sha256(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}
