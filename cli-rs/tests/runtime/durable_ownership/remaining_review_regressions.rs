use super::*;
use std::path::Path;

#[test]
fn symlinked_stop_uses_canonical_lifecycle_lock_remaining_review_regression() {
    let temp = tempfile::tempdir().expect("workspace fixture");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
    let workspace_alias = temp.path().join("workspace-alias");
    std::os::unix::fs::symlink(&workspace, &workspace_alias).expect("workspace alias");

    let stop = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "stop",
            "--workspace-root",
            workspace_alias.to_str().expect("workspace alias path"),
        ])
        .output()
        .expect("runtime stop through workspace alias");

    assert_success(&stop, "runtime stop through workspace alias");
    let result = output_json(&stop);
    assert_eq!(result["workspaceRoot"], workspace.display().to_string());
    let lock_directory = default_install_root(&home)
        .join("state/runtime")
        .join("workspace-launch-locks");
    let canonical_lock = lock_directory.join(format!("{}.lock", workspace_hash(&workspace)));
    let alias_lock = lock_directory.join(format!("{}.lock", workspace_hash(&workspace_alias)));

    assert!(
        canonical_lock.exists(),
        "canonical lifecycle lock is absent"
    );
    assert!(!alias_lock.exists(), "alias-specific lifecycle lock exists");
}

#[test]
fn stale_legacy_descriptor_pid_reuse_is_repaired_without_signaling_replacement_remaining_review_regression()
 {
    let mut fixture = LegacyPidReuseFixture::new();
    let mut descriptor = fixture.descriptor();
    descriptor["processStartEpochMillis"] = 1_000.into();
    fixture.write_descriptor(descriptor);

    let repair = fixture.repair(true);

    assert_success(&repair, "stale legacy descriptor repair");
    let repair = output_json(&repair);
    assert_eq!(repair["state"], "CLEAN");
    assert_eq!(repair["actions"].as_array().map(Vec::len), Some(1));
    assert_eq!(
        repair["actions"][0]["action"],
        "REMOVE_PROVEN_DEAD_LEGACY_RUNTIME"
    );
    assert_eq!(repair["actions"][0]["executed"], true);
    assert!(
        fixture
            .replacement
            .try_wait()
            .expect("replacement status")
            .is_none(),
        "unrelated replacement was signaled"
    );
    assert!(!fixture.descriptor_registry.exists(), "descriptor remains");
    assert!(!fixture.socket_path.exists(), "socket remains");
}

#[test]
fn legacy_descriptor_pid_reuse_with_incomplete_identity_stays_ambiguous_remaining_review_regression()
 {
    let mut fixture = LegacyPidReuseFixture::new();
    let mut descriptor = fixture.descriptor();
    descriptor
        .as_object_mut()
        .expect("descriptor object")
        .remove("processStartEpochMillis");
    fixture.write_descriptor(descriptor);

    let repair = fixture.repair(true);

    assert_error(&repair, "RUNTIME_OWNERSHIP_AMBIGUOUS");
    assert!(
        fixture
            .replacement
            .try_wait()
            .expect("replacement status")
            .is_none(),
        "unrelated process was signaled"
    );
    assert!(
        fixture.descriptor_registry.exists(),
        "descriptor was removed"
    );
    assert!(fixture.socket_path.exists(), "socket was removed");
}

#[test]
fn gone_legacy_descriptor_without_start_identity_remains_repairable_remaining_review_regression() {
    let mut fixture = LegacyPidReuseFixture::new();
    let mut descriptor = fixture.descriptor();
    descriptor
        .as_object_mut()
        .expect("descriptor object")
        .remove("processStartEpochMillis");
    fixture.write_descriptor(descriptor);
    fixture.replacement.kill().expect("stop descriptor process");
    fixture.replacement.wait().expect("descriptor process exit");

    let repair = fixture.repair(true);

    assert_success(&repair, "gone legacy descriptor repair");
    let repair = output_json(&repair);
    assert_eq!(repair["state"], "CLEAN");
    assert_eq!(repair["actions"].as_array().map(Vec::len), Some(1));
    assert!(!fixture.descriptor_registry.exists(), "descriptor remains");
    assert!(!fixture.socket_path.exists(), "socket remains");
}

struct LegacyPidReuseFixture {
    _temp: tempfile::TempDir,
    home: PathBuf,
    config_home: PathBuf,
    workspace: PathBuf,
    socket_path: PathBuf,
    _listener: UnixListener,
    replacement: std::process::Child,
    descriptor_registry: PathBuf,
}

impl LegacyPidReuseFixture {
    fn new() -> Self {
        let temp = tempfile::tempdir().expect("legacy PID reuse fixture");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let socket_path = temp.path().join("legacy.sock");
        std::fs::create_dir_all(&home).expect("home");
        std::fs::create_dir_all(&workspace).expect("workspace");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
        let workspace = std::fs::canonicalize(workspace).expect("canonical workspace");
        let listener = UnixListener::bind(&socket_path).expect("legacy socket");
        let replacement = Command::new("/bin/sleep")
            .arg("30")
            .spawn()
            .expect("unrelated replacement");
        let descriptor_registry = default_descriptor_dir(&home).join("daemons.json");
        std::fs::create_dir_all(descriptor_registry.parent().expect("descriptor directory"))
            .expect("descriptor directory");
        Self {
            _temp: temp,
            home,
            config_home,
            workspace,
            socket_path,
            _listener: listener,
            replacement,
            descriptor_registry,
        }
    }

    fn descriptor(&self) -> serde_json::Value {
        runtime_descriptor_for_process_test(
            &self.workspace,
            &self.socket_path,
            "indexer",
            "durable-ownership-test",
            self.replacement.id(),
        )
    }

    fn write_descriptor(&self, descriptor: serde_json::Value) {
        std::fs::write(
            &self.descriptor_registry,
            serde_json::to_vec_pretty(&serde_json::json!([descriptor])).expect("descriptor JSON"),
        )
        .expect("descriptor registry");
    }

    fn repair(&self, execute: bool) -> std::process::Output {
        let mut command = kast(&self.home, &self.config_home);
        command.args([
            "--output",
            "json",
            "developer",
            "runtime",
            "repair",
            "--workspace-root",
            self.workspace.to_str().expect("workspace path"),
        ]);
        if execute {
            command.arg("--execute");
        }
        command.output().expect("legacy runtime repair")
    }
}

impl Drop for LegacyPidReuseFixture {
    fn drop(&mut self) {
        if self.replacement.try_wait().ok().flatten().is_none() {
            let _ = self.replacement.kill();
            let _ = self.replacement.wait();
        }
    }
}

fn workspace_hash(path: &Path) -> String {
    hex::encode(Sha256::digest(path.to_string_lossy().as_bytes()))[..12].to_string()
}
