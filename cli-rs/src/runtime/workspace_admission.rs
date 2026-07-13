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
    pub backend_name: String,
    pub workspace_root: String,
    pub workspace_kind: SemanticWorkspaceKind,
    pub source_module_names: Vec<String>,
    pub limitations: Vec<SemanticWorkspaceLimitation>,
    pub evidence_quality: SemanticEvidenceQuality,
    pub next_actions: Vec<SemanticWorkspaceNextAction>,
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
    let preference = runtime_backend_preference(&config, requested_backend);
    let backend_name = preference
        .fixed_backend()
        .unwrap_or_else(default_semantic_backend);
    let workspace_kind = classify_semantic_workspace(
        &workspace_root,
        has_registered_semantic_state(&config, &workspace_root),
    );
    if workspace_kind == SemanticWorkspaceKind::UnsupportedProject {
        return Ok(SemanticWorkspaceRoute::Rejected(
            unsupported_workspace_rejection(&workspace_root, backend_name),
        ));
    }

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
        backend_name: runtime_status.backend_name.clone(),
        workspace_root: runtime_root.display().to_string(),
        workspace_kind: admission.workspace_kind,
        source_module_names: runtime_status.source_module_names.clone(),
        limitations,
        evidence_quality: SemanticEvidenceQuality::CompilerBacked,
        next_actions: vec![],
    })
}

fn default_semantic_backend() -> BackendName {
    if cfg!(target_os = "macos") {
        BackendName::Idea
    } else {
        BackendName::Headless
    }
}

fn classify_semantic_workspace(
    workspace_root: &Path,
    has_registered_semantic_state: bool,
) -> SemanticWorkspaceKind {
    if !is_gradle_workspace(workspace_root)
        && !workspace_root.join(".kast/setup/workspace.json").is_file()
        && !has_registered_semantic_state
    {
        return SemanticWorkspaceKind::UnsupportedProject;
    }
    if workspace_root.join(".git").is_file() {
        return SemanticWorkspaceKind::LinkedWorktree;
    }
    if workspace_root.join(".git").is_dir() {
        return SemanticWorkspaceKind::PrimaryCheckout;
    }
    let temporary_root = fs::canonicalize(std::env::temp_dir())
        .unwrap_or_else(|_| config::normalize(std::env::temp_dir()));
    if workspace_root.starts_with(temporary_root) {
        return SemanticWorkspaceKind::DisposableCheckout;
    }
    SemanticWorkspaceKind::StandaloneGradleWorkspace
}

fn has_registered_semantic_state(config: &KastConfig, workspace_root: &Path) -> bool {
    read_descriptors(&config.paths.descriptor_dir)
        .unwrap_or_default()
        .iter()
        .any(|descriptor| descriptor_matches_workspace(descriptor, workspace_root))
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
            backend_name: backend_name.canonical().to_string(),
            workspace_root: workspace_root.display().to_string(),
            workspace_kind: SemanticWorkspaceKind::UnsupportedProject,
            source_module_names: vec![],
            limitations: vec![SemanticWorkspaceLimitation::UnsupportedProject],
            evidence_quality: SemanticEvidenceQuality::Unavailable,
            next_actions: vec![],
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
            backend_name: backend_name.canonical().to_string(),
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
                        "Open `{exact_root}` in IntelliJ IDEA or Android Studio with the Homebrew-coupled Kast plugin enabled, then run `kast agent verify --workspace-root '{exact_root}' --backend=idea`."
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
        },
    }
}
