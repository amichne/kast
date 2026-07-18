#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
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

fn ready_semantic_backend_candidates(
    workspace_root: &Path,
    config: &KastConfig,
) -> Result<Vec<SemanticBackendCandidateEvidence>> {
    let inspection = inspect_workspace_with_config(
        workspace_root,
        config,
        RuntimeBackendPreference::Automatic,
        StaleDescriptorPolicy::Preserve,
    )?;
    let mut candidates = inspection
        .candidates
        .iter()
        .filter(|candidate| candidate.ready)
        .filter_map(|candidate| {
            backend_name_from_runtime(&candidate.descriptor.backend_name)?;
            Some(SemanticBackendCandidateEvidence {
                backend_name: candidate.descriptor.backend_name.clone(),
                backend_version: candidate.descriptor.backend_version.clone(),
                workspace_root: config::normalize(PathBuf::from(
                    &candidate.descriptor.workspace_root,
                ))
                .display()
                .to_string(),
                ready: candidate.ready,
                evidence_quality: SemanticEvidenceQuality::CompilerBacked,
            })
        })
        .collect::<Vec<_>>();
    candidates.sort_by(|left, right| left.backend_name.cmp(&right.backend_name));
    candidates.dedup_by(|left, right| left.backend_name == right.backend_name);
    Ok(candidates)
}

fn backend_name_from_runtime(backend_name: &str) -> Option<BackendName> {
    match backend_name {
        "idea" => Some(BackendName::Idea),
        "headless" => Some(BackendName::Headless),
        _ => None,
    }
}

fn automatic_semantic_backend_selection(
    candidates: Vec<SemanticBackendCandidateEvidence>,
    default_backend: BackendName,
) -> std::result::Result<BackendName, Vec<SemanticBackendCandidateEvidence>> {
    match candidates.as_slice() {
        [] => Ok(default_backend),
        [candidate] => Ok(
            backend_name_from_runtime(&candidate.backend_name).unwrap_or(default_backend),
        ),
        _ => Err(candidates),
    }
}

fn default_semantic_backend() -> BackendName {
    if cfg!(target_os = "macos") {
        BackendName::Idea
    } else {
        BackendName::Headless
    }
}

fn classify_semantic_workspace(workspace_root: &Path) -> SemanticWorkspaceKind {
    if workspace_root.join(".git").is_file() {
        return SemanticWorkspaceKind::LinkedWorktree;
    }
    let temporary_root = fs::canonicalize(std::env::temp_dir())
        .unwrap_or_else(|_| config::normalize(std::env::temp_dir()));
    let classified_root = fs::canonicalize(workspace_root)
        .unwrap_or_else(|_| config::normalize(workspace_root.to_path_buf()));
    if classified_root.starts_with(&temporary_root) {
        return SemanticWorkspaceKind::DisposableCheckout;
    }
    if workspace_root.join(".git").is_dir() {
        return SemanticWorkspaceKind::PrimaryCheckout;
    }
    SemanticWorkspaceKind::StandaloneGradleWorkspace
}

fn is_gradle_workspace(workspace_root: &Path) -> bool {
    [
        "settings.gradle.kts",
        "settings.gradle",
        "build.gradle.kts",
        "build.gradle",
    ]
    .iter()
    .any(|marker| workspace_root.join(marker).is_file())
}

fn unsupported_workspace_rejection(
    workspace_root: &Path,
    backend_name: BackendName,
) -> SemanticWorkspaceRejection {
    SemanticWorkspaceRejection {
        code: "SEMANTIC_WORKSPACE_UNSUPPORTED",
        message: format!(
            "{} is not a supported Kotlin Gradle workspace. Select a workspace containing settings.gradle(.kts) or build.gradle(.kts).",
            workspace_root.display()
        ),
        evidence: SemanticWorkspaceEvidence {
            backend_name: Some(backend_name.canonical().to_string()),
            workspace_root: workspace_root.display().to_string(),
            workspace_kind: SemanticWorkspaceKind::UnsupportedProject,
            source_module_names: vec![],
            limitations: vec![SemanticWorkspaceLimitation::UnsupportedProject],
            evidence_quality: SemanticEvidenceQuality::Unavailable,
            next_actions: vec![],
            backend_candidates: vec![],
        },
    }
}

fn ambiguous_backend_rejection(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
    backend_candidates: Vec<SemanticBackendCandidateEvidence>,
) -> SemanticWorkspaceRejection {
    SemanticWorkspaceRejection {
        code: "SEMANTIC_BACKEND_AMBIGUOUS",
        message: format!(
            "More than one ready semantic backend is registered for {}. Select one explicitly with --backend=idea or --backend=headless.",
            workspace_root.display()
        ),
        evidence: SemanticWorkspaceEvidence {
            backend_name: None,
            workspace_root: workspace_root.display().to_string(),
            workspace_kind,
            source_module_names: vec![],
            limitations: vec![SemanticWorkspaceLimitation::BackendSelectionAmbiguous],
            evidence_quality: SemanticEvidenceQuality::Unavailable,
            next_actions: vec![],
            backend_candidates,
        },
    }
}

fn mutation_authority_rejection(
    workspace_root: &Path,
    backend_name: BackendName,
    workspace_kind: SemanticWorkspaceKind,
    authority_message: String,
) -> SemanticWorkspaceRejection {
    let exact_root = workspace_root.display();
    SemanticWorkspaceRejection {
        code: "SEMANTIC_MUTATION_AUTHORITY_REQUIRED",
        message: format!(
            "Applied semantic mutation is not authorized for the exact workspace root {exact_root}. {authority_message}"
        ),
        evidence: SemanticWorkspaceEvidence {
            backend_name: Some(backend_name.canonical().to_string()),
            workspace_root: exact_root.to_string(),
            workspace_kind,
            source_module_names: vec![],
            limitations: vec![
                SemanticWorkspaceLimitation::MutationAuthorityRequired,
                SemanticWorkspaceLimitation::SourceModulesUnavailable,
            ],
            evidence_quality: SemanticEvidenceQuality::Unavailable,
            next_actions: vec![SemanticWorkspaceNextAction {
                kind: SemanticWorkspaceNextActionKind::PrepareIdeaWorkspace,
                command: format!(
                    "Open `{exact_root}` in IntelliJ IDEA or Android Studio with the JetBrains-installed Kast plugin enabled, then rerun the applied command against that exact root."
                ),
                mutates_global_install_authority: false,
            }],
            backend_candidates: vec![],
        },
    }
}

fn unprepared_workspace_rejection(
    workspace_root: &Path,
    backend_name: BackendName,
    workspace_kind: SemanticWorkspaceKind,
    authority_message: String,
) -> SemanticWorkspaceRejection {
    let exact_root = workspace_root.display();
    SemanticWorkspaceRejection {
        code: "SEMANTIC_WORKSPACE_UNPREPARED",
        message: format!(
            "No compiler-backed semantic state is prepared for the exact workspace root {exact_root}. {authority_message}"
        ),
        evidence: SemanticWorkspaceEvidence {
            backend_name: Some(backend_name.canonical().to_string()),
            workspace_root: exact_root.to_string(),
            workspace_kind,
            source_module_names: vec![],
            limitations: vec![
                SemanticWorkspaceLimitation::WorkspaceUnprepared,
                SemanticWorkspaceLimitation::SourceModulesUnavailable,
            ],
            evidence_quality: SemanticEvidenceQuality::Unavailable,
            next_actions: vec![
                SemanticWorkspaceNextAction {
                    kind: SemanticWorkspaceNextActionKind::PrepareIdeaWorkspace,
                    command: format!(
                        "Open `{exact_root}` in IntelliJ IDEA or Android Studio with the JetBrains-installed Kast plugin enabled, then run `kast agent verify --workspace-root '{exact_root}' --backend=idea`."
                    ),
                    mutates_global_install_authority: false,
                },
                SemanticWorkspaceNextAction {
                    kind: SemanticWorkspaceNextActionKind::UseHeadlessDistribution,
                    command: format!(
                        "From a supported installed headless distribution, run `kast agent verify --workspace-root '{exact_root}' --backend=headless`."
                    ),
                    mutates_global_install_authority: false,
                },
            ],
            backend_candidates: vec![],
        },
    }
}
