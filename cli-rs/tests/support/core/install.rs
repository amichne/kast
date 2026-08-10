use std::os::unix::process::CommandExt;

pub(crate) fn kast(home: &std::path::Path, config_home: &std::path::Path) -> Command {
    kast_at(Path::new(env!("CARGO_BIN_EXE_kast")), home, config_home)
}

pub(crate) fn kast_at(binary: &Path, home: &Path, config_home: &Path) -> Command {
    let mut command = Command::new(binary);
    command
        .arg0("kastctl")
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    let test_manager_root = default_install_root(home).join("state/runtime/test-manager");
    if test_manager_root.is_dir() {
        command
            .env("KAST_TEST_ALLOW_RUNTIME_SERVICE_MANAGER", "1")
            .env("KAST_TEST_RUNTIME_SERVICE_MANAGER_ROOT", test_manager_root);
    }
    command
}

pub(crate) struct PublishedSemanticCommand {
    command: Command,
    backend: Option<PublishedSemanticReadBackend>,
}

pub(crate) fn published_semantic_command(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
) -> PublishedSemanticCommand {
    published_semantic_command_for_reads(kast(home, config_home), home, config_home, workspace, 1)
}

pub(crate) fn published_semantic_command_for_reads(
    mut command: Command,
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    read_count: usize,
) -> PublishedSemanticCommand {
    publish_workspace_database_for_test(workspace);
    let socket = home.join("semantic.sock");
    let _ = std::fs::remove_file(&socket);
    let _ = read_count;
    let backend = spawn_open_published_semantic_read_backend(
        home, config_home, workspace, &socket,
    );
    let test_manager_root = default_install_root(home).join("state/runtime/test-manager");
    command
        .env("KAST_TEST_ALLOW_RUNTIME_SERVICE_MANAGER", "1")
        .env("KAST_TEST_RUNTIME_SERVICE_MANAGER_ROOT", test_manager_root);
    PublishedSemanticCommand {
        command,
        backend: Some(backend),
    }
}

impl PublishedSemanticCommand {
    pub(crate) fn arg<S: AsRef<std::ffi::OsStr>>(&mut self, arg: S) -> &mut Self {
        self.command.arg(arg);
        self
    }

    pub(crate) fn args<I, S>(&mut self, args: I) -> &mut Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<std::ffi::OsStr>,
    {
        self.command.args(args);
        self
    }

    pub(crate) fn current_dir<P: AsRef<Path>>(&mut self, directory: P) -> &mut Self {
        self.command.current_dir(directory);
        self
    }

    pub(crate) fn output(&mut self) -> std::io::Result<std::process::Output> {
        let output = self.command.output();
        self.backend
            .take()
            .expect("published semantic backend")
            .finish();
        output
    }
}

impl std::ops::Deref for PublishedSemanticCommand {
    type Target = Command;

    fn deref(&self) -> &Self::Target {
        &self.command
    }
}

impl std::ops::DerefMut for PublishedSemanticCommand {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.command
    }
}

pub(crate) fn default_install_root(home: &Path) -> PathBuf {
    home.join(".local/share/kast")
}

pub(crate) fn default_descriptor_dir(home: &Path) -> PathBuf {
    default_install_root(home).join("state/runtime/daemons")
}

pub(crate) fn default_bin_dir(home: &Path) -> PathBuf {
    default_install_root(home).join("current/bin")
}

pub(crate) fn default_libexec_dir(home: &Path) -> PathBuf {
    default_install_root(home).join("current/libexec")
}

pub(crate) fn install_manifest_path(home: &Path) -> PathBuf {
    default_install_root(home).join("current/receipt.json")
}

pub(crate) fn write_current_cli_install_manifest_for_test(home: &Path, _config_home: &Path) {
    let install_root = default_install_root(home);
    move_preinstall_workspace_fixtures_into_receipt_data_root(&install_root);
    let control_binary = default_libexec_dir(home).join("kastctl");
    let agent_binary = default_bin_dir(home).join("kast");
    let config_root = install_root.join("current/config");
    let indexer_dir = install_root.join(format!(
        "current/lib/backends/indexer-{}",
        env!("CARGO_PKG_VERSION")
    ));
    let runtime_libs_dir = indexer_dir.join("runtime-libs");
    let host_home = indexer_dir.join("idea-home");
    let indexer_payload_lib = host_home.join("plugins/kast-indexer/lib");
    std::fs::create_dir_all(default_bin_dir(home)).expect("bin directory");
    std::fs::create_dir_all(default_libexec_dir(home)).expect("libexec directory");
    std::fs::create_dir_all(&install_root).expect("install root");
    std::fs::create_dir_all(&config_root).expect("config root");
    std::fs::create_dir_all(&runtime_libs_dir).expect("indexer runtime libs");
    std::fs::create_dir_all(&indexer_payload_lib).expect("indexer payload lib");
    std::fs::write(runtime_libs_dir.join("classpath.txt"), "fixture.jar\n")
        .expect("indexer runtime classpath");
    std::fs::write(indexer_payload_lib.join("kast-indexer.jar"), "fixture")
        .expect("indexer payload jar");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &control_binary).expect("active Kast control binary");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &agent_binary).expect("active Kast agent binary");
    std::fs::write(
        install_manifest_path(home),
        serde_json::to_vec_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "current-cli-test-install",
            "releaseDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "manifestDigest": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "profile": "indexer",
            "activeVersion": env!("CARGO_PKG_VERSION"),
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": default_bin_dir(home).display().to_string(),
                "config": config_root.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": install_root.join("state/cache").display().to_string(),
                "runtime": install_root.join("state/runtime").display().to_string(),
                "logs": install_root.join("state/logs").display().to_string(),
                "locks": install_root.display().to_string()
            },
            "entrypoints": {
                "shim": control_binary.display().to_string(),
                "activeBinary": control_binary.display().to_string()
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "platform": if cfg!(target_os = "macos") { "macos-test" } else { "linux-test" },
            "components": ["cli", "indexer", "manifest"],
            "backends": [{
                "name": "indexer",
                "version": "0.7.11",
                "installDir": indexer_dir.display().to_string(),
                "runtimeLibsDir": runtime_libs_dir.display().to_string(),
                "ideaHome": host_home.display().to_string()
            }],
            "schemaVersion": 3
        }))
        .expect("install manifest JSON"),
    )
    .expect("install manifest");
}

fn move_preinstall_workspace_fixtures_into_receipt_data_root(install_root: &Path) {
    let preinstall_workspaces = install_root.join("state/data/workspaces");
    if !preinstall_workspaces.is_dir() {
        return;
    }
    let receipt_workspaces = install_root.join("state/workspaces");
    assert!(
        !receipt_workspaces.exists(),
        "fixture cannot merge pre-install and receipt-backed workspace roots"
    );
    std::fs::create_dir_all(receipt_workspaces.parent().expect("workspace data parent"))
        .expect("workspace data parent");
    std::fs::rename(&preinstall_workspaces, &receipt_workspaces)
        .expect("move pre-install workspace fixtures into receipt data root");
}

pub(crate) fn write_active_kast_for_test(home: &Path, config_home: &Path) -> PathBuf {
    write_current_cli_install_manifest_for_test(home, config_home);
    default_libexec_dir(home).join("kastctl")
}

pub(crate) fn write_legacy_local_install_for_test(home: &Path, config_home: &Path) -> PathBuf {
    let install_root = default_install_root(home);
    let shim = home.join(".local/bin/kast");
    let active_binary = install_root.join("versions/0.12.3/bin/kast");
    std::fs::create_dir_all(active_binary.parent().expect("active binary parent"))
        .expect("active binary dir");
    std::fs::create_dir_all(shim.parent().expect("shim parent")).expect("shim dir");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &active_binary).expect("active binary");
    std::fs::write(
        &shim,
        format!(
            "#!/usr/bin/env bash\nset -euo pipefail\nexec '{}' \"$@\"\n",
            active_binary.display()
        ),
    )
    .expect("shim");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&shim, std::fs::Permissions::from_mode(0o755)).expect("shim mode");
    }
    std::fs::create_dir_all(&install_root).expect("install root");
    std::fs::write(
        install_root.join("install.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "legacy-test-install",
            "profile": "user-local",
            "activeVersion": "0.12.3",
            "createdAt": "unix:1",
            "updatedAt": "unix:1",
            "roots": {
                "install": install_root.display().to_string(),
                "bin": home.join(".local/bin").display().to_string(),
                "config": config_home.display().to_string(),
                "data": install_root.join("state").display().to_string(),
                "cache": home.join(".cache/kast").display().to_string(),
                "runtime": install_root.join("runtime").display().to_string(),
                "logs": home.join(".local/state/kast/logs").display().to_string(),
                "locks": install_root.join("locks").display().to_string()
            },
            "entrypoints": {
                "shim": shim.display().to_string(),
                "activeBinary": active_binary.display().to_string()
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": "0.12.3",
            "components": ["cli", "config"],
            "ownedPaths": [shim.display().to_string()],
            "schemaVersion": 3
        }))
        .expect("legacy manifest json"),
    )
    .expect("legacy manifest");
    shim
}

pub(crate) fn workspace_database_path_for_test(workspace: &Path) -> PathBuf {
    std::fs::create_dir_all(workspace).expect("workspace fixture root");
    workspace_data_directory_for_test(workspace)
        .join("cache/source-index.db")
}

pub(crate) fn publish_workspace_database_for_test(workspace: &Path) -> serde_json::Value {
    workspace_files::publish_workspace_database(&workspace_database_path_for_test(workspace))
        .expect("published workspace database fixture")
}

pub(crate) fn published_workspace_generation_for_test(
    workspace: &Path,
) -> Option<serde_json::Value> {
    let database = workspace_database_path_for_test(workspace);
    if !database.is_file() {
        return None;
    }
    let connection = rusqlite::Connection::open_with_flags(
        database,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )
    .ok()?;
    connection
        .query_row(
            "SELECT revision, identity, source_index_generation, source_revision, reference_revision,
                    graph_revision, graph_blocker, source_index_schema_version,
                    published_at_epoch_millis, repository_overlay_file
             FROM workspace_publication WHERE singleton = 1",
            [],
            |row| {
                let overlay: Option<String> = row.get(9)?;
                let mut manifest = serde_json::json!({
                    "generation": row.get::<_, i64>(0)?,
                    "identity": row.get::<_, String>(1)?,
                    "sourceIndexGeneration": row.get::<_, i64>(2)?,
                    "sourceRevision": row.get::<_, i64>(3)?,
                    "referenceRevision": row.get::<_, i64>(4)?,
                    "graphPublication": if let Some(revision) = row.get::<_, Option<i64>>(5)? {
                        serde_json::json!({"type": "READY", "revision": revision})
                    } else {
                        serde_json::json!({"type": "BLOCKED", "blocker": row.get::<_, String>(6)?})
                    },
                    "sourceIndexSchemaVersion": row.get::<_, i64>(7)?,
                    "databaseFile": "source-index.db",
                    "publishedAtEpochMillis": row.get::<_, i64>(8)?,
                });
                if let Some(overlay) = overlay {
                    manifest["repositoryOverlayFile"] = serde_json::json!(overlay);
                }
                Ok(manifest)
            },
        )
        .ok()
}

fn workspace_data_directory_for_test(workspace: &Path) -> PathBuf {
    let workspace = std::fs::canonicalize(workspace).unwrap_or_else(|error| {
        panic!(
            "canonical fixture workspace {}: {error}",
            workspace.display()
        )
    });
    let home = inferred_fixture_home(&workspace);
    workspace_data_directory_for_test_at_home(&workspace, &home)
}

fn inferred_fixture_home(workspace: &Path) -> PathBuf {
    workspace
        .parent()
        .unwrap_or_else(|| panic!("workspace fixture has no parent: {}", workspace.display()))
        .join("home")
}

fn workspace_data_directory_for_test_at_home(workspace: &Path, home: &Path) -> PathBuf {
    let workspace = std::fs::canonicalize(workspace)
        .unwrap_or_else(|_| workspace.components().collect());
    let install_root = default_install_root(home);
    let data_root = if install_root.join("current/receipt.json").is_file() {
        install_root.join("state")
    } else {
        install_root.join("state/data")
    };
    use sha2::{Digest, Sha256};
    let digest = hex::encode(Sha256::digest(workspace.to_string_lossy().as_bytes()));
    data_root.join("workspaces").join(digest)
}
