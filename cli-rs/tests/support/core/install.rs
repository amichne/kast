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
    command: Command,
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
        .join("semantic-generations/generations/test-generation/source-index.db")
}

pub(crate) fn publish_workspace_database_for_test(workspace: &Path) -> serde_json::Value {
    workspace_files::publish_database_if_generation(&workspace_database_path_for_test(workspace))
        .expect("published workspace generation fixture")
}

pub(crate) fn published_workspace_generation_for_test(
    workspace: &Path,
) -> Option<serde_json::Value> {
    let pointer = workspace_data_directory_for_test(workspace)
        .join("semantic-generations/current.json");
    pointer.is_file().then(|| {
        serde_json::from_slice(&std::fs::read(&pointer).expect("published workspace pointer"))
            .expect("published workspace pointer JSON")
    })
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
    let workspace: PathBuf = workspace.components().collect();
    let install_root = default_install_root(home);
    let data_root = if install_root.join("current/receipt.json").is_file() {
        install_root.join("state")
    } else {
        install_root.join("state/data")
    };
    let workspaces_root = data_root.join("workspaces");
    if let (Some(toplevel), Some(common_dir), Some(git_dir)) = (
        git_path_for_test(&workspace, &["rev-parse", "--show-toplevel"]),
        git_path_for_test(&workspace, &["rev-parse", "--git-common-dir"]),
        git_path_for_test(&workspace, &["rev-parse", "--git-dir"]),
    ) {
        use sha2::{Digest, Sha256};
        let common_hash = hex::encode(Sha256::digest(common_dir.to_string_lossy().as_bytes()));
        let worktree_hash = hex::encode(Sha256::digest(
            format!("{}\n{}", toplevel.display(), git_dir.display()).as_bytes(),
        ));
        let slug = sanitized_workspace_segment(
            toplevel
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("workspace"),
        );
        let repository_root = workspaces_root.join("git/local").join(&common_hash[..12]);
        return repository_root
            .join("worktrees")
            .join(format!("{slug}--{}", &worktree_hash[..12]));
    }
    let registry_path = workspaces_root.join("local-workspaces.json");
    let registry: std::collections::BTreeMap<String, String> = if registry_path.is_file() {
        serde_json::from_slice(&std::fs::read(&registry_path).expect("workspace registry"))
            .unwrap_or_default()
    } else {
        std::collections::BTreeMap::new()
    };
    let key = workspace.display().to_string();
    let id = registry.get(&key).cloned().unwrap_or_else(|| {
        use sha2::{Digest, Sha256};
        hex::encode(Sha256::digest(key.as_bytes()))[..12].to_string()
    });
    let sanitized = sanitized_workspace_segment(&workspace.display().to_string());
    workspaces_root
        .join("local")
        .join(format!("{sanitized}--{id}"))
}

fn git_path_for_test(workspace: &Path, args: &[&str]) -> Option<PathBuf> {
    let raw = git_output_for_test(workspace, args)?;
    let path = PathBuf::from(raw);
    Some(
        if path.is_absolute() {
            path
        } else {
            workspace.join(path)
        }
        .components()
        .collect(),
    )
}

fn git_output_for_test(workspace: &Path, args: &[&str]) -> Option<String> {
    let output = std::process::Command::new("git")
        .args(args)
        .current_dir(workspace)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let raw = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if raw.is_empty() {
        return None;
    }
    Some(raw)
}

fn sanitized_workspace_segment(value: &str) -> String {
    let mut sanitized = String::new();
    for character in value.chars() {
        if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
            sanitized.push(character);
        } else if !sanitized.ends_with('-') {
            sanitized.push('-');
        }
    }
    let sanitized = sanitized
        .trim_matches('-')
        .chars()
        .take(80)
        .collect::<String>();
    if sanitized.is_empty() || matches!(sanitized.as_str(), "." | "..") {
        "workspace".to_string()
    } else {
        sanitized
    }
}
