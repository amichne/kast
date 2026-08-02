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
    let runtime_libs_dir = Some(
        request
            .config
            .backends
            .headless
            .runtime_libs_dir
            .clone()
            .filter(|path| path.is_dir())
            .ok_or_else(|| headless_backend_unavailable_error(&request.workspace_root))?,
    );
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
