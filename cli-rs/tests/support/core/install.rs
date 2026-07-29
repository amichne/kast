use std::os::unix::process::CommandExt;

pub(crate) fn kast(home: &std::path::Path, config_home: &std::path::Path) -> Command {
    kast_at(Path::new(env!("CARGO_BIN_EXE_kast")), home, config_home)
}

pub(crate) fn kast_at(binary: &Path, home: &Path, config_home: &Path) -> Command {
    let mut command = Command::new(binary);
    command
        .arg0("_kastctl")
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    command
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

pub(crate) fn install_manifest_path(home: &Path) -> PathBuf {
    default_install_root(home).join("current/receipt.json")
}

pub(crate) fn write_current_cli_install_manifest_for_test(home: &Path, _config_home: &Path) {
    let install_root = default_install_root(home);
    let binary = default_bin_dir(home).join("kast");
    let config_root = install_root.join("current/config");
    std::fs::create_dir_all(default_bin_dir(home)).expect("bin directory");
    std::fs::create_dir_all(&install_root).expect("install root");
    std::fs::create_dir_all(&config_root).expect("config root");
    std::fs::copy(env!("CARGO_BIN_EXE_kast"), &binary).expect("active Kast binary");
    std::fs::write(
        install_manifest_path(home),
        serde_json::to_vec_pretty(&serde_json::json!({
            "tool": "kast",
            "installId": "current-cli-test-install",
            "releaseDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "manifestDigest": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "profile": "user-local",
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
                "shim": binary.display().to_string(),
                "activeBinary": binary.display().to_string()
            },
            "schemas": {"manifest": 1, "workspaceRegistry": 1, "symbolIndex": 3},
            "version": env!("CARGO_PKG_VERSION"),
            "components": ["cli"],
            "schemaVersion": 3
        }))
        .expect("install manifest JSON"),
    )
    .expect("install manifest");
}

pub(crate) fn write_active_kast_for_test(home: &Path, config_home: &Path) -> PathBuf {
    write_current_cli_install_manifest_for_test(home, config_home);
    default_bin_dir(home).join("kast")
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

pub(crate) fn write_macos_plugin_workspace_metadata(workspace: &Path) {
    write_macos_plugin_workspace_metadata_for_cli(
        workspace,
        Path::new(env!("CARGO_BIN_EXE_kast")),
        env!("CARGO_PKG_VERSION"),
    );
}

pub(crate) fn write_macos_plugin_workspace_metadata_at_home(workspace: &Path, home: &Path) {
    write_macos_plugin_workspace_metadata_for_cli_at_home(
        workspace,
        home,
        Path::new(env!("CARGO_BIN_EXE_kast")),
        env!("CARGO_PKG_VERSION"),
    );
}

pub(crate) fn write_macos_plugin_workspace_metadata_for_cli(
    workspace: &Path,
    cli_binary: &Path,
    cli_version: &str,
) {
    let home = inferred_fixture_home(workspace);
    write_macos_plugin_workspace_metadata_for_cli_at_home(
        workspace,
        &home,
        cli_binary,
        cli_version,
    );
}

fn write_macos_plugin_workspace_metadata_for_cli_at_home(
    workspace: &Path,
    home: &Path,
    cli_binary: &Path,
    cli_version: &str,
) {
    #[cfg(target_os = "macos")]
    {
        let workspace: PathBuf = workspace.components().collect();
        let metadata = macos_plugin_workspace_metadata_path_at_home(&workspace, home);
        std::fs::create_dir_all(metadata.parent().expect("metadata parent")).expect("metadata dir");
        std::fs::write(
            metadata,
            serde_json::to_string_pretty(&serde_json::json!({
                "schemaVersion": 3,
                "preparedBy": "kast-intellij-plugin",
                "workspaceRoot": workspace.display().to_string(),
                "cliBinary": cli_binary.display().to_string(),
                "backend": "idea",
                "socketPath": default_socket_path_for_test(&workspace).display().to_string(),
                "compatibility": {
                    "pluginVersion": cli_version,
                    "cliVersion": cli_version,
                    "protocolRevision": 2,
                    "workspaceMetadataRevision": 3,
                    "readCapabilities": [
                        "RESOLVE_SYMBOL",
                        "FIND_REFERENCES",
                        "CALL_HIERARCHY",
                        "TYPE_HIERARCHY",
                        "SEMANTIC_INSERTION_POINT",
                        "DIAGNOSTICS",
                        "FILE_OUTLINE",
                        "WORKSPACE_SYMBOL_SEARCH",
                        "WORKSPACE_SEARCH",
                        "WORKSPACE_FILES",
                        "IMPLEMENTATIONS",
                        "CODE_ACTIONS",
                        "COMPLETIONS"
                    ],
                    "mutationCapabilities": [
                        "RENAME",
                        "APPLY_EDITS",
                        "FILE_OPERATIONS",
                        "OPTIMIZE_IMPORTS",
                        "REFRESH_WORKSPACE"
                    ],
                    "runtimeIdentity": {
                        "implementationVersion": cli_version,
                        "backendKind": "IDEA"
                    }
                },
                "requiredArtifacts": [
                    "workspace.json"
                ]
            }))
            .expect("metadata json"),
        )
        .expect("metadata");
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (workspace, home, cli_binary, cli_version);
    }
}

#[cfg(target_os = "macos")]
pub(crate) fn macos_plugin_workspace_metadata_path(workspace: &Path) -> PathBuf {
    workspace_data_directory_for_test(workspace).join("workspace.json")
}

#[cfg(not(target_os = "macos"))]
pub(crate) fn macos_plugin_workspace_metadata_path(workspace: &Path) -> PathBuf {
    workspace.join("workspace.json")
}

#[cfg(target_os = "macos")]
fn macos_plugin_workspace_metadata_path_at_home(workspace: &Path, home: &Path) -> PathBuf {
    workspace_data_directory_for_test_at_home(workspace, home).join("workspace.json")
}

pub(crate) fn workspace_database_path_for_test(workspace: &Path) -> PathBuf {
    workspace_data_directory_for_test(workspace).join("cache/source-index.db")
}

fn workspace_data_directory_for_test(workspace: &Path) -> PathBuf {
    let home = inferred_fixture_home(workspace);
    workspace_data_directory_for_test_at_home(workspace, &home)
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
    let id = registry
        .get(&key)
        .cloned()
        .unwrap_or_else(|| {
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
