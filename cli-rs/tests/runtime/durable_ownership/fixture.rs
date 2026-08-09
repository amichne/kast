use super::*;

pub(super) enum RuntimeTerminalBehavior {
    RetainArtifacts,
    RemoveOwnedArtifacts,
    LeaveIncompleteCleanup,
}

pub(super) struct RuntimeServiceFixture {
    _temp: tempfile::TempDir,
    home: PathBuf,
    config_home: PathBuf,
    pub(super) workspace: PathBuf,
    pub(super) socket_path: PathBuf,
    _listener: UnixListener,
    pub(super) runtime: std::process::Child,
    pub(super) registration: PathBuf,
    pub(super) descriptor_registry: PathBuf,
    manager_root: PathBuf,
}

impl RuntimeServiceFixture {
    pub(super) fn new() -> Self {
        Self::with_options(false, RuntimeTerminalBehavior::RetainArtifacts)
    }

    pub(super) fn new_with_persisted_descriptor_directory() -> Self {
        Self::with_options(true, RuntimeTerminalBehavior::RetainArtifacts)
    }

    pub(super) fn new_with_terminal_behavior(terminal: RuntimeTerminalBehavior) -> Self {
        Self::with_options(false, terminal)
    }

    fn with_options(use_alternate_directory: bool, terminal: RuntimeTerminalBehavior) -> Self {
        let temp = tempfile::tempdir().expect("runtime service fixture");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let socket_path = temp.path().join("endpoint.sock");
        std::fs::create_dir_all(&home).expect("home");
        std::fs::create_dir_all(&workspace).expect("workspace");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
        let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
        let listener = UnixListener::bind(&socket_path).expect("unservable endpoint");
        let runtime_instance_id = uuid::Uuid::new_v4();
        let install_root = default_install_root(&home);
        let runtime_dir = install_root.join("state/runtime");
        let descriptor_directory = if use_alternate_directory {
            temp.path().join("persisted-descriptors")
        } else {
            runtime_dir.join("daemons")
        };
        let descriptor_registry = descriptor_directory.join("daemons.json");
        let workspace_key = sha256(workspace.to_string_lossy().as_bytes());
        let registration = runtime_dir
            .join("services")
            .join(&workspace_key)
            .join(runtime_instance_id.to_string());
        let runtime_command = registered_test_command(
            &workspace,
            &socket_path,
            runtime_instance_id,
            &terminal,
            &descriptor_registry,
            &registration,
        );
        let runtime = Command::new(&runtime_command[0])
            .args(&runtime_command[1..])
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .expect("registered process");
        let manager_root = temp.path().join("test-manager");
        std::fs::create_dir_all(&manager_root).expect("manager root");
        let manager_state = manager_root.join(format!("{runtime_instance_id}.json"));
        make_private_directory(&registration);
        let registration = std::fs::canonicalize(registration).expect("canonical registration");
        write_registration(RegistrationFixtureInput {
            registration: &registration,
            id: runtime_instance_id,
            workspace: &workspace,
            descriptor_directory: &descriptor_directory,
            socket_path: &socket_path,
            manager_state: &manager_state,
            temp: temp.path(),
            terminal: &terminal,
            descriptor_registry: &descriptor_registry,
        });
        let receipt_bytes = std::fs::read(registration.join("receipt.json")).expect("receipt");
        write_private(
            &registration
                .parent()
                .expect("service root")
                .join("active.json"),
            &serde_json::to_vec_pretty(&serde_json::json!({
                "schemaVersion": 1,
                "runtimeInstanceId": runtime_instance_id,
                "receiptSha256": sha256(&receipt_bytes)
            }))
            .expect("active JSON"),
        );
        std::fs::write(
            &manager_state,
            serde_json::to_vec(&serde_json::json!({"pid": runtime.id()}))
                .expect("manager state JSON"),
        )
        .expect("manager state");
        std::fs::create_dir_all(descriptor_registry.parent().expect("descriptor directory"))
            .expect("descriptor directory");
        let mut descriptor = runtime_descriptor_for_process_test(
            &workspace,
            &socket_path,
            "indexer",
            "durable-ownership-test",
            runtime.id(),
        );
        descriptor["runtimeInstanceId"] = runtime_instance_id.to_string().into();
        std::fs::write(
            &descriptor_registry,
            serde_json::to_vec_pretty(&serde_json::json!([descriptor])).expect("descriptor JSON"),
        )
        .expect("descriptor registry");
        Self {
            _temp: temp,
            home,
            config_home,
            workspace,
            socket_path,
            _listener: listener,
            runtime,
            registration,
            descriptor_registry,
            manager_root,
        }
    }

    pub(super) fn add_dead_registration(&self) -> PathBuf {
        let id = uuid::Uuid::new_v4();
        let registration = self
            .registration
            .parent()
            .expect("service root")
            .join(id.to_string());
        make_private_directory(&registration);
        let registration = std::fs::canonicalize(registration).expect("canonical registration");
        let runtime_dir = default_install_root(&self.home).join("state/runtime");
        write_registration(RegistrationFixtureInput {
            registration: &registration,
            id,
            workspace: &self.workspace,
            descriptor_directory: &runtime_dir.join("daemons"),
            socket_path: &self.socket_path,
            manager_state: &self.manager_root.join(format!("{id}.json")),
            temp: self._temp.path(),
            terminal: &RuntimeTerminalBehavior::RetainArtifacts,
            descriptor_registry: &runtime_dir.join("daemons/daemons.json"),
        });
        registration
    }

    pub(super) fn orphan_runtime_from_manager(&self) {
        let runtime_instance_id = self
            .registration
            .file_name()
            .expect("registration identity");
        let manager_state = self
            .manager_root
            .join(runtime_instance_id)
            .with_extension("json");
        std::fs::write(
            manager_state,
            serde_json::to_vec(&serde_json::json!({"pid": 0})).expect("manager state JSON"),
        )
        .expect("orphaned manager state");
    }

    pub(super) fn remove_registration_and_descriptor(&self) {
        std::fs::remove_dir_all(self.registration.parent().expect("service workspace"))
            .expect("remove service registration");
        std::fs::remove_file(&self.descriptor_registry).expect("remove runtime descriptor");
    }

    pub(super) fn replace_registered_process_claim_with_reused_pid(&self) {
        let receipt: serde_json::Value = serde_json::from_slice(
            &std::fs::read(self.registration.join("receipt.json")).expect("receipt"),
        )
        .expect("receipt JSON");
        write_private(
            &self.registration.join("process.json"),
            &serde_json::to_vec_pretty(&serde_json::json!({
                "schemaVersion": 1,
                "launchSha256": receipt["launchSha256"],
                "process": {
                    "pid": self.runtime.id(),
                    "startKey": "stale-runtime-process",
                    "startEpochMillis": 1_000,
                    "ownerUid": u64::from(unsafe { libc::geteuid() })
                }
            }))
            .expect("stale process claim JSON"),
        );
        let mut descriptors: serde_json::Value = serde_json::from_slice(
            &std::fs::read(&self.descriptor_registry).expect("descriptor registry"),
        )
        .expect("descriptor registry JSON");
        descriptors[0]["processStartEpochMillis"] = 1_000.into();
        std::fs::write(
            &self.descriptor_registry,
            serde_json::to_vec_pretty(&descriptors).expect("descriptor registry JSON"),
        )
        .expect("stale descriptor registry");
    }

    pub(super) fn replace_descriptor_claim_with_current_process(&self) {
        let descriptors: serde_json::Value = serde_json::from_slice(
            &std::fs::read(&self.descriptor_registry).expect("descriptor registry"),
        )
        .expect("descriptor registry JSON");
        let mut descriptor = runtime_descriptor_for_process_test(
            &self.workspace,
            &self.socket_path,
            "indexer",
            "durable-ownership-test",
            self.runtime.id(),
        );
        descriptor["runtimeInstanceId"] = descriptors[0]["runtimeInstanceId"].clone();
        std::fs::write(
            &self.descriptor_registry,
            serde_json::to_vec_pretty(&serde_json::json!([descriptor]))
                .expect("descriptor registry JSON"),
        )
        .expect("current descriptor registry");
    }

    pub(super) fn copy_descriptor_to_caller_projection(&self) -> PathBuf {
        let registry = default_descriptor_dir(&self.home).join("daemons.json");
        std::fs::create_dir_all(registry.parent().expect("caller descriptor directory"))
            .expect("caller descriptor directory");
        std::fs::copy(&self.descriptor_registry, &registry).expect("caller descriptor registry");
        registry
    }

    pub(super) fn command(&self) -> Command {
        let mut command = kast(&self.home, &self.config_home);
        command
            .env("KAST_TEST_ALLOW_RUNTIME_SERVICE_MANAGER", "1")
            .env("KAST_TEST_RUNTIME_SERVICE_MANAGER_ROOT", &self.manager_root);
        command.args(["--output", "json"]);
        command
    }

    pub(super) fn repair_command(&self, execute: bool) -> Command {
        let mut command = self.command();
        command.args([
            "developer",
            "runtime",
            "repair",
            "--workspace-root",
            self.workspace.to_str().expect("workspace path"),
        ]);
        if execute {
            command.arg("--execute");
        }
        command
    }
}

impl Drop for RuntimeServiceFixture {
    fn drop(&mut self) {
        if self.runtime.try_wait().ok().flatten().is_none() {
            let _ = self.runtime.kill();
            let _ = self.runtime.wait();
        }
    }
}

struct RegistrationFixtureInput<'a> {
    registration: &'a Path,
    id: uuid::Uuid,
    workspace: &'a Path,
    descriptor_directory: &'a Path,
    socket_path: &'a Path,
    manager_state: &'a Path,
    temp: &'a Path,
    terminal: &'a RuntimeTerminalBehavior,
    descriptor_registry: &'a Path,
}
fn write_registration(input: RegistrationFixtureInput<'_>) {
    let runtime_config = input.registration.join("runtime-config.json");
    write_private(&runtime_config, b"{}");
    let launcher = input.temp.join("test-kastctl");
    if !launcher.exists() {
        std::fs::write(&launcher, "#!/bin/sh\nexit 0\n").expect("test launcher");
        std::fs::set_permissions(&launcher, std::fs::Permissions::from_mode(0o700))
            .expect("test launcher mode");
    }
    let launcher = std::fs::canonicalize(launcher).expect("canonical test launcher");
    let launch = serde_json::json!({
        "schemaVersion": 1,
        "workspaceRoot": input.workspace.display().to_string(),
        "workspaceKey": sha256(input.workspace.to_string_lossy().as_bytes()),
        "runtimeInstanceId": input.id,
        "ownerUid": u64::from(unsafe { libc::geteuid() }),
        "workingDirectory": input.workspace.display().to_string(),
        "command": registered_test_command(
            input.workspace,
            input.socket_path,
            input.id,
            input.terminal,
            input.descriptor_registry,
            input.registration,
        ),
        "environment": {},
        "logFile": input.temp.join("runtime.log").display().to_string(),
        "descriptorDirectory": input.descriptor_directory.display().to_string(),
        "socketPath": input.socket_path.display().to_string(),
        "launcherPath": launcher.display().to_string(),
        "launcherSha256": sha256(&std::fs::read(&launcher).expect("launcher bytes")),
        "runtimeConfigPath": runtime_config.display().to_string(),
        "runtimeConfigSha256": sha256(b"{}")
    });
    let launch_bytes = serde_json::to_vec_pretty(&launch).expect("launch JSON");
    let launch_sha256 = sha256(&launch_bytes);
    let launch_path = input.registration.join("launch.json");
    write_private(&launch_path, &launch_bytes);
    let definition_path = input.registration.join("service.test.json");
    let definition = serde_json::to_vec_pretty(&serde_json::json!({
        "launcher": launcher,
        "registration": launch_path,
        "registrationSha256": launch_sha256
    }))
    .expect("definition JSON");
    write_private(&definition_path, &definition);
    let receipt = serde_json::json!({
        "schemaVersion": 1,
        "workspaceRoot": input.workspace.display().to_string(),
        "workspaceKey": sha256(input.workspace.to_string_lossy().as_bytes()),
        "runtimeInstanceId": input.id,
        "launchPath": launch_path.display().to_string(),
        "launchSha256": launch_sha256,
        "definitionSha256": sha256(&definition),
        "manager": {
            "kind": "TEST",
            "state_path": input.manager_state.display().to_string(),
            "definition_path": definition_path.display().to_string()
        }
    });
    write_private(
        &input.registration.join("receipt.json"),
        &serde_json::to_vec_pretty(&receipt).expect("receipt JSON"),
    );
}

fn registered_test_command(
    workspace: &Path,
    socket_path: &Path,
    runtime_instance_id: uuid::Uuid,
    terminal: &RuntimeTerminalBehavior,
    descriptor_registry: &Path,
    registration: &Path,
) -> Vec<String> {
    let script = match terminal {
        RuntimeTerminalBehavior::RetainArtifacts => "trap 'exit 0' TERM; read fixture_value",
        RuntimeTerminalBehavior::RemoveOwnedArtifacts => {
            "trap 'rm -f \"$4\" \"$5\";exit' TERM;read x"
        }
        RuntimeTerminalBehavior::LeaveIncompleteCleanup => "trap 'touch \"$6/x\";exit' TERM;read x",
    };
    vec![
        "/bin/sh".to_string(),
        "-c".to_string(),
        script.to_string(),
        "kast-indexer".to_string(),
        format!("--workspace-root={}", workspace.display()),
        format!("--socket-path={}", socket_path.display()),
        format!("--runtime-instance-id={runtime_instance_id}"),
        descriptor_registry.display().to_string(),
        socket_path.display().to_string(),
        registration.display().to_string(),
    ]
}

fn make_private_directory(path: &Path) {
    std::fs::create_dir_all(path).expect("private directory");
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))
        .expect("private directory mode");
}

fn write_private(path: &Path, bytes: &[u8]) {
    std::fs::write(path, bytes).expect("private file");
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
        .expect("private file mode");
}

fn sha256(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}
