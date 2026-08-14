const WORKSPACE_LEASE_SCHEMA_VERSION: u32 = 3;
const WORKSPACE_LEASE_TOKEN_VERSION: &str = "kl3";

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceLeaseState {
    Ready,
    Released,
    Abandoned,
    Failed,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceLeaseOwnership {
    Started,
    Borrowed,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum WorkspaceLeaseInstallAuthority {
    ActiveRelease,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceLeaseInstallationIdentity {
    pub authority: WorkspaceLeaseInstallAuthority,
    pub generation: String,
    pub environment_sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceLeaseProcessIdentity {
    pub pid: u64,
    pub started_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceLeaseOwnerIdentity {
    pub process: WorkspaceLeaseProcessIdentity,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_sha256: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "WorkspaceLeaseOwnerScope::is_caller_session"
    )]
    pub scope: WorkspaceLeaseOwnerScope,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceLeaseOwnerScope {
    #[default]
    CallerSession,
    CurrentProcess,
}

impl WorkspaceLeaseOwnerScope {
    fn is_caller_session(&self) -> bool {
        *self == Self::CallerSession
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceLeaseRuntimeIdentity {
    pub descriptor_path: String,
    pub descriptor: ServerInstanceDescriptor,
    pub process: WorkspaceLeaseProcessIdentity,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "outcome",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum WorkspaceLeaseReleaseReceipt {
    RuntimeIdlePolicy { released_at: String },
    RecoveredAbandonedOwner { released_at: String },
}

impl WorkspaceLeaseReleaseReceipt {
    pub(crate) fn released_at(&self) -> &str {
        match self {
            Self::RuntimeIdlePolicy { released_at }
            | Self::RecoveredAbandonedOwner { released_at } => released_at,
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceLeaseFailureReason {
    OwnerAbandoned,
    RuntimeUnavailable,
    RuntimeReplaced,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceLeaseResult {
    pub lease_id: String,
    pub state: WorkspaceLeaseState,
    pub workspace_root: String,
    pub workspace_kind: SemanticWorkspaceKind,
    pub backend_name: BackendName,
    pub runtime: WorkspaceLeaseRuntimeIdentity,
    pub installation: WorkspaceLeaseInstallationIdentity,
    pub ownership: WorkspaceLeaseOwnership,
    pub owner: WorkspaceLeaseOwnerIdentity,
    pub acquired_at: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure_reason: Option<WorkspaceLeaseFailureReason>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub release_receipt: Option<WorkspaceLeaseReleaseReceipt>,
    pub schema_version: u32,
}

pub(crate) struct ValidatedWorkspaceLease {
    runtime: WorkspaceLeaseRuntimeIdentity,
}

impl ValidatedWorkspaceLease {
    pub(crate) fn authorizes<C: lifecycle_typestate::RequiredCapability>(
        &self,
        admission: &AdmittedIndexerRuntime<C>,
    ) -> bool {
        self.runtime.descriptor_path == admission.candidate().descriptor_path
            && self.runtime.descriptor == admission.candidate().descriptor
            && process_identity_is_live(&self.runtime.process)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct WorkspaceLeaseBinding {
    schema_version: u32,
    record_id: uuid::Uuid,
    workspace_root: PathBuf,
    workspace_kind: SemanticWorkspaceKind,
    backend_name: BackendName,
    runtime: WorkspaceLeaseRuntimeIdentity,
    installation: WorkspaceLeaseInstallationIdentity,
    ownership: WorkspaceLeaseOwnership,
    owner: WorkspaceLeaseOwnerIdentity,
    acquired_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
enum WorkspaceLeaseRecord {
    Active {
        binding: WorkspaceLeaseBinding,
        record_mac: String,
    },
    Released {
        binding: WorkspaceLeaseBinding,
        receipt: WorkspaceLeaseReleaseReceipt,
        record_mac: String,
    },
}

impl WorkspaceLeaseRecord {
    fn binding(&self) -> &WorkspaceLeaseBinding {
        match self {
            Self::Active { binding, .. } | Self::Released { binding, .. } => binding,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct WorkspaceLeaseTokenClaims {
    authority: WorkspaceLeaseInstallAuthority,
    generation: String,
    environment_sha256: String,
    workspace_root: PathBuf,
    backend_name: BackendName,
    binding_sha256: String,
    record_id: uuid::Uuid,
}

struct WorkspaceLeasePaths {
    records: PathBuf,
    secret: PathBuf,
    lock: PathBuf,
}

impl WorkspaceLeasePaths {
    fn resolve() -> Result<Self> {
        let paths = crate::manifest::resolve_paths()?;
        Ok(Self {
            records: paths.runtime_dir.join("workspace-leases"),
            secret: paths.install_root.join("state/workspace-lease.key"),
            lock: paths.locks_dir.join("workspace-leases.lock"),
        })
    }

    fn record(&self, record_id: uuid::Uuid) -> PathBuf {
        self.records.join(format!("{record_id}.json"))
    }
}

include!("lease/access.rs");
include!("lease/runtime_binding.rs");
include!("lease/validation.rs");
include!("lease/storage.rs");
