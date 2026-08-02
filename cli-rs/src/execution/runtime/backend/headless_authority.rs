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
            start_headless_runtime(&request).map_err(|error| {
                runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
            })?
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

fn parse_admitted_capabilities(
    request: &SemanticRuntimeRequest,
    candidate: &RuntimeCandidateStatus,
) -> std::result::Result<AdmittedHeadlessCapabilities, SemanticRuntimeRejection> {
    let capabilities = candidate.capabilities.as_ref().ok_or_else(|| {
        runtime_identity_rejection(&request.workspace_root, request.workspace_kind)
    })?;
    let backend_name = capabilities.get("backendName").and_then(Value::as_str);
    let backend_version = capabilities.get("backendVersion").and_then(Value::as_str);
    let workspace_root = capabilities
        .get("workspaceRoot")
        .and_then(Value::as_str)
        .ok_or_else(|| {
            runtime_identity_rejection(&request.workspace_root, request.workspace_kind)
        })?;
    let schema = capabilities.get("schemaVersion").and_then(Value::as_u64);
    let canonical_capability_root = canonical_existing_root(workspace_root).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    if backend_name != Some(BackendName::Headless.canonical())
        || backend_version != Some(candidate.descriptor.backend_version.as_str())
        || canonical_capability_root != request.workspace_root
        || schema != Some(u64::from(SCHEMA_VERSION))
    {
        return Err(runtime_identity_rejection(
            &request.workspace_root,
            request.workspace_kind,
        ));
    }
    let mutation_capabilities = capabilities
        .get("mutationCapabilities")
        .cloned()
        .map(serde_json::from_value)
        .transpose()
        .map_err(|error| {
            runtime_cli_rejection(
                &request.workspace_root,
                request.workspace_kind,
                CliError::new(
                    "RUNTIME_CAPABILITY_IDENTITY_INVALID",
                    format!("Headless mutation capabilities are invalid: {error}"),
                ),
            )
        })?
        .unwrap_or_default();
    Ok(AdmittedHeadlessCapabilities {
        mutation_capabilities,
    })
}

fn validate_descriptor_owner(descriptor: &ServerInstanceDescriptor) -> Result<()> {
    let runtime_instance_id = descriptor
        .runtime_instance_id
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(runtime_identity_mismatch)?;
    let advertised_process_start_epoch_millis = descriptor
        .process_start_epoch_millis
        .filter(|value| *value > 0)
        .ok_or_else(runtime_identity_mismatch)?;
    let advertised_owner_uid = descriptor.owner_uid.ok_or_else(runtime_identity_mismatch)?;
    let advertised_socket_identity = descriptor
        .socket_file_identity
        .as_ref()
        .filter(|identity| identity.inode > 0)
        .ok_or_else(runtime_identity_mismatch)?;
    let observed_process_start_epoch_seconds = process_start_epoch_seconds(descriptor.pid)?;
    if advertised_process_start_epoch_millis / 1_000 != observed_process_start_epoch_seconds {
        return Err(runtime_identity_mismatch());
    }
    #[cfg(unix)]
    if advertised_owner_uid != u64::from(unsafe { libc::geteuid() }) {
        return Err(CliError::new(
            "RUNTIME_IDENTITY_MISMATCH",
            "Headless runtime descriptor belongs to a different operating-system user.",
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;

        let metadata = fs::metadata(&descriptor.socket_path)?;
        if metadata.dev() != advertised_socket_identity.device
            || metadata.ino() != advertised_socket_identity.inode
        {
            return Err(CliError::new(
                "RUNTIME_IDENTITY_MISMATCH",
                "Headless runtime socket identity does not match its descriptor.",
            ));
        }
    }
    let _ = (
        runtime_instance_id,
        advertised_owner_uid,
        advertised_socket_identity,
    );
    Ok(())
}

fn runtime_identity_mismatch() -> CliError {
    CliError::new(
        "RUNTIME_IDENTITY_MISMATCH",
        "Headless runtime descriptor ownership identity is incomplete or does not match the live endpoint.",
    )
}

fn process_start_epoch_seconds(pid: u64) -> Result<u64> {
    if pid == 0 || pid > i32::MAX as u64 {
        return Err(runtime_identity_mismatch());
    }
    let output = Command::new("ps")
        .env("LC_ALL", "C")
        .args(["-o", "lstart=", "-p", &pid.to_string()])
        .output()?;
    let started_at = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !output.status.success() || started_at.is_empty() {
        return Err(runtime_identity_mismatch());
    }
    #[cfg(unix)]
    {
        let started_at =
            std::ffi::CString::new(started_at).map_err(|_| runtime_identity_mismatch())?;
        let format = c"%a %b %e %T %Y";
        let mut parsed = unsafe { std::mem::zeroed::<libc::tm>() };
        parsed.tm_isdst = -1;
        if unsafe { libc::strptime(started_at.as_ptr(), format.as_ptr(), &mut parsed) }.is_null() {
            return Err(runtime_identity_mismatch());
        }
        let seconds = unsafe { libc::mktime(&mut parsed) };
        u64::try_from(seconds).map_err(|_| runtime_identity_mismatch())
    }
    #[cfg(not(unix))]
    {
        let _ = started_at;
        Err(runtime_identity_mismatch())
    }
}

fn current_socket_file_identity(path: &str) -> Result<Option<RuntimeSocketFileIdentity>> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;

        let metadata = fs::metadata(path)?;
        Ok(Some(RuntimeSocketFileIdentity {
            device: metadata.dev(),
            inode: metadata.ino(),
        }))
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        Ok(None)
    }
}

fn validate_admitted_runtime_current(admission: &AdmittedHeadlessRuntime) -> Result<()> {
    let expected = &admission.candidate.descriptor;
    let registered = read_descriptors(&admission.config.paths.descriptor_dir)?;
    if !registered.iter().any(|descriptor| descriptor == expected)
        || !process_identity_matches(
            &admission.process_identity,
            process_identity(expected.pid).ok().as_ref(),
        )
        || current_socket_file_identity(&expected.socket_path)?
            != admission.observed_socket_file_identity
    {
        return Err(CliError::new(
            "RUNTIME_IDENTITY_REPLACED",
            "The admitted headless runtime descriptor or process identity changed.",
        ));
    }
    validate_descriptor_owner(expected)?;
    Ok(())
}

fn start_headless_runtime(
    request: &SemanticRuntimeRequest,
) -> Result<(RuntimeCandidateStatus, bool)> {
    let _launch_lock = WorkspaceLaunchLock::acquire(&request.config, &request.workspace_root)?;
    if let Ok(candidate) = admitted_candidate(request) {
        return Ok((candidate, false));
    }
    #[cfg(target_os = "macos")]
    let runtime_libs_dir = None;
    #[cfg(not(target_os = "macos"))]
    let runtime_libs_dir = request
        .config
        .backends
        .headless
        .runtime_libs_dir
        .clone()
        .filter(|path| path.is_dir())
        .ok_or_else(|| headless_backend_unavailable_error(&request.workspace_root))?;
    let log_file = daemon_log_file(
        &request.config,
        &request.workspace_root,
        BackendName::Headless,
    );
    let daemon_args = DaemonStartArgs {
        workspace_root: Some(request.workspace_root.clone()),
        backend_name: Some(BackendName::Headless),
        runtime_libs_dir,
        ..DaemonStartArgs::from(request.runtime_args.clone())
    };
    let mut child = daemon::spawn_background(daemon_args, &log_file)?;
    thread::spawn(move || {
        let _ = child.wait();
    });
    let deadline = Instant::now() + Duration::from_millis(request.wait_timeout_ms);
    while Instant::now() < deadline {
        if let Ok(candidate) = admitted_candidate(request) {
            return Ok((candidate, true));
        }
        thread::sleep(Duration::from_millis(250));
    }
    Err(CliError::new(
        "RUNTIME_TIMEOUT",
        format!(
            "Timed out waiting for the headless runtime for {}.",
            request.workspace_root.display()
        ),
    ))
}

#[cfg(not(target_os = "macos"))]
fn headless_backend_unavailable_error(workspace_root: &Path) -> CliError {
    let mut error = CliError::new(
        "NO_BACKEND_AVAILABLE",
        format!(
            "No headless backend is installed or running for {}. Install the headless distribution, then retry.",
            workspace_root.display()
        ),
    );
    error.details.insert(
        "supportedDistribution".to_string(),
        "linux-headless-tarball".to_string(),
    );
    error
}

fn canonical_existing_root(value: &str) -> Result<PathBuf> {
    fs::canonicalize(value).map_err(|error| {
        CliError::new(
            "RUNTIME_IDENTITY_MISMATCH",
            format!("Runtime workspace root {value} could not be canonicalized: {error}"),
        )
    })
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
        evidence: Box::new(unavailable_evidence(workspace_root, workspace_kind)),
    }
}

fn headless_conflict_rejection(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
    candidates: &[RuntimeCandidateStatus],
) -> SemanticRuntimeRejection {
    let backend_candidates = candidates
        .iter()
        .map(|candidate| SemanticBackendCandidateEvidence {
            backend_name: candidate.descriptor.backend_name.clone(),
            backend_version: candidate.descriptor.backend_version.clone(),
            workspace_root: workspace_root.display().to_string(),
            ready: candidate.ready,
            evidence_quality: SemanticEvidenceQuality::CompilerBacked,
        })
        .collect();
    let mut evidence = unavailable_evidence(workspace_root, workspace_kind);
    evidence.limitations = vec![SemanticWorkspaceLimitation::BackendSelectionAmbiguous];
    evidence.backend_candidates = backend_candidates;
    SemanticRuntimeRejection {
        code: "HEADLESS_RUNTIME_CONFLICT",
        message: format!(
            "More than one healthy headless runtime owns the exact workspace root {}. Stop the conflicting runtime before retrying.",
            workspace_root.display()
        ),
        evidence: Box::new(evidence),
    }
}

fn unavailable_rejection(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
    accept_indexing: bool,
) -> SemanticRuntimeRejection {
    SemanticRuntimeRejection {
        code: if accept_indexing {
            "NO_BACKEND_AVAILABLE"
        } else {
            "RUNTIME_NOT_READY"
        },
        message: format!(
            "No {} headless semantic runtime is available for {}.",
            if accept_indexing { "servable" } else { "READY" },
            workspace_root.display()
        ),
        evidence: Box::new(unavailable_evidence(workspace_root, workspace_kind)),
    }
}

fn runtime_identity_rejection(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
) -> SemanticRuntimeRejection {
    SemanticRuntimeRejection {
        code: "RUNTIME_IDENTITY_MISMATCH",
        message: format!(
            "Headless runtime identity does not match the exact workspace root {}.",
            workspace_root.display()
        ),
        evidence: Box::new(unavailable_evidence(workspace_root, workspace_kind)),
    }
}

fn runtime_cli_rejection(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
    error: CliError,
) -> SemanticRuntimeRejection {
    SemanticRuntimeRejection {
        code: error.code,
        message: error.message,
        evidence: Box::new(unavailable_evidence(workspace_root, workspace_kind)),
    }
}

fn unavailable_evidence(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
) -> SemanticWorkspaceEvidence {
    SemanticWorkspaceEvidence {
        backend_name: Some(BackendName::Headless.canonical().to_string()),
        workspace_root: workspace_root.display().to_string(),
        workspace_kind,
        source_module_names: vec![],
        limitations: vec![SemanticWorkspaceLimitation::SourceModulesUnavailable],
        evidence_quality: SemanticEvidenceQuality::Unavailable,
        backend_candidates: vec![],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn current_process_descriptor(socket_path: &Path) -> ServerInstanceDescriptor {
        ServerInstanceDescriptor {
            workspace_root: "/workspace".to_string(),
            backend_name: BackendName::Headless.canonical().to_string(),
            backend_version: "test".to_string(),
            runtime_instance_id: Some("runtime-instance".to_string()),
            process_start_epoch_millis: Some(1),
            owner_uid: Some(u64::from(unsafe { libc::geteuid() })),
            socket_file_identity: current_socket_file_identity(
                socket_path.to_str().expect("UTF-8 socket path"),
            )
            .expect("socket identity"),
            transport: "uds".to_string(),
            socket_path: socket_path.display().to_string(),
            pid: u64::from(std::process::id()),
            schema_version: SCHEMA_VERSION,
        }
    }

    #[test]
    fn missing_descriptor_ownership_identity_is_rejected() {
        let descriptor = ServerInstanceDescriptor {
            workspace_root: "/workspace".to_string(),
            backend_name: BackendName::Headless.canonical().to_string(),
            backend_version: "test".to_string(),
            runtime_instance_id: None,
            process_start_epoch_millis: None,
            owner_uid: None,
            socket_file_identity: None,
            transport: "uds".to_string(),
            socket_path: "/missing.sock".to_string(),
            pid: u64::from(std::process::id()),
            schema_version: SCHEMA_VERSION,
        };

        let error = validate_descriptor_owner(&descriptor).expect_err("legacy identity rejected");

        assert_eq!(error.code, "RUNTIME_IDENTITY_MISMATCH");
    }

    #[cfg(unix)]
    #[test]
    fn mismatched_descriptor_process_start_is_rejected_as_pid_reuse() {
        use std::os::unix::net::UnixListener;

        let temp = tempfile::tempdir().expect("socket directory");
        let socket_path = temp.path().join("runtime.sock");
        let _listener = UnixListener::bind(&socket_path).expect("runtime socket");
        let descriptor = current_process_descriptor(&socket_path);

        let error = validate_descriptor_owner(&descriptor).expect_err("PID reuse rejected");

        assert_eq!(error.code, "RUNTIME_IDENTITY_MISMATCH");
    }

    #[test]
    fn migration_planner_returns_typed_patch_for_retired_default() {
        let plan = plan_legacy_backend_migration(
            "[runtime]\ndefaultBackend = \"idea\"\nstrictPluginMatching = true\n",
        )
        .expect("migration plan");

        let LegacyBackendMigrationPlan::Replace(patch) = plan else {
            panic!("expected migration patch");
        };
        assert_eq!(
            patch.migrated_contents(),
            "[runtime]\ndefaultBackend = \"headless\"\nstrictPluginMatching = true\n"
        );
    }

    #[test]
    fn migration_planner_preserves_automatic_default() {
        assert_eq!(
            plan_legacy_backend_migration("[runtime]\ndefaultBackend = \"auto\"\n")
                .expect("migration plan"),
            LegacyBackendMigrationPlan::NoChange
        );
    }

    #[test]
    fn headless_authority_accepts_every_server_mutation_capability() {
        let capabilities: Vec<SemanticMutationCapability> = serde_json::from_value(
            serde_json::json!([
                "RENAME",
                "APPLY_EDITS",
                "FILE_OPERATIONS",
                "OPTIMIZE_IMPORTS",
                "REFRESH_WORKSPACE"
            ]),
        )
        .expect("complete server mutation capability domain");

        assert_eq!(
            capabilities,
            vec![
                SemanticMutationCapability::Rename,
                SemanticMutationCapability::ApplyEdits,
                SemanticMutationCapability::FileOperations,
                SemanticMutationCapability::OptimizeImports,
                SemanticMutationCapability::RefreshWorkspace,
            ]
        );
    }
}
