use super::*;

#[path = "indexer_authority/ownership.rs"]
mod ownership;
#[path = "indexer_authority/process.rs"]
mod process;
#[path = "indexer_authority/registration.rs"]
mod registration;
#[path = "indexer_authority/repair.rs"]
mod repair;
#[path = "indexer_authority/service_manager.rs"]
mod service_manager;

use super::lifecycle_typestate::{
    CanonicalWorkspaceRoot, Demand, LifecycleBlocker as TypestateLifecycleBlocker,
    RequiredCapability, RuntimeAvailable, RuntimeEpochId, RuntimeEpochIdentity, SourceCapability,
    StartingEpoch,
};
use ownership::{RuntimeOwnershipSnapshot, reconcile_runtime_ownership};
pub(crate) use registration::{
    RuntimeSetupAuthorization, RuntimeSetupIntent, preflight_runtime_setup,
};
use registration::{prepare_service_registration, publish_active_registration};
pub(crate) use repair::service_entrypoint;

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum LifecycleOwnershipObservation {
    Absent,
    ExactOwned { runtime_instance_id: String },
    Blocked { code: &'static str, message: String },
}

pub(crate) fn inspect_lifecycle_ownership(
    config: &KastConfig,
    workspace_root: &Path,
) -> Result<LifecycleOwnershipObservation> {
    Ok(match reconcile_runtime_ownership(config, workspace_root)? {
        RuntimeOwnershipSnapshot::Absent(_) => LifecycleOwnershipObservation::Absent,
        RuntimeOwnershipSnapshot::ServiceOwned(owned) if owned.proven_dead.is_empty() => {
            LifecycleOwnershipObservation::ExactOwned {
                runtime_instance_id: owned.registration.receipt.runtime_instance_id.to_string(),
            }
        }
        RuntimeOwnershipSnapshot::ServiceOwned(_) | RuntimeOwnershipSnapshot::ProvenDead(_) => {
            LifecycleOwnershipObservation::Blocked {
                code: "RUNTIME_OWNERSHIP_PROVEN_DEAD",
                message: "Proven-dead owned runtime evidence awaits the next semantic demand."
                    .to_string(),
            }
        }
        RuntimeOwnershipSnapshot::LegacyOwned(_) | RuntimeOwnershipSnapshot::Conflict(_) => {
            LifecycleOwnershipObservation::Blocked {
                code: "RUNTIME_OWNERSHIP_CONFLICT",
                message: "Runtime evidence does not establish one reusable exact-owned epoch."
                    .to_string(),
            }
        }
        RuntimeOwnershipSnapshot::Ambiguous(ambiguity) => LifecycleOwnershipObservation::Blocked {
            code: "RUNTIME_OWNERSHIP_AMBIGUOUS",
            message: ambiguity.reason,
        },
    })
}

#[derive(Debug, Clone)]
pub(crate) struct AdmittedIndexerRuntime<C: RequiredCapability = SourceCapability> {
    workspace_root: PathBuf,
    workspace_kind: SemanticWorkspaceKind,
    config: KastConfig,
    candidate: RuntimeCandidateStatus,
    capabilities: AdmittedIndexerCapabilities,
    lifecycle: RuntimeAvailable<C>,
    capability: C::Ready,
    origin: RuntimeAdmissionOrigin,
    process_identity: WorkspaceLeaseProcessIdentity,
    observed_socket_file_identity: RuntimeSocketFileIdentity,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RuntimeAdmissionOrigin {
    Reused,
    Started,
}

#[derive(Debug)]
pub(crate) struct RevalidatedRuntimeEpoch<'a, C: RequiredCapability = SourceCapability> {
    admission: &'a AdmittedIndexerRuntime<C>,
    observed_identity: RuntimeEpochIdentity,
}

impl<C: RequiredCapability> AdmittedIndexerRuntime<C> {
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

    pub(crate) fn origin(&self) -> RuntimeAdmissionOrigin {
        self.origin
    }

    pub(crate) fn validate_current(&self) -> Result<RevalidatedRuntimeEpoch<'_, C>> {
        validate_admitted_runtime_current(self)
    }

    pub(crate) fn admit_captured_capability<D: RequiredCapability>(
        &self,
    ) -> Result<AdmittedIndexerRuntime<D>> {
        let status = self
            .candidate
            .runtime_status
            .as_ref()
            .ok_or_else(capability_unavailable)?;
        let evidence = D::admit(status).map_err(|_| capability_unavailable())?;
        let lifecycle = Demand::<D>::new()
            .admit(self.lifecycle.root().clone())
            .observe_exact(self.lifecycle.identity().clone())
            .revalidated()
            .available();
        let capability = D::finish(lifecycle.clone(), evidence);
        let admission = AdmittedIndexerRuntime {
            workspace_root: self.workspace_root.clone(),
            workspace_kind: self.workspace_kind,
            config: self.config.clone(),
            candidate: self.candidate.clone(),
            capabilities: self.capabilities.clone(),
            lifecycle,
            capability,
            origin: self.origin,
            process_identity: self.process_identity.clone(),
            observed_socket_file_identity: self.observed_socket_file_identity.clone(),
        };
        Ok(admission)
    }
}

impl<C: RequiredCapability> RevalidatedRuntimeEpoch<'_, C> {
    pub(crate) fn identity(&self) -> &RuntimeEpochIdentity {
        &self.observed_identity
    }

    pub(crate) fn capability_ready(&self) -> Result<C::Ready> {
        debug_assert_eq!(self.identity(), self.admission.lifecycle.identity());
        Ok(self.admission.capability.clone())
    }

    pub(crate) fn revalidate_capability(&self, status: &RuntimeStatusResponse) -> Result<C::Ready> {
        debug_assert_eq!(self.identity(), self.admission.lifecycle.identity());
        let evidence = C::admit(status).map_err(|_| capability_unavailable())?;
        let capability = C::finish(self.admission.lifecycle.clone(), evidence);
        if C::stamp(&capability) != C::stamp(&self.admission.capability) {
            return Err(CliError::new(
                "CAPABILITY_REVISION_MOVED",
                "The admitted capability revision changed while the operation was in progress.",
            ));
        }
        Ok(capability)
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
    StartIfMissingOrAwaitCapability,
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
pub(crate) struct SemanticRuntimeRequest<C: RequiredCapability = SourceCapability> {
    pub(crate) demand: Demand<C>,
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

include!("indexer_authority/ownership/admission.rs");

include!("indexer_authority/runtime.rs");

#[cfg(test)]
#[path = "indexer_authority/tests/mod.rs"]
mod tests;
