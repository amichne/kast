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
