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
    if let Some(backend) = install.and_then(|install| install.backends.first()) {
        return DoctorAgentBackendDiagnostic {
            state: if Path::new(&backend.runtime_libs_dir)
                .join("classpath.txt")
                .is_file()
            {
                AgentResourceState::Managed
            } else {
                AgentResourceState::Missing
            },
            kind: Some(backend.name.clone()),
            version: Some(backend.version.clone()),
            revision: None,
            source_path: Some(backend.runtime_libs_dir.clone()),
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

fn plugin_workspace_evidence(workspace_root: &Path) -> Option<PluginWorkspaceEvidence> {
    let metadata_path = workspace_root.join(".kast/setup/workspace.json");
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
