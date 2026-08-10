#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationPostconditionQuery {
    authority: AgentMutationPostconditionAuthority,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationPostconditionStatus {
    Verified,
}

#[derive(Debug, Clone, Copy, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum MutationPostconditionOperation {
    Rename,
    Replacement,
    AddFile,
    AddDeclaration,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationPostconditionPostimage {
    file_path: String,
    sha256: String,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationPostconditionResult {
    status: MutationPostconditionStatus,
    operation: MutationPostconditionOperation,
    current_generation: u64,
    postimages: Vec<MutationPostconditionPostimage>,
    evidence: AgentMutationPostconditionEvidence,
    schema_version: u32,
}

impl MutationPostconditionResult {
    fn validate_for(
        &self,
        operation: &StoredOperation,
        transitions: &[ExactMutationTransition],
    ) -> Result<()> {
        let expected_operation = match operation {
            StoredOperation::Rename { .. } => MutationPostconditionOperation::Rename,
            StoredOperation::Replace { .. } => MutationPostconditionOperation::Replacement,
            StoredOperation::AddFile { .. } => MutationPostconditionOperation::AddFile,
            StoredOperation::AddDeclaration { .. } => MutationPostconditionOperation::AddDeclaration,
        };
        let expected_postimages = transitions
            .iter()
            .map(|transition| MutationPostconditionPostimage {
                file_path: transition.absolute_path.clone(),
                sha256: transition.postimage.sha256().to_string(),
            })
            .collect::<Vec<_>>();
        if self.status != MutationPostconditionStatus::Verified
            || self.operation != expected_operation
            || self.schema_version != crate::SCHEMA_VERSION
            || self.postimages != expected_postimages
            || self.current_generation > i64::MAX as u64
            || self.current_generation < operation.minimum_postcondition_generation()
            || operation
                .validate_postcondition_evidence(&self.evidence)
                .is_err()
        {
            return Err(CliError::new(
                "KAST_MUTATION_POSTCONDITION_INVALID",
                "Compiler postcondition evidence did not bind the stored operation and every exact postimage.",
            ));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawApplyEditsResult {
    applied: Vec<Value>,
    affected_files: Vec<String>,
    created_files: Vec<String>,
    deleted_files: Vec<String>,
    schema_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedMutationFile {
    path: String,
    sha256: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct VerifiedMutationDiagnostics {
    error: usize,
    warning: usize,
    info: usize,
    total: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MutationLeaseReceipt {
    state: WorkspaceLeaseState,
    ownership: WorkspaceLeaseOwnership,
    release_receipt: WorkspaceLeaseReleaseReceipt,
    plan_id: Uuid,
    lease_binding_sha256: MutationLeaseBindingSha256,
    workspace_root: PathBuf,
    workspace_kind: runtime::SemanticWorkspaceKind,
    backend_name: crate::cli::BackendName,
    runtime: runtime::WorkspaceLeaseRuntimeIdentity,
    installation: runtime::WorkspaceLeaseInstallationIdentity,
    owner: runtime::WorkspaceLeaseOwnerIdentity,
    acquired_at: String,
    schema_version: u32,
}

const MUTATION_LEASE_RECEIPT_SCHEMA_VERSION: u32 = 2;
const MUTATION_LEASE_BINDING_DOMAIN: &str = "kast-mutation-lease-binding-v1";

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(transparent)]
struct MutationLeaseBindingSha256(String);

impl TryFrom<String> for MutationLeaseBindingSha256 {
    type Error = String;

    fn try_from(value: String) -> std::result::Result<Self, Self::Error> {
        if is_lowercase_sha256(&value) {
            Ok(Self(value))
        } else {
            Err("mutation lease bindings must be lowercase SHA-256 values".to_string())
        }
    }
}

fn is_lowercase_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

impl<'de> Deserialize<'de> for MutationLeaseBindingSha256 {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        Self::try_from(String::deserialize(deserializer)?).map_err(serde::de::Error::custom)
    }
}

impl MutationLeaseBindingSha256 {
    fn for_acquired(
        lease_id: &AgentWorkspaceLeaseId,
        plan_id: Uuid,
        acquired: &runtime::WorkspaceLeaseResult,
    ) -> Result<Self> {
        let payload = serde_json::to_vec(&(
            MUTATION_LEASE_BINDING_DOMAIN,
            lease_id.as_str(),
            plan_id,
            &acquired.workspace_root,
            acquired.workspace_kind,
            acquired.backend_name,
            &acquired.runtime,
            &acquired.installation,
            acquired.ownership,
            &acquired.owner,
            &acquired.acquired_at,
            acquired.schema_version,
        ))?;
        Self::try_from(manifest::sha256_bytes(&payload)).map_err(|message| {
            CliError::new(
                "KAST_MUTATION_LEASE_BINDING_INVALID",
                format!("The acquired mutation lease binding was invalid: {message}."),
            )
        })
    }
}

impl MutationLeaseReceipt {
    fn validate_for(&self, plan_id: Uuid, workspace_root: &Path) -> Result<()> {
        let descriptor = &self.runtime.descriptor;
        let release_is_closed = matches!(
            self.release_receipt,
            WorkspaceLeaseReleaseReceipt::RuntimeIdlePolicy { .. }
        );
        if self.schema_version != MUTATION_LEASE_RECEIPT_SCHEMA_VERSION
            || self.plan_id != plan_id
            || self.workspace_root != workspace_root
            || self.workspace_kind == runtime::SemanticWorkspaceKind::UnsupportedProject
            || self.backend_name != crate::cli::BackendName::Indexer
            || self.state != WorkspaceLeaseState::Released
            || self.acquired_at.trim().is_empty()
            || self.release_receipt.released_at().trim().is_empty()
            || !release_is_closed
            || self.runtime.descriptor_path.trim().is_empty()
            || descriptor.workspace_root != workspace_root.display().to_string()
            || descriptor.backend_name != self.backend_name.canonical()
            || descriptor.backend_version.trim().is_empty()
            || descriptor.schema_version != crate::SCHEMA_VERSION
            || descriptor.pid == 0
            || descriptor.pid != self.runtime.process.pid
            || self.runtime.process.started_at.trim().is_empty()
            || self.installation.generation.trim().is_empty()
            || !is_lowercase_sha256(&self.installation.environment_sha256)
            || self.owner.scope != runtime::WorkspaceLeaseOwnerScope::CurrentProcess
            || self.owner.session_sha256.is_some()
            || self.owner.process.pid == 0
            || self.owner.process.started_at.trim().is_empty()
        {
            return Err(CliError::new(
                "KAST_MUTATION_LEASE_RECEIPT_INVALID",
                "Mutation lease release evidence does not bind its plan, exact root, authenticated identity, and closed release outcome.",
            ));
        }
        Ok(())
    }
}

struct OwnedMutationLease {
    plan_id: Uuid,
    lease_id: AgentWorkspaceLeaseId,
    acquired: runtime::WorkspaceLeaseResult,
}

impl OwnedMutationLease {
    fn acquire(plan_id: Uuid, workspace_root: &Path) -> Result<Self> {
        let acquired = runtime::workspace_lease_acquire_process_owned(AgentLeaseAcquireArgs {
            workspace_root: workspace_root.to_path_buf(),
            wait_timeout_ms: crate::cli::DEFAULT_RUNTIME_WAIT_TIMEOUT_MS,
        })?;
        if acquired.state != WorkspaceLeaseState::Ready
            || acquired.workspace_root != workspace_root.display().to_string()
            || acquired.backend_name != crate::cli::BackendName::Indexer
            || acquired.owner.scope != runtime::WorkspaceLeaseOwnerScope::CurrentProcess
            || acquired.owner.session_sha256.is_some()
            || acquired.failure_reason.is_some()
            || acquired.release_receipt.is_some()
        {
            return Err(CliError::new(
                "WORKSPACE_LEASE_NOT_READY",
                "The internally acquired mutation lease did not return one exact-root process-owned READY identity.",
            ));
        }
        let lease_id = acquired.lease_id.parse().map_err(|message: String| {
            CliError::new(
                "WORKSPACE_LEASE_ID_INVALID",
                format!("The internally acquired mutation lease id was invalid: {message}"),
            )
        })?;
        Ok(Self {
            plan_id,
            lease_id,
            acquired,
        })
    }

    fn id(&self) -> AgentWorkspaceLeaseId {
        self.lease_id.clone()
    }

    fn release(self) -> Result<MutationLeaseReceipt> {
        let lease_binding_sha256 = MutationLeaseBindingSha256::for_acquired(
            &self.lease_id,
            self.plan_id,
            &self.acquired,
        )?;
        if cfg!(debug_assertions)
            && std::env::var("KAST_TEST_MUTATION_LEASE_RELEASE_FAILURE")
                .is_ok_and(|value| value == "1")
        {
            return Err(CliError::new(
                "KAST_TEST_MUTATION_LEASE_RELEASE_FAILED",
                "Mutation lease release failed at the deterministic test seam.",
            ));
        }
        let released = runtime::workspace_lease_release(AgentLeaseAccessArgs {
            lease_id: self.lease_id,
            workspace_root: PathBuf::from(&self.acquired.workspace_root),
        })?;
        if released.state != WorkspaceLeaseState::Released
            || released.lease_id != self.acquired.lease_id
            || released.workspace_root != self.acquired.workspace_root
            || released.workspace_kind != self.acquired.workspace_kind
            || released.backend_name != self.acquired.backend_name
            || released.runtime != self.acquired.runtime
            || released.installation != self.acquired.installation
            || released.ownership != self.acquired.ownership
            || released.owner != self.acquired.owner
            || released.acquired_at != self.acquired.acquired_at
            || released.failure_reason.is_some()
            || released.schema_version != self.acquired.schema_version
        {
            return Err(CliError::new(
                "WORKSPACE_LEASE_RELEASE_INCOMPLETE",
                "The mutation lease release did not retain its exact acquired identity.",
            ));
        }
        let release_receipt = released.release_receipt.clone().ok_or_else(|| {
            CliError::new(
                "WORKSPACE_LEASE_RELEASE_RECEIPT_MISSING",
                "The released mutation lease returned no release receipt.",
            )
        })?;
        let receipt = MutationLeaseReceipt {
            state: released.state,
            ownership: released.ownership,
            release_receipt,
            plan_id: self.plan_id,
            lease_binding_sha256,
            workspace_root: PathBuf::from(&released.workspace_root),
            workspace_kind: released.workspace_kind,
            backend_name: released.backend_name,
            runtime: released.runtime,
            installation: released.installation,
            owner: released.owner,
            acquired_at: released.acquired_at,
            schema_version: MUTATION_LEASE_RECEIPT_SCHEMA_VERSION,
        };
        receipt.validate_for(self.plan_id, Path::new(&self.acquired.workspace_root))?;
        Ok(receipt)
    }
}
