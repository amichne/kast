#[cfg(target_os = "macos")]
fn apply_macos_plugin_workspace_check(
    target: ReadyTarget,
    workspace_root: Option<&Path>,
    issues: &mut Vec<String>,
) {
    if !matches!(target, ReadyTarget::Agent | ReadyTarget::Kotlin) {
        return;
    }
    match workspace_root {
        Some(workspace_root) => {
            if let Err(error) = validate_macos_plugin_workspace(workspace_root) {
                issues.push(error.message);
            }
        }
        None => issues.push(
            "macOS agent and Kotlin readiness require --workspace-root so the plugin-prepared workspace metadata can be verified".to_string(),
        ),
    }
}

#[cfg(not(target_os = "macos"))]
fn apply_macos_plugin_workspace_check(
    _target: ReadyTarget,
    _workspace_root: Option<&Path>,
    _issues: &mut Vec<String>,
) {
}

#[cfg(target_os = "macos")]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MacosPluginWorkspaceMetadata {
    schema_version: u32,
    prepared_by: String,
    workspace_root: PathBuf,
    cli_binary: PathBuf,
    backend: String,
    socket_path: PathBuf,
    compatibility: runtime::RuntimeCompatibilityFacts,
    required_artifacts: Vec<PathBuf>,
}

#[cfg(target_os = "macos")]
pub fn validate_macos_plugin_workspace(workspace_root: &Path) -> Result<()> {
    let workspace_root = config::normalize(workspace_root.to_path_buf());
    let strict_plugin_matching = config::KastConfig::load(&workspace_root)?
        .runtime
        .strict_plugin_matching;
    let (metadata_path, metadata) = read_macos_plugin_workspace_metadata(&workspace_root)?;
    validate_macos_plugin_workspace_metadata(
        &workspace_root,
        &metadata_path,
        metadata,
        strict_plugin_matching,
    )
}

#[cfg(target_os = "macos")]
pub(crate) fn validate_macos_running_plugin_workspace(
    workspace_root: &Path,
    backend_version: &str,
    strict_plugin_matching: bool,
) -> Result<()> {
    let workspace_root = config::normalize(workspace_root.to_path_buf());
    let (metadata_path, metadata) = read_macos_plugin_workspace_metadata(&workspace_root)?;
    validate_macos_running_plugin_workspace_metadata(
        &workspace_root,
        &metadata_path,
        metadata,
        backend_version,
        strict_plugin_matching,
    )
}

#[cfg(target_os = "macos")]
fn read_macos_plugin_workspace_metadata(
    workspace_root: &Path,
) -> Result<(PathBuf, MacosPluginWorkspaceMetadata)> {
    let metadata_path = workspace_root.join(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE);
    let raw = fs::read_to_string(&metadata_path).map_err(|error| {
        macos_plugin_workspace_error(format!(
            "macOS Kast invocation requires workspace metadata prepared by the Kast IntelliJ plugin at {}: {error}",
            metadata_path.display()
        ))
    })?;
    let metadata: MacosPluginWorkspaceMetadata = serde_json::from_str(&raw).map_err(|error| {
        macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata is not valid for this CLI version at {}; update the CLI and plugin, reopen this exact project, and refresh workspace metadata: {error}",
            metadata_path.display(),
        ))
    })?;
    Ok((metadata_path, metadata))
}

#[cfg(not(target_os = "macos"))]
pub fn validate_macos_plugin_workspace(_workspace_root: &Path) -> Result<()> {
    Ok(())
}

#[cfg(target_os = "macos")]
fn validate_macos_running_plugin_workspace_metadata(
    workspace_root: &Path,
    metadata_path: &Path,
    metadata: MacosPluginWorkspaceMetadata,
    backend_version: &str,
    strict_plugin_matching: bool,
) -> Result<()> {
    if metadata.compatibility.plugin_version != backend_version
        || metadata
            .compatibility
            .runtime_identity
            .implementation_version
            != backend_version
    {
        return Err(macos_plugin_workspace_error(format!(
            "The running IDEA descriptor version {backend_version} does not match its workspace compatibility metadata at {}",
            metadata_path.display(),
        )));
    }
    validate_macos_plugin_workspace_metadata(
        workspace_root,
        metadata_path,
        metadata,
        strict_plugin_matching,
    )
}

#[cfg(target_os = "macos")]
fn validate_macos_plugin_workspace_metadata(
    workspace_root: &Path,
    metadata_path: &Path,
    metadata: MacosPluginWorkspaceMetadata,
    strict_plugin_matching: bool,
) -> Result<()> {
    if metadata.schema_version != MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata schemaVersion {} is not recognized by this Kast version; expected {} at {}; update the CLI and plugin, reopen this exact project, and refresh workspace metadata",
            metadata.schema_version,
            MACOS_PLUGIN_WORKSPACE_SCHEMA_VERSION,
            metadata_path.display()
        )));
    }
    if metadata.prepared_by != MACOS_PLUGIN_WORKSPACE_PREPARED_BY {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata was prepared by `{}` instead of `{}` at {}",
            metadata.prepared_by,
            MACOS_PLUGIN_WORKSPACE_PREPARED_BY,
            metadata_path.display()
        )));
    }
    validate_prepared_compatibility_metadata(metadata_path, &metadata, strict_plugin_matching)?;
    let metadata_workspace_root = config::normalize(metadata.workspace_root);
    if metadata_workspace_root != workspace_root {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata root {} does not match requested workspace {}",
            metadata_workspace_root.display(),
            workspace_root.display()
        )));
    }
    if metadata.backend != MACOS_PLUGIN_WORKSPACE_BACKEND {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata backend `{}` is not recognized by this Kast version; expected `{}`",
            metadata.backend, MACOS_PLUGIN_WORKSPACE_BACKEND
        )));
    }
    let expected_socket_path = config::default_socket_path(workspace_root);
    if metadata.socket_path != expected_socket_path {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata socketPath {} does not match expected socket {}",
            metadata.socket_path.display(),
            expected_socket_path.display()
        )));
    }
    validate_macos_plugin_cli_binary(&metadata.cli_binary)?;
    validate_macos_plugin_required_artifacts(workspace_root, &metadata.required_artifacts)
}

#[cfg(target_os = "macos")]
fn validate_prepared_compatibility_metadata(
    metadata_path: &Path,
    metadata: &MacosPluginWorkspaceMetadata,
    strict_plugin_matching: bool,
) -> Result<()> {
    let facts = &metadata.compatibility;
    if facts.workspace_metadata_revision.0.get() != metadata.schema_version {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace compatibility metadata revision {} does not match schemaVersion {} at {}",
            facts.workspace_metadata_revision.0,
            metadata.schema_version,
            metadata_path.display(),
        )));
    }
    if facts
        .runtime_identity
        .implementation_version
        .chars()
        .any(char::is_whitespace)
        || facts.runtime_identity.implementation_version.is_empty()
    {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace runtime identity has an invalid implementation version at {}",
            metadata_path.display(),
        )));
    }
    if facts.runtime_identity.backend_kind.metadata_name() != metadata.backend {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace runtime identity backend does not match metadata backend at {}",
            metadata_path.display(),
        )));
    }
    if facts
        .read_capabilities
        .iter()
        .copied()
        .collect::<BTreeSet<_>>()
        .len()
        != facts.read_capabilities.len()
        || facts
            .mutation_capabilities
            .iter()
            .copied()
            .collect::<BTreeSet<_>>()
            .len()
            != facts.mutation_capabilities.len()
    {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace compatibility capabilities contain duplicates at {}",
            metadata_path.display(),
        )));
    }
    match runtime::assess_runtime_compatibility_with_plugin_matching(
        facts,
        None,
        strict_plugin_matching,
    )? {
        runtime::RuntimeCompatibilityAssessment::Compatible => Ok(()),
        runtime::RuntimeCompatibilityAssessment::UpdateRequired {
            requirement,
            plugin_version,
            cli_version,
        } => Err(macos_plugin_workspace_error(format!(
            "Kast runtime compatibility does not support plugin {plugin_version} with CLI {cli_version} because of {requirement:?}; update the CLI and plugin, reopen this exact project, and refresh workspace metadata at {}",
            metadata_path.display(),
        ))),
        runtime::RuntimeCompatibilityAssessment::MissingCapability { capability } => {
            Err(macos_plugin_workspace_error(format!(
                "Kast runtime compatibility is missing required capability {capability:?}; update the plugin, reopen this exact project, and refresh workspace metadata at {}",
                metadata_path.display(),
            )))
        }
    }
}

#[cfg(target_os = "macos")]
fn validate_macos_plugin_cli_binary(cli_binary: &Path) -> Result<()> {
    let configured = fs::canonicalize(cli_binary).map_err(|error| {
        macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata cliBinary {} cannot be resolved: {error}",
            cli_binary.display()
        ))
    })?;
    let running = env::current_exe()
        .map_err(|error| {
            macos_plugin_workspace_error(format!(
                "Current Kast executable cannot be resolved for macOS workspace validation: {error}"
            ))
        })
        .and_then(|path| {
            fs::canonicalize(&path).map_err(|error| {
                macos_plugin_workspace_error(format!(
                    "Current Kast executable {} cannot be canonicalized for macOS workspace validation: {error}",
                    path.display()
                ))
            })
        })?;
    if configured != running {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata cliBinary {} does not match the running Kast executable {}",
            configured.display(),
            running.display()
        )));
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn validate_macos_plugin_required_artifacts(
    workspace_root: &Path,
    required_artifacts: &[PathBuf],
) -> Result<()> {
    if required_artifacts != [PathBuf::from(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE)] {
        return Err(macos_plugin_workspace_error(format!(
            "macOS Kast workspace metadata must declare only its exact-root artifact `{}`",
            MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE
        )));
    }
    for artifact in required_artifacts {
        if !is_safe_relative_artifact(artifact) {
            return Err(macos_plugin_workspace_error(format!(
                "macOS Kast workspace metadata contains an unsupported artifact path `{}`",
                artifact.display()
            )));
        }
        let path = workspace_root.join(artifact);
        if !path.exists() {
            return Err(macos_plugin_workspace_error(format!(
                "macOS Kast workspace metadata requires missing artifact {}",
                path.display()
            )));
        }
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn is_safe_relative_artifact(path: &Path) -> bool {
    path.is_relative()
        && path
            .components()
            .all(|component| matches!(component, std::path::Component::Normal(_)))
}

#[cfg(target_os = "macos")]
fn macos_plugin_workspace_error(message: String) -> crate::error::CliError {
    crate::error::CliError::new("MACOS_PLUGIN_WORKSPACE_REQUIRED", message)
}
