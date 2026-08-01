use super::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum AgentResourceState {
    Missing,
    Managed,
}

impl AgentResourceState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Missing => "missing",
            Self::Managed => "managed",
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentBinaryDiagnostic {
    pub path: String,
    pub version: String,
    pub revision: String,
    pub source_path: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentBackendDiagnostic {
    pub state: AgentResourceState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub revision: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_path: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentEnvironmentDiagnostic {
    pub install_authority: InstallAuthority,
    pub binary: DoctorAgentBinaryDiagnostic,
    pub backend: DoctorAgentBackendDiagnostic,
    pub ok: bool,
}

#[derive(Debug)]
struct PluginWorkspaceEvidence {
    metadata_path: PathBuf,
    trusted: bool,
    backend_kind: Option<String>,
    backend_version: Option<String>,
    protocol_revision: Option<String>,
}

pub(super) fn agent_environment_diagnostic(
    workspace_root: Option<&Path>,
    install_authority: InstallAuthority,
    install: Option<&InstallState>,
    binary: &DoctorBinaryDiagnostic,
    issues: &mut Vec<String>,
) -> Result<DoctorAgentEnvironmentDiagnostic> {
    let plugin = workspace_root.and_then(plugin_workspace_evidence);
    let backend = effective_backend_diagnostic(workspace_root, install, plugin.as_ref());
    if backend.state != AgentResourceState::Managed {
        issues.push(
            "Agent readiness could not identify one managed effective semantic backend".to_string(),
        );
    }
    let ok = backend.state == AgentResourceState::Managed;
    Ok(DoctorAgentEnvironmentDiagnostic {
        install_authority,
        binary: DoctorAgentBinaryDiagnostic {
            path: binary.running_binary.clone(),
            version: cli::version().to_string(),
            revision: cli::version().to_string(),
            source_path: binary.running_binary.clone(),
        },
        backend,
        ok,
    })
}

fn effective_backend_diagnostic(
    workspace_root: Option<&Path>,
    install: Option<&InstallState>,
    plugin: Option<&PluginWorkspaceEvidence>,
) -> DoctorAgentBackendDiagnostic {
    if let Some(plugin) = plugin
        && plugin.trusted
        && plugin.backend_kind.is_some()
        && plugin.backend_version.is_some()
    {
        return DoctorAgentBackendDiagnostic {
            state: AgentResourceState::Managed,
            kind: plugin.backend_kind.clone(),
            version: plugin.backend_version.clone(),
            revision: plugin.protocol_revision.clone(),
            source_path: Some(plugin.metadata_path.display().to_string()),
        };
    }
    if let Some((install, backend)) =
        install.and_then(|install| install.backends.first().map(|backend| (install, backend)))
    {
        let source_path = effective_backend_source_path(install, backend);
        return DoctorAgentBackendDiagnostic {
            state: if effective_backend_payload_exists(install, backend, &source_path) {
                AgentResourceState::Managed
            } else {
                AgentResourceState::Missing
            },
            kind: Some(backend.name.clone()),
            version: Some(backend.version.clone()),
            revision: None,
            source_path: Some(source_path.display().to_string()),
        };
    }
    DoctorAgentBackendDiagnostic {
        state: AgentResourceState::Missing,
        kind: None,
        version: None,
        revision: None,
        source_path: workspace_root.map(|root| root.display().to_string()),
    }
}

fn effective_backend_source_path(
    install: &InstallState,
    backend: &manifest::BackendComponentState,
) -> PathBuf {
    #[cfg(target_os = "macos")]
    if install.profile == MACOS_INSTALLED_IDEA_SIDECAR_PROFILE
        && let Some(idea_home) = &backend.idea_home
    {
        return Path::new(idea_home).join("plugins/kast-headless");
    }
    #[cfg(not(target_os = "macos"))]
    let _ = install;
    PathBuf::from(&backend.runtime_libs_dir)
}

fn effective_backend_payload_exists(
    install: &InstallState,
    _backend: &manifest::BackendComponentState,
    source_path: &Path,
) -> bool {
    #[cfg(target_os = "macos")]
    if install.profile == MACOS_INSTALLED_IDEA_SIDECAR_PROFILE {
        let plugin_lib = source_path.join("lib");
        return plugin_lib.is_dir()
            && fs::read_dir(plugin_lib).is_ok_and(|entries| {
                entries.filter_map(std::result::Result::ok).any(|entry| {
                    entry
                        .path()
                        .extension()
                        .is_some_and(|extension| extension == "jar")
                })
            });
    }
    #[cfg(not(target_os = "macos"))]
    let _ = install;
    source_path.join("classpath.txt").is_file()
}

fn plugin_workspace_evidence(workspace_root: &Path) -> Option<PluginWorkspaceEvidence> {
    let metadata_path = config::workspace_data_directory(workspace_root)
        .ok()?
        .join(MACOS_PLUGIN_WORKSPACE_METADATA_RELATIVE);
    let raw = fs::read_to_string(&metadata_path).ok()?;
    let metadata: serde_json::Value = serde_json::from_str(&raw).ok()?;
    if metadata
        .get("preparedBy")
        .and_then(serde_json::Value::as_str)
        != Some("kast-intellij-plugin")
    {
        return None;
    }
    let compatibility = metadata.get("compatibility");
    let runtime_identity = compatibility.and_then(|value| value.get("runtimeIdentity"));
    #[cfg(target_os = "macos")]
    let trusted = validate_macos_plugin_workspace(workspace_root).is_ok();
    #[cfg(not(target_os = "macos"))]
    let trusted = false;
    Some(PluginWorkspaceEvidence {
        metadata_path,
        trusted,
        backend_kind: runtime_identity
            .and_then(|value| value.get("backendKind"))
            .and_then(serde_json::Value::as_str)
            .map(str::to_ascii_lowercase),
        backend_version: runtime_identity
            .and_then(|value| value.get("implementationVersion"))
            .and_then(serde_json::Value::as_str)
            .map(str::to_string),
        protocol_revision: compatibility
            .and_then(|value| value.get("protocolRevision"))
            .and_then(serde_json::Value::as_u64)
            .map(|revision| revision.to_string()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(target_os = "macos")]
    #[test]
    fn installed_idea_sidecar_plugin_is_a_managed_backend_without_runtime_classpath() {
        let temp = tempfile::tempdir().unwrap();
        let idea_home = temp.path().join("idea-home");
        let plugin_lib = idea_home.join("plugins/kast-headless/lib");
        std::fs::create_dir_all(&plugin_lib).unwrap();
        std::fs::write(plugin_lib.join("kast-headless.jar"), "fixture").unwrap();
        let install = install_with_backend(
            MACOS_INSTALLED_IDEA_SIDECAR_PROFILE,
            &temp.path().join("runtime-libs"),
            &idea_home,
        );

        let diagnostic = effective_backend_diagnostic(None, Some(&install), None);

        assert_eq!(diagnostic.state, AgentResourceState::Managed);
    }

    #[test]
    fn non_sidecar_backend_still_requires_runtime_classpath() {
        let temp = tempfile::tempdir().unwrap();
        let idea_home = temp.path().join("idea-home");
        let plugin_lib = idea_home.join("plugins/kast-headless/lib");
        std::fs::create_dir_all(&plugin_lib).unwrap();
        std::fs::write(plugin_lib.join("kast-headless.jar"), "fixture").unwrap();
        let install = install_with_backend(
            "ubuntu-debian-headless",
            &temp.path().join("runtime-libs"),
            &idea_home,
        );

        let diagnostic = effective_backend_diagnostic(None, Some(&install), None);

        assert_eq!(diagnostic.state, AgentResourceState::Missing);
    }

    fn install_with_backend(profile: &str, runtime_libs: &Path, idea_home: &Path) -> InstallState {
        serde_json::from_value(serde_json::json!({
            "profile": profile,
            "roots": {
                "install": "/install",
                "bin": "/install/current/bin",
                "config": "/install/current/config",
                "data": "/install/state/data",
                "cache": "/install/state/cache",
                "runtime": "/install/state/runtime",
                "logs": "/install/state/logs",
                "locks": "/install"
            },
            "entrypoints": {
                "shim": "/install/current/libexec/kastctl",
                "activeBinary": "/install/current/libexec/kastctl"
            },
            "backends": [{
                "name": "headless",
                "version": "test",
                "installDir": idea_home.parent().unwrap().display().to_string(),
                "runtimeLibsDir": runtime_libs.display().to_string(),
                "ideaHome": idea_home.display().to_string()
            }]
        }))
        .unwrap()
    }
}
