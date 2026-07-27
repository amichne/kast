#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SemanticWorkspaceKind {
    PrimaryCheckout,
    LinkedWorktree,
    DisposableCheckout,
    StandaloneGradleWorkspace,
    UnsupportedProject,
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SemanticEvidenceQuality {
    Unavailable,
    CompilerBacked,
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SemanticWorkspaceLimitation {
    WorkspaceUnprepared,
    SourceModulesUnavailable,
    UnsupportedProject,
    MutationAuthorityRequired,
    BackendSelectionAmbiguous,
    RuntimeIndexing,
    ReferenceIndexUnavailable,
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SemanticWorkspaceNextActionKind {
    PrepareIdeaWorkspace,
    UseHeadlessDistribution,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SemanticWorkspaceNextAction {
    pub kind: SemanticWorkspaceNextActionKind,
    pub command: String,
    pub mutates_global_install_authority: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SemanticWorkspaceEvidence {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backend_name: Option<String>,
    pub workspace_root: String,
    pub workspace_kind: SemanticWorkspaceKind,
    pub source_module_names: Vec<String>,
    pub limitations: Vec<SemanticWorkspaceLimitation>,
    pub evidence_quality: SemanticEvidenceQuality,
    pub next_actions: Vec<SemanticWorkspaceNextAction>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub backend_candidates: Vec<SemanticBackendCandidateEvidence>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SemanticBackendCandidateEvidence {
    pub backend_name: String,
    pub backend_version: String,
    pub workspace_root: String,
    pub ready: bool,
    pub evidence_quality: SemanticEvidenceQuality,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SemanticWorkspaceAdmission {
    pub workspace_root: PathBuf,
    pub backend_name: BackendName,
    pub workspace_kind: SemanticWorkspaceKind,
}

#[derive(Debug, Clone)]
pub struct SemanticWorkspaceRejection {
    pub code: &'static str,
    pub message: String,
    pub evidence: SemanticWorkspaceEvidence,
}

pub enum SemanticWorkspaceRoute {
    Admitted(SemanticWorkspaceAdmission),
    Rejected(SemanticWorkspaceRejection),
}

pub fn semantic_workspace_route(
    requested_workspace_root: Option<PathBuf>,
    requested_backend: Option<BackendName>,
) -> Result<SemanticWorkspaceRoute> {
    let workspace_root = workspace_root(requested_workspace_root)?;
    let config = KastConfig::load(&workspace_root)?;
    semantic_workspace_route_with_config(workspace_root, config, requested_backend)
}

fn semantic_workspace_route_with_config(
    workspace_root: PathBuf,
    config: KastConfig,
    requested_backend: Option<BackendName>,
) -> Result<SemanticWorkspaceRoute> {
    if !is_gradle_workspace(&workspace_root) {
        let backend_name = requested_backend.unwrap_or_else(default_semantic_backend);
        return Ok(SemanticWorkspaceRoute::Rejected(
            unsupported_workspace_rejection(&workspace_root, backend_name),
        ));
    }
    let preference = runtime_backend_preference(&config, requested_backend);
    let workspace_kind = classify_semantic_workspace(&workspace_root);
    let backend_name = match preference.fixed_backend() {
        Some(backend_name) => backend_name,
        None => {
            let candidates = ready_semantic_backend_candidates(&workspace_root, &config)?;
            match automatic_semantic_backend_selection(candidates, default_semantic_backend()) {
                Ok(backend_name) => backend_name,
                Err(candidates) => {
                    return Ok(SemanticWorkspaceRoute::Rejected(
                    ambiguous_backend_rejection(&workspace_root, workspace_kind, candidates),
                    ));
                }
            }
        }
    };

    if backend_name == BackendName::Idea
        && let Err(error) = self_mgmt::validate_macos_plugin_workspace(&workspace_root)
    {
        return Ok(SemanticWorkspaceRoute::Rejected(
            unprepared_workspace_rejection(
                &workspace_root,
                backend_name,
                workspace_kind,
                error.message,
            ),
        ));
    }

    Ok(SemanticWorkspaceRoute::Admitted(
        SemanticWorkspaceAdmission {
            workspace_root,
            backend_name,
            workspace_kind,
        },
    ))
}

pub fn semantic_mutation_workspace_route(
    requested_workspace_root: Option<PathBuf>,
    requested_backend: Option<BackendName>,
) -> Result<SemanticWorkspaceRoute> {
    let workspace_root = workspace_root(requested_workspace_root)?;
    let config = KastConfig::load(&workspace_root)?;
    if !is_gradle_workspace(&workspace_root) {
        let backend_name = requested_backend.unwrap_or_else(default_semantic_backend);
        return Ok(SemanticWorkspaceRoute::Rejected(
            unsupported_workspace_rejection(&workspace_root, backend_name),
        ));
    }
    let workspace_kind = classify_semantic_workspace(&workspace_root);
    let authority_backend = runtime_backend_preference(&config, requested_backend)
        .fixed_backend()
        .unwrap_or_else(default_semantic_backend);
    if let Err(error) = self_mgmt::validate_macos_plugin_workspace(&workspace_root) {
        return Ok(SemanticWorkspaceRoute::Rejected(
            mutation_authority_rejection(
                &workspace_root,
                authority_backend,
                workspace_kind,
                error.message,
            ),
        ));
    }
    semantic_workspace_route_with_config(workspace_root, config, requested_backend)
}

pub fn compiler_backed_workspace_evidence(
    admission: &SemanticWorkspaceAdmission,
    runtime_status: &RuntimeStatusResponse,
) -> Option<SemanticWorkspaceEvidence> {
    let runtime_root = config::normalize(PathBuf::from(&runtime_status.workspace_root));
    if runtime_root != admission.workspace_root
        || runtime_status.backend_name != admission.backend_name.canonical()
    {
        return None;
    }
    let mut limitations = vec![];
    if runtime_status.indexing {
        limitations.push(SemanticWorkspaceLimitation::RuntimeIndexing);
    }
    if runtime_status.source_module_names.is_empty() {
        limitations.push(SemanticWorkspaceLimitation::SourceModulesUnavailable);
    }
    if !runtime_status.reference_index_ready {
        limitations.push(SemanticWorkspaceLimitation::ReferenceIndexUnavailable);
    }
    Some(SemanticWorkspaceEvidence {
        backend_name: Some(runtime_status.backend_name.clone()),
        workspace_root: runtime_root.display().to_string(),
        workspace_kind: admission.workspace_kind,
        source_module_names: runtime_status.source_module_names.clone(),
        limitations,
        evidence_quality: SemanticEvidenceQuality::CompilerBacked,
        next_actions: vec![],
        backend_candidates: vec![],
    })
}

include!("workspace_admission/routing_support.rs");
