pub(crate) use headless_authority::{
    AdmittedHeadlessRuntime, LegacyBackendMigrationPlan,
};

pub(crate) fn plan_legacy_backend_migration(
    config_contents: &str,
) -> Result<LegacyBackendMigrationPlan> {
    headless_authority::plan_legacy_backend_migration(config_contents)
}

pub(crate) fn require_headless_backend(backend_name: BackendName) -> Result<()> {
    headless_authority::require_headless_backend(backend_name)
}

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
    SourceModulesUnavailable,
    UnsupportedProject,
    BackendSelectionAmbiguous,
    RuntimeIndexing,
    ReferenceIndexUnavailable,
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

pub(crate) type SemanticWorkspaceAdmission = AdmittedHeadlessRuntime;

#[derive(Debug, Clone)]
pub(crate) struct SemanticWorkspaceRejection {
    pub code: &'static str,
    pub message: String,
    pub details: std::collections::BTreeMap<String, String>,
    pub evidence: SemanticWorkspaceEvidence,
}

impl SemanticWorkspaceRejection {
    pub(crate) fn into_cli_error(self) -> CliError {
        let mut error = CliError::new(self.code, self.message);
        error.details = self.details;
        error.details.insert(
            "semanticWorkspace".to_string(),
            serde_json::to_string(&self.evidence).unwrap_or_default(),
        );
        error
    }
}

pub(crate) enum SemanticWorkspaceRoute {
    Admitted(Box<SemanticWorkspaceAdmission>),
    Rejected(SemanticWorkspaceRejection),
}

pub(crate) fn semantic_workspace_route(
    requested_workspace_root: Option<PathBuf>,
    requested_backend: Option<BackendName>,
) -> Result<SemanticWorkspaceRoute> {
    semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, requested_backend, true, false),
        headless_authority::SemanticRuntimeAvailability::StartIfMissing,
    )
}

pub(crate) fn semantic_workspace_route_reuse_only(
    requested_workspace_root: Option<PathBuf>,
    requested_backend: Option<BackendName>,
) -> Result<SemanticWorkspaceRoute> {
    semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, requested_backend, true, true),
        headless_authority::SemanticRuntimeAvailability::ReuseOnly,
    )
}

pub(crate) fn semantic_workspace_route_for_runtime(
    args: RuntimeArgs,
) -> Result<SemanticWorkspaceRoute> {
    let availability = if args.no_auto_start.unwrap_or(false) {
        headless_authority::SemanticRuntimeAvailability::ReuseOnly
    } else {
        headless_authority::SemanticRuntimeAvailability::StartIfMissing
    };
    semantic_workspace_route_with_availability(args, availability)
}

pub(crate) fn semantic_workspace_route_ready(
    requested_workspace_root: Option<PathBuf>,
    requested_backend: Option<BackendName>,
) -> Result<SemanticWorkspaceRoute> {
    semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, requested_backend, false, true),
        headless_authority::SemanticRuntimeAvailability::ReuseOnly,
    )
}

fn semantic_workspace_route_with_availability(
    args: RuntimeArgs,
    availability: headless_authority::SemanticRuntimeAvailability,
) -> Result<SemanticWorkspaceRoute> {
    let requested_backend = args.backend_name;
    let workspace_root = workspace_root(args.workspace_root.clone())?;
    let workspace_root = fs::canonicalize(&workspace_root).map_err(|error| {
        CliError::new(
            "WORKSPACE_ROOT_INVALID",
            format!(
                "Workspace root {} could not be canonicalized: {error}",
                workspace_root.display()
            ),
        )
    })?;
    let config = KastConfig::load(&workspace_root)?;
    let workspace_kind = classify_semantic_workspace(&workspace_root);
    if let Some(rejection) = headless_authority::retired_backend_rejection(
        &config,
        requested_backend,
        &workspace_root,
        workspace_kind,
    ) {
        return Ok(SemanticWorkspaceRoute::Rejected(
            semantic_workspace_rejection(rejection),
        ));
    }
    if !is_gradle_workspace(&workspace_root) {
        return Ok(SemanticWorkspaceRoute::Rejected(
            unsupported_workspace_rejection(&workspace_root),
        ));
    }
    let request = headless_authority::SemanticRuntimeRequest {
        workspace_root,
        config,
        requested_backend,
        workspace_kind,
        availability,
        accept_indexing: args.accept_indexing.unwrap_or(false),
        wait_timeout_ms: args.wait_timeout_ms,
        runtime_args: args,
    };
    Ok(match headless_authority::admit_headless_runtime(request) {
        Ok(admission) => SemanticWorkspaceRoute::Admitted(Box::new(admission)),
        Err(rejection) => {
            SemanticWorkspaceRoute::Rejected(semantic_workspace_rejection(rejection))
        }
    })
}

pub(crate) fn semantic_mutation_workspace_route(
    requested_workspace_root: Option<PathBuf>,
    requested_backend: Option<BackendName>,
) -> Result<SemanticWorkspaceRoute> {
    semantic_workspace_route_reuse_only(requested_workspace_root, requested_backend)
}

pub(crate) fn compiler_backed_workspace_evidence(
    admission: &SemanticWorkspaceAdmission,
    runtime_status: &RuntimeStatusResponse,
) -> Option<SemanticWorkspaceEvidence> {
    let runtime_root = config::normalize(PathBuf::from(&runtime_status.workspace_root));
    if runtime_root != admission.workspace_root()
        || runtime_status.backend_name != admission.backend_name()
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
        workspace_kind: admission.workspace_kind(),
        source_module_names: runtime_status.source_module_names.clone(),
        limitations,
        evidence_quality: SemanticEvidenceQuality::CompilerBacked,
        backend_candidates: vec![],
    })
}

fn semantic_workspace_rejection(
    rejection: headless_authority::SemanticRuntimeRejection,
) -> SemanticWorkspaceRejection {
    SemanticWorkspaceRejection {
        code: rejection.code,
        message: rejection.message,
        details: rejection.details,
        evidence: *rejection.evidence,
    }
}

fn semantic_runtime_args(
    workspace_root: Option<PathBuf>,
    backend_name: Option<BackendName>,
    accept_indexing: bool,
    no_auto_start: bool,
) -> RuntimeArgs {
    RuntimeArgs {
        workspace_root,
        backend_name,
        idea_home: None,
        wait_timeout_ms: crate::cli::DEFAULT_RUNTIME_WAIT_TIMEOUT_MS,
        accept_indexing: Some(accept_indexing),
        no_auto_start: Some(no_auto_start),
        socket_path: None,
        module_name: None,
        source_roots: None,
        classpath: None,
        request_timeout_ms: None,
        max_results: None,
        max_concurrent_requests: None,
        profile: false,
        profile_modes: None,
        profile_duration: None,
        profile_otlp_endpoint: None,
    }
}

fn classify_semantic_workspace(workspace_root: &Path) -> SemanticWorkspaceKind {
    if workspace_root.join(".git").is_file() {
        return SemanticWorkspaceKind::LinkedWorktree;
    }
    let temporary_root = fs::canonicalize(std::env::temp_dir())
        .unwrap_or_else(|_| config::normalize(std::env::temp_dir()));
    if workspace_root.starts_with(&temporary_root) {
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

fn unsupported_workspace_rejection(workspace_root: &Path) -> SemanticWorkspaceRejection {
    SemanticWorkspaceRejection {
        code: "SEMANTIC_WORKSPACE_UNSUPPORTED",
        message: format!(
            "{} is not a supported Kotlin Gradle workspace. Select a workspace containing settings.gradle(.kts) or build.gradle(.kts).",
            workspace_root.display()
        ),
        details: std::collections::BTreeMap::new(),
        evidence: SemanticWorkspaceEvidence {
            backend_name: Some(BackendName::Headless.canonical().to_string()),
            workspace_root: workspace_root.display().to_string(),
            workspace_kind: SemanticWorkspaceKind::UnsupportedProject,
            source_module_names: vec![],
            limitations: vec![SemanticWorkspaceLimitation::UnsupportedProject],
            evidence_quality: SemanticEvidenceQuality::Unavailable,
            backend_candidates: vec![],
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn workspace_rejection_projects_runtime_details_and_semantic_evidence() {
        let rejection = SemanticWorkspaceRejection {
            code: "NO_BACKEND_AVAILABLE",
            message: "backend unavailable".to_string(),
            details: std::collections::BTreeMap::from([(
                "supportedDistribution".to_string(),
                "linux-headless-tarball".to_string(),
            )]),
            evidence: SemanticWorkspaceEvidence {
                backend_name: Some("headless".to_string()),
                workspace_root: "/workspace".to_string(),
                workspace_kind: SemanticWorkspaceKind::StandaloneGradleWorkspace,
                source_module_names: vec![],
                limitations: vec![SemanticWorkspaceLimitation::SourceModulesUnavailable],
                evidence_quality: SemanticEvidenceQuality::Unavailable,
                backend_candidates: vec![],
            },
        };

        let error = rejection.into_cli_error();

        assert_eq!(
            error.details.get("supportedDistribution"),
            Some(&"linux-headless-tarball".to_string())
        );
        assert!(error.details.contains_key("semanticWorkspace"));
    }
}
