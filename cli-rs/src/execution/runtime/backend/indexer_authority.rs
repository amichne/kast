use super::*;

#[derive(Debug, Clone)]
pub(crate) struct AdmittedIndexerRuntime {
    workspace_root: PathBuf,
    workspace_kind: SemanticWorkspaceKind,
    config: KastConfig,
    candidate: RuntimeCandidateStatus,
    capabilities: AdmittedIndexerCapabilities,
    started: bool,
    process_identity: WorkspaceLeaseProcessIdentity,
    observed_socket_file_identity: Option<RuntimeSocketFileIdentity>,
}

impl AdmittedIndexerRuntime {
    pub(crate) fn workspace_root(&self) -> &Path {
        &self.workspace_root
    }

    pub(crate) fn workspace_kind(&self) -> SemanticWorkspaceKind {
        self.workspace_kind
    }

    pub(crate) fn backend_name(&self) -> &'static str {
        BackendName::Indexer.canonical()
    }

    pub(crate) fn backend(&self) -> BackendName {
        BackendName::Indexer
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
struct AdmittedIndexerCapabilities {
    mutation_capabilities: Vec<SemanticMutationCapability>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SemanticRuntimeAvailability {
    ReuseOnly,
    StartIfMissing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SupportedIndexerDistribution {
    #[cfg(any(not(target_os = "macos"), test))]
    LinuxIndexerTarball,
}

impl SupportedIndexerDistribution {
    pub(crate) const fn wire_value(self) -> &'static str {
        match self {
            #[cfg(any(not(target_os = "macos"), test))]
            Self::LinuxIndexerTarball => "linux-indexer-tarball",
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct SemanticRuntimeRequest {
    pub(crate) workspace_root: PathBuf,
    pub(crate) config: KastConfig,
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
    pub(crate) supported_distribution: Option<SupportedIndexerDistribution>,
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
    idea_launch: Option<LegacyIdeaLaunchSection>,
}

#[derive(Deserialize)]
struct LegacyIdeaLaunchSection {
    command: Option<String>,
}

pub(super) fn plan_legacy_backend_migration(
    config_contents: &str,
) -> Result<LegacyBackendMigrationPlan> {
    let legacy_runtime = toml::from_str::<LegacyBackendConfigDocument>(config_contents)?
        .runtime
        .and_then(|runtime| runtime.idea_launch)
        .and_then(|launch| launch.command);
    let mut document = config_contents
        .parse::<toml_edit::DocumentMut>()
        .map_err(|error| {
            CliError::new(
                "CONFIG_PARSE_FAILED",
                format!("Could not parse the legacy Kast configuration: {error}"),
            )
        })?;
    let has_retired_sections = ["runtime", "projectOpen", "backends"]
        .into_iter()
        .any(|key| document.contains_key(key));
    if !has_retired_sections {
        return Ok(LegacyBackendMigrationPlan::NoChange);
    }
    document.remove("runtime");
    document.remove("projectOpen");
    document.remove("backends");
    if let Some(command) = legacy_runtime {
        if !document.contains_key("indexer") {
            document["indexer"] = toml_edit::Item::Table(toml_edit::Table::new());
        }
        document["indexer"]["hostCommand"] = toml_edit::value(command);
    }
    let mut migrated_contents = document.to_string();
    let missing_comments = config_contents
        .lines()
        .filter(|line| line.trim_start().starts_with('#'))
        .filter(|line| {
            !migrated_contents
                .lines()
                .any(|candidate| candidate == *line)
        })
        .collect::<Vec<_>>();
    for comment in missing_comments.into_iter().rev() {
        migrated_contents.insert_str(0, &format!("{comment}\n"));
    }
    Ok(LegacyBackendMigrationPlan::Replace(
        RetiredIdeaBackendPatch { migrated_contents },
    ))
}

pub(super) fn admit_indexer_runtime(
    request: SemanticRuntimeRequest,
) -> std::result::Result<AdmittedIndexerRuntime, SemanticRuntimeRejection> {
    let (candidate, started) = match admitted_candidate(&request) {
        Ok(candidate) => (candidate, false),
        Err(rejection)
            if request.availability == SemanticRuntimeAvailability::StartIfMissing
                && matches!(rejection.code, "NO_INDEXER_AVAILABLE" | "RUNTIME_NOT_READY") =>
        {
            start_indexer_runtime(&request)?
        }
        Err(rejection) => return Err(rejection),
    };
    construct_admitted_runtime(request, candidate, started)
}

fn admitted_candidate(
    request: &SemanticRuntimeRequest,
) -> std::result::Result<RuntimeCandidateStatus, SemanticRuntimeRejection> {
    let inspection = inspect_indexer_workspace_with_config(
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
        return Err(indexer_conflict_rejection(
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
) -> std::result::Result<AdmittedIndexerRuntime, SemanticRuntimeRejection> {
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
        || descriptor.backend_name != BackendName::Indexer.canonical()
        || runtime_status.backend_name != BackendName::Indexer.canonical()
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
    Ok(AdmittedIndexerRuntime {
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

include!("indexer_authority/runtime.rs");

#[cfg(test)]
#[path = "indexer_authority/tests/mod.rs"]
mod tests;
