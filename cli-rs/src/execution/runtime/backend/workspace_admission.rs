pub(crate) use indexer_authority::{
    AdmittedIndexerRuntime, LegacyBackendMigrationPlan, SupportedIndexerDistribution,
};

pub(crate) fn plan_legacy_backend_migration(
    config_contents: &str,
) -> Result<LegacyBackendMigrationPlan> {
    indexer_authority::plan_legacy_backend_migration(config_contents)
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

pub(crate) type SemanticWorkspaceAdmission = AdmittedIndexerRuntime;

#[derive(Debug, Clone)]
pub(crate) struct SemanticWorkspaceRejection {
    pub code: &'static str,
    pub message: String,
    pub supported_distribution: Option<SupportedIndexerDistribution>,
    pub evidence: SemanticWorkspaceEvidence,
}

impl SemanticWorkspaceRejection {
    pub(crate) fn into_cli_error(self) -> CliError {
        let mut error = CliError::new(self.code, self.message);
        if let Some(distribution) = self.supported_distribution {
            error.details.insert(
                "supportedDistribution".to_string(),
                distribution.wire_value().to_string(),
            );
        }
        error.details.insert(
            "semanticWorkspace".to_string(),
            serde_json::to_string(&self.evidence).unwrap_or_default(),
        );
        error
    }
}

pub(crate) enum SemanticWorkspaceRoute<C: lifecycle_typestate::RequiredCapability = lifecycle_typestate::SourceCapability> {
    Admitted(Box<AdmittedIndexerRuntime<C>>),
    Rejected(SemanticWorkspaceRejection),
}

#[derive(Debug, Clone)]
pub(crate) struct SourceReadyRuntime {
    source: lifecycle_typestate::SourceReady<lifecycle_typestate::SourceCapability>,
    backend_name: String,
    references: ReferenceLaneEvidence,
    source_module_count: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ReferenceLaneEvidence {
    Ready,
    Blocked,
}

impl SourceReadyRuntime {
    pub(crate) fn workspace_root(&self) -> &Path {
        self.source.runtime().root().as_path()
    }

    pub(crate) fn backend_name(&self) -> &str {
        &self.backend_name
    }

    pub(crate) fn reference_index_ready(&self) -> bool {
        matches!(self.references, ReferenceLaneEvidence::Ready)
    }

    pub(crate) fn source_module_count(&self) -> usize {
        self.source_module_count
    }

    pub(crate) fn source_revision(&self) -> u64 {
        self.source.revision().value()
    }
}

pub(crate) fn demand_source_ready_runtime(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SourceReadyRuntime> {
    let route = semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, false, false),
        indexer_authority::SemanticRuntimeAvailability::StartIfMissing,
        lifecycle_typestate::Demand::<lifecycle_typestate::SourceCapability>::new(),
    )?;
    let admission = match route {
        SemanticWorkspaceRoute::Admitted(admission) => admission,
        SemanticWorkspaceRoute::Rejected(rejection) => return Err(rejection.into_cli_error()),
    };
    let epoch = admission.validate_current()?;
    let source = epoch.capability_ready()?;
    let status = admission
        .candidate()
        .runtime_status
        .as_ref()
        .ok_or_else(|| {
            CliError::new(
                "SOURCE_READY_EVIDENCE_MISSING",
                "Source-ready admission returned no runtime epoch evidence.",
            )
        })?;
    if status.state != RuntimeState::Ready
        || !status.active()
        || status.indexing()
        || status.source_module_names.is_empty()
    {
        return Err(CliError::new(
            "SOURCE_READY_EVIDENCE_INVALID",
            "Source-ready admission returned an epoch without committed source evidence.",
        ));
    }
    Ok(SourceReadyRuntime {
        source,
        backend_name: status.backend_name.clone(),
        references: if status.reference_index_ready() {
            ReferenceLaneEvidence::Ready
        } else {
            ReferenceLaneEvidence::Blocked
        },
        source_module_count: status.source_module_names.len(),
    })
}

#[derive(Debug, Clone)]
pub(crate) struct ReferenceReadyRuntime {
    ready: lifecycle_typestate::ReferenceReady<lifecycle_typestate::ReferenceCapability>,
}

impl ReferenceReadyRuntime {
    pub(crate) fn workspace_root(&self) -> &Path {
        self.ready.source().runtime().root().as_path()
    }
}

pub(crate) fn demand_reference_ready_runtime(
    requested_workspace_root: Option<PathBuf>,
) -> Result<ReferenceReadyRuntime> {
    let route = semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, false, false),
        indexer_authority::SemanticRuntimeAvailability::StartIfMissing,
        lifecycle_typestate::Demand::<lifecycle_typestate::ReferenceCapability>::new(),
    )?;
    let admission = match route {
        SemanticWorkspaceRoute::Admitted(admission) => admission,
        SemanticWorkspaceRoute::Rejected(rejection) => return Err(rejection.into_cli_error()),
    };
    let ready = admission.validate_current()?.capability_ready()?;
    Ok(ReferenceReadyRuntime { ready })
}

pub(crate) fn semantic_workspace_route(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRoute> {
    semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, true, false),
        indexer_authority::SemanticRuntimeAvailability::StartIfMissing,
        lifecycle_typestate::Demand::<lifecycle_typestate::SourceCapability>::new(),
    )
}

pub(crate) fn semantic_workspace_route_reuse_only(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRoute> {
    semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, true, true),
        indexer_authority::SemanticRuntimeAvailability::ReuseOnly,
        lifecycle_typestate::Demand::<lifecycle_typestate::SourceCapability>::new(),
    )
}

pub(crate) fn semantic_workspace_route_for_runtime(
    args: RuntimeArgs,
) -> Result<SemanticWorkspaceRoute> {
    let availability = if args.no_auto_start.unwrap_or(false) {
        indexer_authority::SemanticRuntimeAvailability::ReuseOnly
    } else {
        indexer_authority::SemanticRuntimeAvailability::StartIfMissing
    };
    semantic_workspace_route_with_availability(
        args,
        availability,
        lifecycle_typestate::Demand::<lifecycle_typestate::SourceCapability>::new(),
    )
}

pub(crate) fn semantic_workspace_route_ready(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRoute> {
    semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, false, true),
        indexer_authority::SemanticRuntimeAvailability::ReuseOnly,
        lifecycle_typestate::Demand::<lifecycle_typestate::SourceCapability>::new(),
    )
}

pub(crate) fn semantic_graph_workspace_route_ready(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRoute<lifecycle_typestate::GraphCapability>> {
    semantic_workspace_route_with_availability(
        semantic_runtime_args(requested_workspace_root, false, true),
        indexer_authority::SemanticRuntimeAvailability::ReuseOnly,
        lifecycle_typestate::Demand::<lifecycle_typestate::GraphCapability>::new(),
    )
}

fn semantic_workspace_route_with_availability<C: lifecycle_typestate::RequiredCapability>(
    args: RuntimeArgs,
    availability: indexer_authority::SemanticRuntimeAvailability,
    demand: lifecycle_typestate::Demand<C>,
) -> Result<SemanticWorkspaceRoute<C>> {
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
    if !is_gradle_workspace(&workspace_root) {
        return Ok(SemanticWorkspaceRoute::Rejected(semantic_workspace_rejection(
            indexer_authority::lifecycle_blocker_rejection(
                &workspace_root,
                SemanticWorkspaceKind::UnsupportedProject,
                lifecycle_typestate::LifecycleBlocker::UnsupportedRoot,
            ),
        )));
    }
    let request = indexer_authority::SemanticRuntimeRequest {
        demand,
        workspace_root,
        config,
        workspace_kind,
        availability,
        accept_indexing: args.accept_indexing.unwrap_or(false),
        wait_timeout_ms: args.wait_timeout_ms,
        runtime_args: args,
    };
    Ok(match indexer_authority::admit_indexer_runtime(request) {
        Ok(admission) => SemanticWorkspaceRoute::Admitted(Box::new(admission)),
        Err(rejection) => {
            SemanticWorkspaceRoute::Rejected(semantic_workspace_rejection(rejection))
        }
    })
}

pub(crate) fn semantic_mutation_workspace_route(
    requested_workspace_root: Option<PathBuf>,
) -> Result<SemanticWorkspaceRoute> {
    semantic_workspace_route_reuse_only(requested_workspace_root)
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
    if runtime_status.indexing() {
        limitations.push(SemanticWorkspaceLimitation::RuntimeIndexing);
    }
    if runtime_status.source_module_names.is_empty() {
        limitations.push(SemanticWorkspaceLimitation::SourceModulesUnavailable);
    }
    if !runtime_status.reference_index_ready() {
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
    rejection: indexer_authority::SemanticRuntimeRejection,
) -> SemanticWorkspaceRejection {
    SemanticWorkspaceRejection {
        code: rejection.code,
        message: rejection.message,
        supported_distribution: rejection.supported_distribution,
        evidence: *rejection.evidence,
    }
}

fn semantic_runtime_args(
    workspace_root: Option<PathBuf>,
    accept_indexing: bool,
    no_auto_start: bool,
) -> RuntimeArgs {
    RuntimeArgs {
        workspace_root,
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
