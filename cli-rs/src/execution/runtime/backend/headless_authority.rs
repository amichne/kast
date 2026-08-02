use super::*;

#[derive(Debug, Clone)]
pub(crate) struct AdmittedHeadlessRuntime {
    workspace_root: PathBuf,
    workspace_kind: SemanticWorkspaceKind,
    config: KastConfig,
    candidate: RuntimeCandidateStatus,
    capabilities: AdmittedHeadlessCapabilities,
    started: bool,
    process_identity: WorkspaceLeaseProcessIdentity,
    observed_socket_file_identity: Option<RuntimeSocketFileIdentity>,
}

impl AdmittedHeadlessRuntime {
    pub(crate) fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }

    pub(crate) fn workspace_kind(&self) -> SemanticWorkspaceKind {
        self.workspace_kind
    }

    pub(crate) fn backend_name(&self) -> &'static str {
        BackendName::Headless.canonical()
    }

    pub(crate) fn backend(&self) -> BackendName {
        BackendName::Headless
    }

    pub(crate) fn config(&self) -> &KastConfig {
        &self.config
    }

    pub(crate) fn candidate(&self) -> &RuntimeCandidateStatus {
        &self.candidate
    }

    pub(crate) fn supports_mutation(&self, capability: SemanticMutationCapability) -> bool {
        self.capabilities
            .mutation_capabilities
            .contains(&capability)
    }

    pub(crate) fn started(&self) -> bool {
        self.started
    }

    pub(crate) fn validate_current(&self) -> Result<()> {
        validate_admitted_runtime_current(self)
    }
}

#[derive(Debug, Clone)]
struct AdmittedHeadlessCapabilities {
    mutation_capabilities: Vec<SemanticMutationCapability>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SemanticRuntimeAvailability {
    ReuseOnly,
    StartIfMissing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SupportedHeadlessDistribution {
    #[cfg(any(not(target_os = "macos"), test))]
    LinuxHeadlessTarball,
}

impl SupportedHeadlessDistribution {
    pub(crate) const fn wire_value(self) -> &'static str {
        match self {
            #[cfg(any(not(target_os = "macos"), test))]
            Self::LinuxHeadlessTarball => "linux-headless-tarball",
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct SemanticRuntimeRequest {
    pub(crate) workspace_root: PathBuf,
    pub(crate) config: KastConfig,
    pub(crate) requested_backend: Option<BackendName>,
    pub(crate) workspace_kind: SemanticWorkspaceKind,
    pub(crate) availability: SemanticRuntimeAvailability,
    pub(crate) accept_indexing: bool,
    pub(crate) wait_timeout_ms: u64,
    pub(crate) runtime_args: RuntimeArgs,
}

#[derive(Debug, Clone)]
pub(crate) struct SemanticRuntimeRejection {
    pub(crate) code: &'static str,
    pub(crate) message: String,
    pub(crate) supported_distribution: Option<SupportedHeadlessDistribution>,
    pub(crate) evidence: Box<SemanticWorkspaceEvidence>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum LegacyBackendMigrationPlan {
    NoChange,
    Replace(RetiredIdeaBackendPatch),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct RetiredIdeaBackendPatch {
    migrated_contents: String,
}

impl RetiredIdeaBackendPatch {
    pub(crate) fn migrated_contents(&self) -> &str {
        &self.migrated_contents
    }
}

#[derive(Deserialize)]
struct LegacyBackendConfigDocument {
    runtime: Option<LegacyRuntimeSection>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LegacyRuntimeSection {
    default_backend: Option<crate::config::RuntimeDefaultBackend>,
}

enum BackendIngress {
    Requested(BackendName),
    Configured(crate::config::RuntimeDefaultBackend),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ClassifiedBackendIntent {
    Headless,
    RetiredIdea,
    Automatic,
}

fn classify_backend_ingress(ingress: BackendIngress) -> ClassifiedBackendIntent {
    match ingress {
        BackendIngress::Requested(BackendName::Idea)
        | BackendIngress::Configured(crate::config::RuntimeDefaultBackend::Idea) => {
            ClassifiedBackendIntent::RetiredIdea
        }
        BackendIngress::Requested(BackendName::Headless)
        | BackendIngress::Configured(crate::config::RuntimeDefaultBackend::Headless) => {
            ClassifiedBackendIntent::Headless
        }
        BackendIngress::Configured(crate::config::RuntimeDefaultBackend::Auto) => {
            ClassifiedBackendIntent::Automatic
        }
    }
}

fn effective_backend_intent(
    requested_backend: Option<BackendName>,
    configured_backend: crate::config::RuntimeDefaultBackend,
) -> ClassifiedBackendIntent {
    requested_backend.map_or_else(
        || classify_backend_ingress(BackendIngress::Configured(configured_backend)),
        |backend| classify_backend_ingress(BackendIngress::Requested(backend)),
    )
}

fn retired_idea_cli_error() -> CliError {
    CliError::new(
        "IDEA_SEMANTIC_BACKEND_RETIRED",
        "The foreground IDEA semantic backend is retired. Remove --backend=idea or select --backend=headless.",
    )
}

fn retired_idea_rejection(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
) -> SemanticRuntimeRejection {
    SemanticRuntimeRejection {
        code: "IDEA_SEMANTIC_BACKEND_RETIRED",
        message: "The foreground IDEA semantic backend is retired. Remove --backend=idea or select --backend=headless.".to_string(),
        supported_distribution: None,
        evidence: Box::new(unavailable_evidence(workspace_root, workspace_kind)),
    }
}

pub(super) fn plan_legacy_backend_migration(
    config_contents: &str,
) -> Result<LegacyBackendMigrationPlan> {
    let configured_backend = toml::from_str::<LegacyBackendConfigDocument>(config_contents)?
        .runtime
        .and_then(|runtime| runtime.default_backend);
    let Some(configured_backend) = configured_backend else {
        return Ok(LegacyBackendMigrationPlan::NoChange);
    };
    match classify_backend_ingress(BackendIngress::Configured(configured_backend)) {
        ClassifiedBackendIntent::RetiredIdea => {
            use toml_edit::{DocumentMut, value};

            let mut document = config_contents.parse::<DocumentMut>().map_err(|error| {
                CliError::new(
                    "CONFIG_PARSE_FAILED",
                    format!("Could not parse the legacy Kast configuration: {error}"),
                )
            })?;
            document["runtime"]["defaultBackend"] = value("headless");
            Ok(LegacyBackendMigrationPlan::Replace(
                RetiredIdeaBackendPatch {
                    migrated_contents: document.to_string(),
                },
            ))
        }
        ClassifiedBackendIntent::Headless | ClassifiedBackendIntent::Automatic => {
            Ok(LegacyBackendMigrationPlan::NoChange)
        }
    }
}

pub(super) fn require_headless_backend(backend: BackendName) -> Result<()> {
    match classify_backend_ingress(BackendIngress::Requested(backend)) {
        ClassifiedBackendIntent::Headless => Ok(()),
        ClassifiedBackendIntent::RetiredIdea => Err(retired_idea_cli_error()),
        ClassifiedBackendIntent::Automatic => unreachable!("requested backend is never automatic"),
    }
}

pub(super) fn retired_backend_rejection(
    config: &KastConfig,
    requested_backend: Option<BackendName>,
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
) -> Option<SemanticRuntimeRejection> {
    (effective_backend_intent(requested_backend, config.runtime.default_backend)
        == ClassifiedBackendIntent::RetiredIdea)
        .then(|| retired_idea_rejection(workspace_root, workspace_kind))
}

pub(super) fn admit_headless_runtime(
    request: SemanticRuntimeRequest,
) -> std::result::Result<AdmittedHeadlessRuntime, SemanticRuntimeRejection> {
    if effective_backend_intent(
        request.requested_backend,
        request.config.runtime.default_backend,
    ) == ClassifiedBackendIntent::RetiredIdea
    {
        return Err(retired_idea_rejection(
            &request.workspace_root,
            request.workspace_kind,
        ));
    }

    let (candidate, started) = match admitted_candidate(&request) {
        Ok(candidate) => (candidate, false),
        Err(rejection)
            if request.availability == SemanticRuntimeAvailability::StartIfMissing
                && matches!(rejection.code, "NO_BACKEND_AVAILABLE" | "RUNTIME_NOT_READY") =>
        {
            start_headless_runtime(&request)?
        }
        Err(rejection) => return Err(rejection),
    };
    construct_admitted_runtime(request, candidate, started)
}

fn admitted_candidate(
    request: &SemanticRuntimeRequest,
) -> std::result::Result<RuntimeCandidateStatus, SemanticRuntimeRejection> {
    let inspection = inspect_headless_workspace_with_config(
        &request.workspace_root,
        &request.config,
        StaleDescriptorPolicy::Preserve,
    )
    .map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let reachable_candidates = inspection
        .candidates
        .into_iter()
        .filter(|candidate| candidate.runtime_status.as_ref().is_some_and(is_servable))
        .collect::<Vec<_>>();
    if reachable_candidates.len() > 1 {
        return Err(headless_conflict_rejection(
            &request.workspace_root,
            request.workspace_kind,
            &reachable_candidates,
        ));
    }
    let candidate = reachable_candidates.into_iter().next().filter(|candidate| {
        candidate.runtime_status.as_ref().is_some_and(|status| {
            if request.accept_indexing {
                is_servable(status)
            } else {
                is_ready(status)
            }
        })
    });
    candidate.ok_or_else(|| {
        unavailable_rejection(
            &request.workspace_root,
            request.workspace_kind,
            request.accept_indexing,
        )
    })
}

fn construct_admitted_runtime(
    request: SemanticRuntimeRequest,
    candidate: RuntimeCandidateStatus,
    started: bool,
) -> std::result::Result<AdmittedHeadlessRuntime, SemanticRuntimeRejection> {
    let descriptor = &candidate.descriptor;
    let runtime_status = candidate.runtime_status.as_ref().ok_or_else(|| {
        unavailable_rejection(
            &request.workspace_root,
            request.workspace_kind,
            request.accept_indexing,
        )
    })?;
    let canonical_descriptor_root =
        canonical_existing_root(&descriptor.workspace_root).map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    let canonical_status_root =
        canonical_existing_root(&runtime_status.workspace_root).map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    if canonical_descriptor_root != request.workspace_root
        || canonical_status_root != request.workspace_root
        || descriptor.backend_name != BackendName::Headless.canonical()
        || runtime_status.backend_name != BackendName::Headless.canonical()
        || descriptor.backend_version != runtime_status.backend_version
        || descriptor.schema_version != SCHEMA_VERSION
        || runtime_status.schema_version != SCHEMA_VERSION
        || !candidate.pid_alive
        || !runtime_status.healthy
        || !runtime_status.active
    {
        return Err(runtime_identity_rejection(
            &request.workspace_root,
            request.workspace_kind,
        ));
    }
    validate_descriptor_owner(descriptor).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let process_identity = process_identity(descriptor.pid).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let observed_socket_file_identity = current_socket_file_identity(&descriptor.socket_path)
        .map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    let capabilities = parse_admitted_capabilities(&request, &candidate)?;
    Ok(AdmittedHeadlessRuntime {
        workspace_root: request.workspace_root,
        workspace_kind: request.workspace_kind,
        config: request.config,
        candidate,
        capabilities,
        started,
        process_identity,
        observed_socket_file_identity,
    })
}

include!("headless_authority/runtime.rs");

#[cfg(test)]
#[path = "headless_authority/tests/mod.rs"]
mod tests;
