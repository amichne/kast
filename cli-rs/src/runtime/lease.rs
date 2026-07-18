const WORKSPACE_LEASE_SCHEMA_VERSION: u32 = 1;
const WORKSPACE_LEASE_TOKEN_VERSION: &str = "kl1";

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
    LocalDevelopment,
    MacosHomebrew,
    ManagedLocal,
}

impl WorkspaceLeaseInstallAuthority {
    fn canonical(self) -> &'static str {
        match self {
            Self::LocalDevelopment => "local-development",
            Self::MacosHomebrew => "macos-homebrew",
            Self::ManagedLocal => "managed-local",
        }
    }
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
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceLeaseRuntimeIdentity {
    pub descriptor_path: String,
    pub descriptor: ServerInstanceDescriptor,
    pub process: WorkspaceLeaseProcessIdentity,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceLeaseReleaseReceipt {
    pub released_at: String,
    pub runtime_stopped: bool,
    pub reason: WorkspaceLeaseReleaseReason,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WorkspaceLeaseReleaseReason {
    OwnedRuntimeStopped,
    BorrowedRuntimePreserved,
    ExactRuntimeUnavailable,
    RecoveredAbandonedOwner,
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

pub fn workspace_lease_acquire(args: AgentLeaseAcquireArgs) -> Result<WorkspaceLeaseResult> {
    let requested_root = exact_lease_root(&args.workspace_root)?;
    let admission = admitted_lease_workspace(requested_root, args.backend_name)?;
    let initial_installation = lease_installation_identity(
        &admission.workspace_root,
        admission.backend_name,
    )?;
    let paths = WorkspaceLeasePaths::resolve()?;

    with_workspace_lease_lock(&paths, || {
        let secret = read_or_create_workspace_lease_secret(&paths.secret)?;
        recover_or_reject_existing_lease(
            &paths,
            &secret,
            &admission,
            &initial_installation,
        )?;
        let locked_installation = lease_installation_identity(
            &admission.workspace_root,
            admission.backend_name,
        )?;
        if locked_installation != initial_installation {
            return Err(stale_environment_error(
                "The effective agent environment changed before workspace lease acquisition began.",
            ));
        }

        let ensured = ensure_lease_runtime(&admission, args.wait_timeout_ms)?;
        let ownership = if ensured.started {
            WorkspaceLeaseOwnership::Started
        } else {
            WorkspaceLeaseOwnership::Borrowed
        };
        let runtime = runtime_identity(&ensured.selected)?;
        let owner = caller_process_identity()?;
        let acquired_at = crate::manifest::current_timestamp();

        let finalization = (|| {
            let final_installation = lease_installation_identity(
                &admission.workspace_root,
                admission.backend_name,
            )?;
            if final_installation != initial_installation {
                return Err(stale_environment_error(
                    "The effective agent environment changed while the semantic runtime settled.",
                ));
            }
            require_exact_ready_runtime(&admission.workspace_root, admission.backend_name, &runtime)?;
            let record_id = uuid::Uuid::new_v4();
            let binding = WorkspaceLeaseBinding {
                schema_version: WORKSPACE_LEASE_SCHEMA_VERSION,
                record_id,
                workspace_root: admission.workspace_root.clone(),
                workspace_kind: admission.workspace_kind,
                backend_name: admission.backend_name,
                runtime: runtime.clone(),
                installation: final_installation,
                ownership,
                owner: owner.clone(),
                acquired_at: acquired_at.clone(),
            };
            let claims = WorkspaceLeaseTokenClaims {
                authority: binding.installation.authority,
                generation: binding.installation.generation.clone(),
                environment_sha256: binding.installation.environment_sha256.clone(),
                workspace_root: binding.workspace_root.clone(),
                backend_name: binding.backend_name,
                binding_sha256: workspace_lease_binding_digest(&binding)?,
                record_id,
            };
            let lease_id = sign_workspace_lease_token(&secret, &claims)?;
            write_workspace_lease_record(
                &paths.record(record_id),
                &active_workspace_lease_record(&secret, binding.clone())?,
            )?;
            Ok(workspace_lease_result(
                lease_id,
                WorkspaceLeaseState::Ready,
                binding,
                None,
                None,
            ))
        })();

        if finalization.is_err() && ownership == WorkspaceLeaseOwnership::Started {
            let _ = stop_exact_runtime(&admission.workspace_root, admission.backend_name, &runtime);
        }
        finalization
    })
}

pub fn workspace_lease_status(args: AgentLeaseAccessArgs) -> Result<WorkspaceLeaseResult> {
    access_workspace_lease(args, WorkspaceLeaseAccess::Status)
}

pub fn workspace_lease_release(args: AgentLeaseAccessArgs) -> Result<WorkspaceLeaseResult> {
    access_workspace_lease(args, WorkspaceLeaseAccess::Release)
}

pub fn validate_workspace_lease_for_command(
    lease_id: &AgentWorkspaceLeaseId,
    workspace_root: Option<&Path>,
    backend_name: Option<BackendName>,
) -> Result<()> {
    let workspace_root = workspace_root.ok_or_else(|| {
        CliError::new(
            "WORKSPACE_LEASE_ROOT_REQUIRED",
            "Leased semantic commands require an explicit --workspace-root.",
        )
    })?;
    let backend_name = backend_name.ok_or_else(|| {
        CliError::new(
            "WORKSPACE_LEASE_BACKEND_REQUIRED",
            "Leased semantic commands require an explicit --backend.",
        )
    })?;
    let args = AgentLeaseAccessArgs {
        lease_id: lease_id.clone(),
        workspace_root: workspace_root.to_path_buf(),
        backend_name: Some(backend_name),
    };
    let result = access_workspace_lease(args, WorkspaceLeaseAccess::Validate)?;
    if result.state != WorkspaceLeaseState::Ready {
        return Err(lease_state_error(result.state, result.failure_reason));
    }
    Ok(())
}

#[derive(Clone, Copy)]
enum WorkspaceLeaseAccess {
    Status,
    Release,
    Validate,
}

fn access_workspace_lease(
    args: AgentLeaseAccessArgs,
    access: WorkspaceLeaseAccess,
) -> Result<WorkspaceLeaseResult> {
    let requested_root = exact_lease_root(&args.workspace_root)?;
    let paths = WorkspaceLeasePaths::resolve()?;
    with_workspace_lease_lock(&paths, || {
        let secret = read_workspace_lease_secret(&paths.secret)?;
        let claims = verify_workspace_lease_token(&secret, args.lease_id.as_str())?;
        validate_token_request_identity(
            &claims,
            &requested_root,
            args.backend_name,
        )?;
        let installation = lease_installation_identity(
            &claims.workspace_root,
            claims.backend_name,
        )?;
        validate_token_environment(&claims, &installation)?;
        let record_path = paths.record(claims.record_id);
        let record = read_workspace_lease_record(&record_path)?;
        validate_workspace_lease_record_mac(&secret, &record)?;
        validate_lease_binding_identity(
            record.binding(),
            &claims,
            &requested_root,
            args.backend_name,
        )?;
        validate_lease_binding_environment(record.binding(), &installation)?;

        match record {
            WorkspaceLeaseRecord::Released {
                binding, receipt, ..
            } => {
                require_current_lease_owner(&binding.owner)?;
                Ok(workspace_lease_result(
                    args.lease_id.as_str().to_string(),
                    WorkspaceLeaseState::Released,
                    binding,
                    None,
                    Some(receipt),
                ))
            }
            WorkspaceLeaseRecord::Active { binding, .. }
                if !owner_identity_is_live(&binding.owner) =>
            {
                match access {
                    WorkspaceLeaseAccess::Release => Err(lease_state_error(
                        WorkspaceLeaseState::Abandoned,
                        Some(WorkspaceLeaseFailureReason::OwnerAbandoned),
                    )),
                    WorkspaceLeaseAccess::Status | WorkspaceLeaseAccess::Validate => {
                        Ok(workspace_lease_result(
                            args.lease_id.as_str().to_string(),
                            WorkspaceLeaseState::Abandoned,
                            binding,
                            Some(WorkspaceLeaseFailureReason::OwnerAbandoned),
                            None,
                        ))
                    }
                }
            }
            WorkspaceLeaseRecord::Active { binding, .. } => {
                require_current_lease_owner(&binding.owner)?;
                match access {
                WorkspaceLeaseAccess::Release => {
                    let receipt = release_active_binding(&binding)?;
                    write_workspace_lease_record(
                        &record_path,
                        &released_workspace_lease_record(
                            &secret,
                            binding.clone(),
                            receipt.clone(),
                        )?,
                    )?;
                    Ok(workspace_lease_result(
                        args.lease_id.as_str().to_string(),
                        WorkspaceLeaseState::Released,
                        binding,
                        None,
                        Some(receipt),
                    ))
                }
                WorkspaceLeaseAccess::Status | WorkspaceLeaseAccess::Validate => {
                    let (state, failure) = observe_active_binding(&binding)?;
                    Ok(workspace_lease_result(
                        args.lease_id.as_str().to_string(),
                        state,
                        binding,
                        failure,
                        None,
                    ))
                }
                }
            }
        }
    })
}

fn admitted_lease_workspace(
    workspace_root: PathBuf,
    backend_name: Option<BackendName>,
) -> Result<SemanticWorkspaceAdmission> {
    match semantic_workspace_route(Some(workspace_root), backend_name)? {
        SemanticWorkspaceRoute::Admitted(admission) => Ok(admission),
        SemanticWorkspaceRoute::Rejected(rejection) => {
            let mut error = CliError::new(rejection.code, rejection.message);
            error.details.insert(
                "semanticWorkspace".to_string(),
                serde_json::to_string(&rejection.evidence).unwrap_or_default(),
            );
            Err(error)
        }
    }
}

fn exact_lease_root(requested: &Path) -> Result<PathBuf> {
    if !requested.is_absolute() {
        return Err(CliError::new(
            "WORKSPACE_LEASE_ROOT_REQUIRED",
            "Workspace leases require an absolute --workspace-root.",
        ));
    }
    fs::canonicalize(requested).map_err(|error| {
        CliError::new(
            "WORKSPACE_LEASE_ROOT_INVALID",
            format!(
                "Workspace lease root {} could not be canonicalized: {error}",
                requested.display()
            ),
        )
    })
}

fn lease_installation_identity(
    workspace_root: &Path,
    backend_name: BackendName,
) -> Result<WorkspaceLeaseInstallationIdentity> {
    let doctor = self_mgmt::doctor(
        false,
        crate::cli::ReadyTarget::Agent,
        Some(workspace_root),
    )?;
    let environment = doctor.agent_environment.as_ref().ok_or_else(|| {
        CliError::new(
            "WORKSPACE_LEASE_ENVIRONMENT_NOT_READY",
            "Agent readiness did not produce effective environment evidence.",
        )
    })?;
    if !doctor.ok || !environment.ok {
        let mut error = CliError::new(
            "WORKSPACE_LEASE_ENVIRONMENT_NOT_READY",
            "The effective agent environment is not ready for lease acquisition or use.",
        );
        error
            .details
            .insert("issues".to_string(), doctor.issues.join(" | "));
        return Err(error);
    }
    if environment.backend.kind.as_deref() != Some(backend_name.canonical()) {
        return Err(CliError::new(
            "WORKSPACE_LEASE_BACKEND_MISMATCH",
            format!(
                "Effective agent backend {:?} does not match requested backend {}.",
                environment.backend.kind,
                backend_name.canonical()
            ),
        ));
    }
    let serialized = serde_json::to_vec(environment)?;
    let environment_sha256 = crate::manifest::sha256_bytes(&serialized);
    let (authority, generation) = match doctor.install_authority {
        self_mgmt::InstallAuthority::LocalDevelopment => (
            WorkspaceLeaseInstallAuthority::LocalDevelopment,
            doctor
                .local_development
                .as_ref()
                .map(|receipt| receipt.generation_id.as_str().to_string()),
        ),
        self_mgmt::InstallAuthority::MacosHomebrew => (
            WorkspaceLeaseInstallAuthority::MacosHomebrew,
            doctor
                .homebrew_install
                .as_ref()
                .map(|receipt| receipt.cli.version.clone()),
        ),
        self_mgmt::InstallAuthority::ManagedLocal => (
            WorkspaceLeaseInstallAuthority::ManagedLocal,
            doctor.install.as_ref().map(|install| install.install_id.clone()),
        ),
        self_mgmt::InstallAuthority::Missing => {
            return Err(CliError::new(
                "WORKSPACE_LEASE_AUTHORITY_MISSING",
                "Workspace leases require one effective install authority.",
            ));
        }
    };
    let generation = generation.filter(|value| !value.is_empty()).ok_or_else(|| {
        CliError::new(
            "WORKSPACE_LEASE_GENERATION_MISSING",
            "The effective install authority did not provide a generation identity.",
        )
    })?;
    Ok(WorkspaceLeaseInstallationIdentity {
        authority,
        generation,
        environment_sha256,
    })
}

fn ensure_lease_runtime(
    admission: &SemanticWorkspaceAdmission,
    wait_timeout_ms: u64,
) -> Result<WorkspaceEnsureResult> {
    if admission.backend_name == BackendName::Idea {
        let config = KastConfig::load(&admission.workspace_root)?;
        let path_resolution = config::path_resolution_report(
            &config,
            Some(&admission.workspace_root),
            config::PathResolutionMode::Cli,
        )?;
        let selected = wait_for_servable(
            &admission.workspace_root,
            Some(BackendName::Idea),
            false,
            wait_timeout_ms,
        )?;
        return Ok(WorkspaceEnsureResult {
            workspace_root: admission.workspace_root.display().to_string(),
            descriptor_directory: config.paths.descriptor_dir.display().to_string(),
            path_resolution,
            started: false,
            log_file: None,
            selected,
            note: Some("Borrowed the exact IDEA-hosted runtime; acquisition never launches IDEA.".to_string()),
            schema_version: SCHEMA_VERSION,
        });
    }
    workspace_ensure(lease_runtime_args(
        &admission.workspace_root,
        admission.backend_name,
        wait_timeout_ms,
    ))
}

fn lease_runtime_args(
    workspace_root: &Path,
    backend_name: BackendName,
    wait_timeout_ms: u64,
) -> RuntimeArgs {
    RuntimeArgs {
        workspace_root: Some(workspace_root.to_path_buf()),
        backend_name: Some(backend_name),
        idea_home: None,
        wait_timeout_ms,
        accept_indexing: Some(false),
        no_auto_start: Some(false),
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

fn runtime_identity(candidate: &RuntimeCandidateStatus) -> Result<WorkspaceLeaseRuntimeIdentity> {
    Ok(WorkspaceLeaseRuntimeIdentity {
        descriptor_path: candidate.descriptor_path.clone(),
        descriptor: candidate.descriptor.clone(),
        process: process_identity(candidate.descriptor.pid)?,
    })
}

fn caller_process_identity() -> Result<WorkspaceLeaseOwnerIdentity> {
    #[cfg(unix)]
    let direct_parent = u64::try_from(unsafe { libc::getppid() }).map_err(|_| {
        CliError::new(
            "WORKSPACE_LEASE_OWNER_INVALID",
            "The caller process id could not be represented.",
        )
    })?;
    #[cfg(not(unix))]
    let direct_parent = u64::from(std::process::id());
    let pid = parent_process(direct_parent)
        .filter(|(_, command)| is_transient_shell(command))
        .map_or(direct_parent, |(parent, _)| parent);
    let process = process_identity(pid)?;
    let session_sha256 = ["KAST_AGENT_SESSION_ID", "CODEX_THREAD_ID"]
        .into_iter()
        .find_map(|name| std::env::var(name).ok().filter(|value| !value.is_empty()))
        .map(|session| crate::manifest::sha256_bytes(session.as_bytes()));
    Ok(WorkspaceLeaseOwnerIdentity {
        process,
        session_sha256,
    })
}

fn parent_process(pid: u64) -> Option<(u64, String)> {
    let output = Command::new("ps")
        .env("LC_ALL", "C")
        .args(["-o", "ppid=,comm=", "-p", &pid.to_string()])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let line = String::from_utf8_lossy(&output.stdout);
    let mut fields = line.split_whitespace();
    let parent = fields.next()?.parse().ok()?;
    let command = fields.collect::<Vec<_>>().join(" ");
    (!command.is_empty()).then_some((parent, command))
}

fn is_transient_shell(command: &str) -> bool {
    Path::new(command)
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| matches!(name, "sh" | "bash" | "dash" | "fish" | "zsh"))
}

fn process_identity(pid: u64) -> Result<WorkspaceLeaseProcessIdentity> {
    let output = Command::new("ps")
        .env("LC_ALL", "C")
        .args(["-o", "lstart=", "-p", &pid.to_string()])
        .output()?;
    let started_at = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !output.status.success() || started_at.is_empty() {
        return Err(CliError::new(
            "WORKSPACE_LEASE_PROCESS_IDENTITY_UNAVAILABLE",
            format!("Could not prove process-start identity for PID {pid}."),
        ));
    }
    Ok(WorkspaceLeaseProcessIdentity { pid, started_at })
}

fn process_identity_is_live(identity: &WorkspaceLeaseProcessIdentity) -> bool {
    process_identity(identity.pid).is_ok_and(|current| current == *identity)
}

fn owner_identity_is_live(identity: &WorkspaceLeaseOwnerIdentity) -> bool {
    process_identity_is_live(&identity.process)
}

fn require_current_lease_owner(identity: &WorkspaceLeaseOwnerIdentity) -> Result<()> {
    if caller_process_identity()? == *identity {
        Ok(())
    } else {
        Err(CliError::new(
            "WORKSPACE_LEASE_FOREIGN_SESSION",
            "Workspace lease belongs to a different live agent session.",
        ))
    }
}

fn require_exact_ready_runtime(
    workspace_root: &Path,
    backend_name: BackendName,
    expected: &WorkspaceLeaseRuntimeIdentity,
) -> Result<()> {
    match exact_runtime_observation(workspace_root, backend_name, expected)? {
        ExactRuntimeObservation::Ready => Ok(()),
        ExactRuntimeObservation::Unavailable => Err(CliError::new(
            "WORKSPACE_LEASE_RUNTIME_UNAVAILABLE",
            "The exact runtime bound by the workspace lease is no longer available.",
        )),
        ExactRuntimeObservation::Replaced => Err(CliError::new(
            "WORKSPACE_LEASE_RUNTIME_REPLACED",
            "A different runtime now occupies the leased root and backend.",
        )),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ExactRuntimeObservation {
    Ready,
    Unavailable,
    Replaced,
}

fn exact_runtime_observation(
    workspace_root: &Path,
    backend_name: BackendName,
    expected: &WorkspaceLeaseRuntimeIdentity,
) -> Result<ExactRuntimeObservation> {
    let inspection = inspect_workspace(
        workspace_root,
        RuntimeBackendPreference::Fixed(backend_name),
        StaleDescriptorPolicy::Preserve,
    )?;
    for candidate in &inspection.candidates {
        if candidate.descriptor == expected.descriptor
            && candidate.descriptor_path == expected.descriptor_path
        {
            if !process_identity_is_live(&expected.process) {
                return Ok(ExactRuntimeObservation::Unavailable);
            }
            return Ok(if candidate.ready {
                ExactRuntimeObservation::Ready
            } else {
                ExactRuntimeObservation::Unavailable
            });
        }
    }
    if inspection
        .candidates
        .iter()
        .any(|candidate| candidate.descriptor.backend_name == backend_name.canonical())
    {
        Ok(ExactRuntimeObservation::Replaced)
    } else {
        Ok(ExactRuntimeObservation::Unavailable)
    }
}

fn observe_active_binding(
    binding: &WorkspaceLeaseBinding,
) -> Result<(WorkspaceLeaseState, Option<WorkspaceLeaseFailureReason>)> {
    if !owner_identity_is_live(&binding.owner) {
        return Ok((
            WorkspaceLeaseState::Abandoned,
            Some(WorkspaceLeaseFailureReason::OwnerAbandoned),
        ));
    }
    match exact_runtime_observation(
        &binding.workspace_root,
        binding.backend_name,
        &binding.runtime,
    )? {
        ExactRuntimeObservation::Ready => Ok((WorkspaceLeaseState::Ready, None)),
        ExactRuntimeObservation::Unavailable => Ok((
            WorkspaceLeaseState::Failed,
            Some(WorkspaceLeaseFailureReason::RuntimeUnavailable),
        )),
        ExactRuntimeObservation::Replaced => Ok((
            WorkspaceLeaseState::Failed,
            Some(WorkspaceLeaseFailureReason::RuntimeReplaced),
        )),
    }
}

fn release_active_binding(
    binding: &WorkspaceLeaseBinding,
) -> Result<WorkspaceLeaseReleaseReceipt> {
    let (runtime_stopped, reason) = match binding.ownership {
        WorkspaceLeaseOwnership::Borrowed => (
            false,
            WorkspaceLeaseReleaseReason::BorrowedRuntimePreserved,
        ),
        WorkspaceLeaseOwnership::Started => {
            if stop_exact_runtime(
                &binding.workspace_root,
                binding.backend_name,
                &binding.runtime,
            )? {
                (true, WorkspaceLeaseReleaseReason::OwnedRuntimeStopped)
            } else {
                (false, WorkspaceLeaseReleaseReason::ExactRuntimeUnavailable)
            }
        }
    };
    Ok(WorkspaceLeaseReleaseReceipt {
        released_at: crate::manifest::current_timestamp(),
        runtime_stopped,
        reason,
    })
}

fn stop_exact_runtime(
    workspace_root: &Path,
    backend_name: BackendName,
    expected: &WorkspaceLeaseRuntimeIdentity,
) -> Result<bool> {
    if backend_name == BackendName::Idea {
        return Ok(false);
    }
    let inspection = inspect_workspace(
        workspace_root,
        RuntimeBackendPreference::Fixed(backend_name),
        StaleDescriptorPolicy::Preserve,
    )?;
    let Some(candidate) = inspection.candidates.into_iter().find(|candidate| {
        candidate.descriptor == expected.descriptor
            && candidate.descriptor_path == expected.descriptor_path
            && process_identity_is_live(&expected.process)
    }) else {
        return Ok(false);
    };
    let mut warnings = Vec::new();
    let action = stop_candidate(
        &inspection.descriptor_directory,
        candidate,
        false,
        None,
        &mut warnings,
    )?;
    Ok(action.terminated || action.descriptor_deleted)
}

fn recover_or_reject_existing_lease(
    paths: &WorkspaceLeasePaths,
    secret: &[u8],
    admission: &SemanticWorkspaceAdmission,
    installation: &WorkspaceLeaseInstallationIdentity,
) -> Result<()> {
    fs::create_dir_all(&paths.records)?;
    let mut records = fs::read_dir(&paths.records)?.collect::<std::io::Result<Vec<_>>>()?;
    records.sort_by_key(std::fs::DirEntry::file_name);
    for entry in records {
        if entry.path().extension().and_then(|value| value.to_str()) != Some("json") {
            continue;
        }
        let record = read_workspace_lease_record(&entry.path())?;
        validate_workspace_lease_record_mac(secret, &record)?;
        let WorkspaceLeaseRecord::Active { binding, .. } = record else {
            continue;
        };
        if binding.workspace_root != admission.workspace_root
            || binding.backend_name != admission.backend_name
        {
            continue;
        }
        if binding.installation != *installation {
            return Err(stale_environment_error(
                "An active workspace lease belongs to a different effective generation.",
            ));
        }
        if owner_identity_is_live(&binding.owner) {
            let mut error = CliError::new(
                "WORKSPACE_LEASE_CONFLICT",
                format!(
                    "An active workspace lease already owns {} with backend {}.",
                    binding.workspace_root.display(),
                    binding.backend_name.canonical()
                ),
            );
            error
                .details
                .insert("ownerPid".to_string(), binding.owner.process.pid.to_string());
            return Err(error);
        }
        let runtime_stopped = if binding.ownership == WorkspaceLeaseOwnership::Started {
            stop_exact_runtime(
                &binding.workspace_root,
                binding.backend_name,
                &binding.runtime,
            )?
        } else {
            false
        };
        let receipt = WorkspaceLeaseReleaseReceipt {
            released_at: crate::manifest::current_timestamp(),
            runtime_stopped,
            reason: WorkspaceLeaseReleaseReason::RecoveredAbandonedOwner,
        };
        write_workspace_lease_record(
            &entry.path(),
            &released_workspace_lease_record(secret, binding, receipt)?,
        )?;
    }
    Ok(())
}

fn validate_lease_binding_identity(
    binding: &WorkspaceLeaseBinding,
    claims: &WorkspaceLeaseTokenClaims,
    workspace_root: &Path,
    backend_name: Option<BackendName>,
) -> Result<()> {
    if binding.schema_version != WORKSPACE_LEASE_SCHEMA_VERSION
        || binding.record_id != claims.record_id
        || binding.workspace_root != claims.workspace_root
        || binding.backend_name != claims.backend_name
    {
        return Err(CliError::new(
            "WORKSPACE_LEASE_RECORD_INVALID",
            "Workspace lease record identity is invalid.",
        ));
    }
    if workspace_lease_binding_digest(binding)? != claims.binding_sha256 {
        return Err(CliError::new(
            "WORKSPACE_LEASE_RECORD_TAMPERED",
            "Workspace lease record no longer matches its authenticated identity.",
        ));
    }
    if binding.workspace_root != workspace_root {
        return Err(CliError::new(
            "WORKSPACE_LEASE_ROOT_MISMATCH",
            format!(
                "Workspace lease binds {}, not {}.",
                binding.workspace_root.display(),
                workspace_root.display()
            ),
        ));
    }
    if backend_name.is_some_and(|backend| backend != binding.backend_name) {
        return Err(CliError::new(
            "WORKSPACE_LEASE_BACKEND_MISMATCH",
            format!(
                "Workspace lease binds backend {}, not {}.",
                binding.backend_name.canonical(),
                backend_name.expect("checked backend").canonical()
            ),
        ));
    }
    Ok(())
}

fn validate_token_request_identity(
    claims: &WorkspaceLeaseTokenClaims,
    workspace_root: &Path,
    backend_name: Option<BackendName>,
) -> Result<()> {
    if claims.workspace_root != workspace_root {
        return Err(CliError::new(
            "WORKSPACE_LEASE_ROOT_MISMATCH",
            format!(
                "Workspace lease binds {}, not {}.",
                claims.workspace_root.display(),
                workspace_root.display()
            ),
        ));
    }
    if let Some(backend_name) = backend_name
        && backend_name != claims.backend_name
    {
        return Err(CliError::new(
            "WORKSPACE_LEASE_BACKEND_MISMATCH",
            format!(
                "Workspace lease binds backend {}, not {}.",
                claims.backend_name.canonical(),
                backend_name.canonical()
            ),
        ));
    }
    Ok(())
}

fn validate_lease_binding_environment(
    binding: &WorkspaceLeaseBinding,
    installation: &WorkspaceLeaseInstallationIdentity,
) -> Result<()> {
    if binding.installation == *installation {
        Ok(())
    } else {
        Err(stale_environment_error(
            "Workspace lease no longer matches the effective agent environment.",
        ))
    }
}

fn validate_token_environment(
    claims: &WorkspaceLeaseTokenClaims,
    installation: &WorkspaceLeaseInstallationIdentity,
) -> Result<()> {
    if claims.authority != installation.authority {
        return Err(CliError::new(
            "WORKSPACE_LEASE_FOREIGN_AUTHORITY",
            format!(
                "Workspace lease authority {} is not the effective authority {}.",
                claims.authority.canonical(),
                installation.authority.canonical()
            ),
        ));
    }
    if claims.environment_sha256 != installation.environment_sha256 {
        return Err(stale_environment_error(
            "Workspace lease was issued for a different effective generation.",
        ));
    }
    if claims.generation != installation.generation {
        return Err(stale_environment_error(
            "Workspace lease was issued for a different effective generation.",
        ));
    }
    Ok(())
}

fn stale_environment_error(message: &str) -> CliError {
    CliError::new("WORKSPACE_LEASE_STALE_ENVIRONMENT", message)
}

fn lease_state_error(
    state: WorkspaceLeaseState,
    failure: Option<WorkspaceLeaseFailureReason>,
) -> CliError {
    match state {
        WorkspaceLeaseState::Released => CliError::new(
            "WORKSPACE_LEASE_RELEASED",
            "The workspace lease has already reached terminal RELEASED state.",
        ),
        WorkspaceLeaseState::Abandoned => CliError::new(
            "WORKSPACE_LEASE_ABANDONED",
            "The workspace lease owner is no longer the same live process.",
        ),
        WorkspaceLeaseState::Failed => match failure {
            Some(WorkspaceLeaseFailureReason::RuntimeReplaced) => CliError::new(
                "WORKSPACE_LEASE_RUNTIME_REPLACED",
                "A different runtime now occupies the leased root and backend.",
            ),
            _ => CliError::new(
                "WORKSPACE_LEASE_RUNTIME_UNAVAILABLE",
                "The exact runtime bound by the workspace lease is unavailable.",
            ),
        },
        WorkspaceLeaseState::Ready => CliError::new(
            "WORKSPACE_LEASE_STATE_INVALID",
            "Workspace lease validation produced an invalid state.",
        ),
    }
}

fn workspace_lease_result(
    lease_id: String,
    state: WorkspaceLeaseState,
    binding: WorkspaceLeaseBinding,
    failure_reason: Option<WorkspaceLeaseFailureReason>,
    release_receipt: Option<WorkspaceLeaseReleaseReceipt>,
) -> WorkspaceLeaseResult {
    WorkspaceLeaseResult {
        lease_id,
        state,
        workspace_root: binding.workspace_root.display().to_string(),
        workspace_kind: binding.workspace_kind,
        backend_name: binding.backend_name,
        runtime: binding.runtime,
        installation: binding.installation,
        ownership: binding.ownership,
        owner: binding.owner,
        acquired_at: binding.acquired_at,
        failure_reason,
        release_receipt,
        schema_version: WORKSPACE_LEASE_SCHEMA_VERSION,
    }
}

fn workspace_lease_binding_digest(binding: &WorkspaceLeaseBinding) -> Result<String> {
    Ok(crate::manifest::sha256_bytes(&serde_json::to_vec(binding)?))
}

fn active_workspace_lease_record(
    secret: &[u8],
    binding: WorkspaceLeaseBinding,
) -> Result<WorkspaceLeaseRecord> {
    let payload = serde_json::to_vec(&("ACTIVE", &binding))?;
    Ok(WorkspaceLeaseRecord::Active {
        binding,
        record_mac: hex::encode(workspace_lease_hmac_sha256(secret, &payload)),
    })
}

fn released_workspace_lease_record(
    secret: &[u8],
    binding: WorkspaceLeaseBinding,
    receipt: WorkspaceLeaseReleaseReceipt,
) -> Result<WorkspaceLeaseRecord> {
    let payload = serde_json::to_vec(&("RELEASED", &binding, &receipt))?;
    Ok(WorkspaceLeaseRecord::Released {
        binding,
        receipt,
        record_mac: hex::encode(workspace_lease_hmac_sha256(secret, &payload)),
    })
}

fn validate_workspace_lease_record_mac(
    secret: &[u8],
    record: &WorkspaceLeaseRecord,
) -> Result<()> {
    let (payload, encoded_mac) = match record {
        WorkspaceLeaseRecord::Active {
            binding,
            record_mac,
        } => (serde_json::to_vec(&("ACTIVE", binding))?, record_mac),
        WorkspaceLeaseRecord::Released {
            binding,
            receipt,
            record_mac,
        } => (
            serde_json::to_vec(&("RELEASED", binding, receipt))?,
            record_mac,
        ),
    };
    let actual = hex::decode(encoded_mac).map_err(|_| record_tampered_error())?;
    let expected = workspace_lease_hmac_sha256(secret, &payload);
    if constant_time_equal(&actual, &expected) {
        Ok(())
    } else {
        Err(record_tampered_error())
    }
}

fn record_tampered_error() -> CliError {
    CliError::new(
        "WORKSPACE_LEASE_RECORD_TAMPERED",
        "Workspace lease record failed authentication.",
    )
}

fn with_workspace_lease_lock<T>(
    paths: &WorkspaceLeasePaths,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    use std::fs::OpenOptions;
    if let Some(parent) = paths.lock.parent() {
        fs::create_dir_all(parent)?;
    }
    let file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&paths.lock)?;
    workspace_lease_lock(&file)?;
    let result = action();
    workspace_lease_unlock(&file)?;
    result
}

#[cfg(unix)]
fn workspace_lease_lock(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn workspace_lease_lock(_file: &fs::File) -> Result<()> {
    Ok(())
}

#[cfg(unix)]
fn workspace_lease_unlock(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_UN) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn workspace_lease_unlock(_file: &fs::File) -> Result<()> {
    Ok(())
}

fn read_or_create_workspace_lease_secret(path: &Path) -> Result<Vec<u8>> {
    if path.is_file() {
        return read_workspace_lease_secret(path);
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut bytes = Vec::with_capacity(32);
    bytes.extend_from_slice(uuid::Uuid::new_v4().as_bytes());
    bytes.extend_from_slice(uuid::Uuid::new_v4().as_bytes());
    let encoded = hex::encode(&bytes);
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    match options.open(path) {
        Ok(mut file) => {
            file.write_all(encoded.as_bytes())?;
            file.sync_all()?;
            Ok(bytes)
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            read_workspace_lease_secret(path)
        }
        Err(error) => Err(error.into()),
    }
}

fn read_workspace_lease_secret(path: &Path) -> Result<Vec<u8>> {
    let encoded = fs::read_to_string(path).map_err(|error| {
        CliError::new(
            "WORKSPACE_LEASE_SECRET_MISSING",
            format!("Workspace lease signing key is unavailable: {error}"),
        )
    })?;
    let bytes = hex::decode(encoded.trim()).map_err(|_| {
        CliError::new(
            "WORKSPACE_LEASE_SECRET_INVALID",
            "Workspace lease signing key is not valid hexadecimal.",
        )
    })?;
    if bytes.len() != 32 {
        return Err(CliError::new(
            "WORKSPACE_LEASE_SECRET_INVALID",
            "Workspace lease signing key must contain exactly 32 bytes.",
        ));
    }
    Ok(bytes)
}

fn sign_workspace_lease_token(
    secret: &[u8],
    claims: &WorkspaceLeaseTokenClaims,
) -> Result<String> {
    let payload = serde_json::to_vec(claims)?;
    let signature = workspace_lease_hmac_sha256(secret, &payload);
    Ok(format!(
        "{WORKSPACE_LEASE_TOKEN_VERSION}.{}.{}",
        hex::encode(payload),
        hex::encode(signature)
    ))
}

fn verify_workspace_lease_token(
    secret: &[u8],
    token: &str,
) -> Result<WorkspaceLeaseTokenClaims> {
    let mut parts = token.split('.');
    let version = parts.next();
    let payload = parts.next();
    let signature = parts.next();
    if version != Some(WORKSPACE_LEASE_TOKEN_VERSION)
        || payload.is_none()
        || signature.is_none()
        || parts.next().is_some()
    {
        return Err(tampered_lease_error());
    }
    let payload = hex::decode(payload.expect("checked token payload"))
        .map_err(|_| tampered_lease_error())?;
    let signature = hex::decode(signature.expect("checked token signature"))
        .map_err(|_| tampered_lease_error())?;
    let expected = workspace_lease_hmac_sha256(secret, &payload);
    if !constant_time_equal(&signature, &expected) {
        return Err(tampered_lease_error());
    }
    serde_json::from_slice(&payload).map_err(|_| tampered_lease_error())
}

fn tampered_lease_error() -> CliError {
    CliError::new(
        "WORKSPACE_LEASE_TAMPERED",
        "Workspace lease identity is malformed or failed authentication.",
    )
}

fn workspace_lease_hmac_sha256(secret: &[u8], payload: &[u8]) -> [u8; 32] {
    use sha2::{Digest, Sha256};
    const BLOCK: usize = 64;
    let mut key = [0_u8; BLOCK];
    if secret.len() > BLOCK {
        key[..32].copy_from_slice(&Sha256::digest(secret));
    } else {
        key[..secret.len()].copy_from_slice(secret);
    }
    let mut inner_pad = [0x36_u8; BLOCK];
    let mut outer_pad = [0x5c_u8; BLOCK];
    for index in 0..BLOCK {
        inner_pad[index] ^= key[index];
        outer_pad[index] ^= key[index];
    }
    let mut inner = Sha256::new();
    inner.update(inner_pad);
    inner.update(payload);
    let inner = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(outer_pad);
    outer.update(inner);
    outer.finalize().into()
}

fn constant_time_equal(actual: &[u8], expected: &[u8]) -> bool {
    if actual.len() != expected.len() {
        return false;
    }
    actual
        .iter()
        .zip(expected)
        .fold(0_u8, |difference, (left, right)| difference | (left ^ right))
        == 0
}

fn write_workspace_lease_record(path: &Path, record: &WorkspaceLeaseRecord) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension(format!("json.tmp-{}", std::process::id()));
    let result = (|| {
        use std::io::Write;
        let mut file = fs::File::create(&temporary)?;
        serde_json::to_writer_pretty(&mut file, record)?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn read_workspace_lease_record(path: &Path) -> Result<WorkspaceLeaseRecord> {
    let bytes = fs::read(path).map_err(|error| {
        CliError::new(
            "WORKSPACE_LEASE_UNKNOWN",
            format!("Workspace lease record is unavailable: {error}"),
        )
    })?;
    serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "WORKSPACE_LEASE_RECORD_INVALID",
            format!("Workspace lease record is invalid: {error}"),
        )
    })
}

#[cfg(test)]
mod workspace_lease_tests {
    use super::*;

    #[test]
    fn hmac_matches_rfc_4231_case_one() {
        let key = [0x0b_u8; 20];
        let actual = workspace_lease_hmac_sha256(&key, b"Hi There");
        assert_eq!(
            hex::encode(actual),
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        );
    }

    #[test]
    fn signed_token_rejects_tampering() {
        let claims = WorkspaceLeaseTokenClaims {
            authority: WorkspaceLeaseInstallAuthority::ManagedLocal,
            generation: "generation-1".to_string(),
            environment_sha256: "a".repeat(64),
            workspace_root: PathBuf::from("/workspace"),
            backend_name: BackendName::Headless,
            binding_sha256: "b".repeat(64),
            record_id: uuid::Uuid::new_v4(),
        };
        let secret = [7_u8; 32];
        let token = sign_workspace_lease_token(&secret, &claims).expect("token");
        assert_eq!(
            verify_workspace_lease_token(&secret, &token).expect("verified token"),
            claims
        );
        let mut tampered = token.into_bytes();
        let last = tampered.last_mut().expect("last token byte");
        *last = if *last == b'0' { b'1' } else { b'0' };
        let error = verify_workspace_lease_token(
            &secret,
            std::str::from_utf8(&tampered).expect("UTF-8 token"),
        )
        .expect_err("tamper must fail");
        assert_eq!(error.code, "WORKSPACE_LEASE_TAMPERED");
    }

    #[test]
    fn process_identity_rejects_pid_reuse_shape() {
        let current = process_identity(u64::from(std::process::id())).expect("current process");
        let replaced = WorkspaceLeaseProcessIdentity {
            pid: current.pid,
            started_at: format!("{}-different", current.started_at),
        };
        assert!(process_identity_is_live(&current));
        assert!(!process_identity_is_live(&replaced));
    }

    #[test]
    fn token_environment_distinguishes_foreign_authority_from_stale_generation() {
        let claims = WorkspaceLeaseTokenClaims {
            authority: WorkspaceLeaseInstallAuthority::LocalDevelopment,
            generation: "generation-1".to_string(),
            environment_sha256: "a".repeat(64),
            workspace_root: PathBuf::from("/workspace"),
            backend_name: BackendName::Headless,
            binding_sha256: "b".repeat(64),
            record_id: uuid::Uuid::new_v4(),
        };
        let foreign = WorkspaceLeaseInstallationIdentity {
            authority: WorkspaceLeaseInstallAuthority::ManagedLocal,
            generation: claims.generation.clone(),
            environment_sha256: claims.environment_sha256.clone(),
        };
        assert_eq!(
            validate_token_environment(&claims, &foreign)
                .expect_err("foreign authority")
                .code,
            "WORKSPACE_LEASE_FOREIGN_AUTHORITY"
        );

        let stale = WorkspaceLeaseInstallationIdentity {
            authority: claims.authority,
            generation: "generation-2".to_string(),
            environment_sha256: claims.environment_sha256.clone(),
        };
        assert_eq!(
            validate_token_environment(&claims, &stale)
                .expect_err("stale generation")
                .code,
            "WORKSPACE_LEASE_STALE_ENVIRONMENT"
        );
    }
}
