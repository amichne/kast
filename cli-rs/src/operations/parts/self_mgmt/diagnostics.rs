
fn configuration_diagnostic(
    config_path: &Path,
    error: Option<String>,
) -> DoctorConfigurationDiagnostic {
    DoctorConfigurationDiagnostic {
        config_home: config::kast_config_home().display().to_string(),
        config_path: config_path.display().to_string(),
        exists: config_path.is_file(),
        valid: error.is_none(),
        error,
        schema_version: SCHEMA_VERSION,
    }
}

fn canonical_directory_diagnostic(
    paths: &config::PathsConfig,
) -> DoctorCanonicalDirectoryDiagnostic {
    DoctorCanonicalDirectoryDiagnostic {
        root: paths.install_root.display().to_string(),
        bin_dir: paths.bin_dir.display().to_string(),
        lib_dir: paths.lib_dir.display().to_string(),
        cache_dir: paths.cache_dir.display().to_string(),
        logs_dir: paths.logs_dir.display().to_string(),
        runtime_dir: paths.runtime_dir.display().to_string(),
        descriptor_dir: paths.descriptor_dir.display().to_string(),
        socket_dir: paths.socket_dir.display().to_string(),
        schema_version: SCHEMA_VERSION,
    }
}

fn binary_diagnostic(
    cli: &config::CliConfig,
    install: Option<&InstallState>,
) -> DoctorBinaryDiagnostic {
    let running_binary = env::current_exe().unwrap_or_else(|_| cli.binary_path.clone());
    let configured_binary = cli.binary_path.clone();
    let configured_exists = configured_binary.is_file();
    let configured_matches_running = configured_exists
        && configured_binary_matches_running(
            &configured_binary,
            &running_binary,
            install.map(|install| Path::new(&install.entrypoints.active_binary)),
        );
    DoctorBinaryDiagnostic {
        running_binary: running_binary.display().to_string(),
        configured_binary: configured_binary.display().to_string(),
        configured_exists,
        configured_matches_running,
        schema_version: SCHEMA_VERSION,
    }
}

fn configured_binary_matches_running(
    configured_binary: &Path,
    running_binary: &Path,
    active_binary: Option<&Path>,
) -> bool {
    same_binary_path(configured_binary, running_binary)
        || active_binary
            .is_some_and(|active_binary| same_binary_path(active_binary, running_binary))
}

fn same_binary_path(left: &Path, right: &Path) -> bool {
    if config::normalize(left.to_path_buf()) == config::normalize(right.to_path_buf()) {
        return true;
    }
    match (fs::canonicalize(left), fs::canonicalize(right)) {
        (Ok(left), Ok(right)) => left == right,
        _ => false,
    }
}

pub fn read_global_install_state() -> Result<Option<InstallState>> {
    manifest::read_install_manifest()
}

fn managed_path(install_root: &Path, value: &str) -> PathBuf {
    let path = Path::new(value);
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        install_root.join(path)
    }
}

fn minimum_backend_version() -> &'static str {
    option_env!("KAST_MIN_DAEMON_VERSION").unwrap_or("0.7.11")
}

fn version_meets_minimum(version: &str, minimum: &str) -> Option<bool> {
    Some(parse_version_triplet(version)? >= parse_version_triplet(minimum)?)
}

fn parse_version_triplet(value: &str) -> Option<(u64, u64, u64)> {
    let normalized = value.trim().trim_start_matches('v');
    let mut parts = normalized.split(['.', '-', '+']);
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = parts.next()?.parse().ok()?;
    Some((major, minor, patch))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_plugin_workspace_metadata_accepts_resolved_config_socket_path() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace_root = config::normalize(temp.path().join("workspace"));
        let metadata_path = temp.path().join("global-workspace/workspace.json");
        fs::create_dir_all(metadata_path.parent().expect("metadata parent")).expect("metadata dir");
        fs::write(&metadata_path, "{}").expect("metadata file");
        let mut metadata = test_macos_plugin_workspace_metadata(&workspace_root);
        metadata.required_artifacts = vec![PathBuf::from("workspace.json")];

        validate_macos_plugin_workspace_metadata(
            &workspace_root,
            &metadata_path,
            metadata,
            true,
        )
        .expect("resolved config socket path should be accepted");
        assert!(!workspace_root.join(".kast").exists());
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_running_plugin_workspace_metadata_accepts_relaxed_plugin_match() {
        let temp = tempfile::tempdir().expect("tempdir");
        let workspace_root = config::normalize(temp.path().join("workspace"));
        let metadata_path = temp.path().join("global-workspace/workspace.json");
        fs::create_dir_all(metadata_path.parent().expect("metadata parent")).expect("metadata dir");
        fs::write(&metadata_path, "{}").expect("metadata file");
        let mut metadata = test_macos_plugin_workspace_metadata(&workspace_root);
        metadata.compatibility.plugin_version = "newer-plugin".to_string();
        metadata
            .compatibility
            .runtime_identity
            .implementation_version = "newer-plugin".to_string();

        validate_macos_running_plugin_workspace_metadata(
            &workspace_root,
            &metadata_path,
            metadata,
            "newer-plugin",
            false,
        )
        .expect("relaxed matching should admit matrix-compatible running plugin metadata");
    }

    #[cfg(target_os = "macos")]
    fn test_macos_plugin_workspace_metadata(workspace_root: &Path) -> MacosPluginWorkspaceMetadata {
        MacosPluginWorkspaceMetadata {
            schema_version: MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION,
            prepared_by: MACOS_PLUGIN_WORKSPACE_PREPARED_BY.to_string(),
            workspace_root: workspace_root.to_path_buf(),
            cli_binary: env::current_exe().expect("current exe"),
            backend: MACOS_PLUGIN_WORKSPACE_BACKEND.to_string(),
            socket_path: config::KastConfig::defaults()
                .paths
                .socket_dir
                .join(format!(
                    "kast-{}.sock",
                    config::workspace_hash(workspace_root)
                )),
            compatibility: runtime::RuntimeCompatibilityFacts {
                plugin_version: cli::version().to_string(),
                cli_version: cli::version().to_string(),
                protocol_revision: runtime::ProtocolRevision(
                    std::num::NonZeroU32::new(2).expect("revision"),
                ),
                workspace_metadata_revision: runtime::WorkspaceMetadataRevision(
                    std::num::NonZeroU32::new(MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION)
                        .expect("revision"),
                ),
                read_capabilities: vec![
                    runtime::WorkspaceReadCapability::Diagnostics,
                    runtime::WorkspaceReadCapability::ResolveSymbol,
                    runtime::WorkspaceReadCapability::WorkspaceFiles,
                ],
                mutation_capabilities: vec![
                    runtime::WorkspaceMutationCapability::ApplyEdits,
                    runtime::WorkspaceMutationCapability::RefreshWorkspace,
                    runtime::WorkspaceMutationCapability::Rename,
                ],
                runtime_identity: runtime::WorkspaceRuntimeIdentity {
                    implementation_version: cli::version().to_string(),
                    backend_kind: runtime::WorkspaceRuntimeBackendKind::Idea,
                },
            },
            required_artifacts: vec![PathBuf::from(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE)],
        }
    }

    #[test]
    fn configured_binary_match_accepts_manifest_active_binary() {
        let configured_binary = Path::new("/example/bin/kast");
        let running_binary = Path::new("/example/versions/0.1.0/bin/kast");

        assert!(configured_binary_matches_running(
            configured_binary,
            running_binary,
            Some(running_binary)
        ));
        assert!(!configured_binary_matches_running(
            configured_binary,
            Path::new("/other/bin/kast"),
            Some(running_binary)
        ));
    }
}
