fn parse_admitted_capabilities(
    request: &SemanticRuntimeRequest,
    candidate: &RuntimeCandidateStatus,
) -> std::result::Result<AdmittedIndexerCapabilities, SemanticRuntimeRejection> {
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
    if backend_name != Some(BackendName::Indexer.canonical())
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
                    format!("Kast indexer mutation capabilities are invalid: {error}"),
                ),
            )
        })?
        .unwrap_or_default();
    Ok(AdmittedIndexerCapabilities {
        mutation_capabilities,
    })
}

pub(super) fn validate_descriptor_owner(descriptor: &ServerInstanceDescriptor) -> Result<()> {
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
            "Kast indexer descriptor belongs to a different operating-system user.",
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
                "Kast indexer socket identity does not match its descriptor.",
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

pub(super) fn runtime_identity_mismatch() -> CliError {
    CliError::new(
        "RUNTIME_IDENTITY_MISMATCH",
        "Kast runtime descriptor ownership identity is incomplete or does not match the live endpoint.",
    )
}

pub(super) fn descriptor_process_identity_is_live(
    descriptor: &ServerInstanceDescriptor,
) -> bool {
    descriptor
        .process_start_epoch_millis
        .filter(|value| *value > 0)
        .is_some_and(|expected| {
            process_start_epoch_seconds(descriptor.pid)
                .is_ok_and(|observed| expected / 1_000 == observed)
        })
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

fn validate_admitted_runtime_current(admission: &AdmittedIndexerRuntime) -> Result<()> {
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
            "The admitted indexer descriptor or process identity changed.",
        ));
    }
    validate_descriptor_owner(expected)?;
    Ok(())
}

#[cfg(any(not(target_os = "macos"), test))]
fn indexer_distribution_unavailable_rejection(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
) -> SemanticRuntimeRejection {
    SemanticRuntimeRejection {
        code: "NO_INDEXER_AVAILABLE",
        message: format!(
            "No indexer is installed or running for {}. Install Kast, then retry.",
            workspace_root.display()
        ),
        supported_distribution: Some(SupportedIndexerDistribution::LinuxIndexerTarball),
        evidence: Box::new(unavailable_evidence(workspace_root, workspace_kind)),
    }
}

fn canonical_existing_root(value: &str) -> Result<PathBuf> {
    fs::canonicalize(value).map_err(|error| {
        CliError::new(
            "RUNTIME_IDENTITY_MISMATCH",
            format!("Runtime workspace root {value} could not be canonicalized: {error}"),
        )
    })
}

fn indexer_conflict_rejection(
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
        code: "INDEXER_CONFLICT",
        message: format!(
            "More than one healthy indexer owns the exact workspace root {}. Stop the conflicting runtime before retrying.",
            workspace_root.display()
        ),
        supported_distribution: None,
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
            "NO_INDEXER_AVAILABLE"
        } else {
            "RUNTIME_NOT_READY"
        },
        message: format!(
            "No {} Kast indexer is available for {}.",
            if accept_indexing { "servable" } else { "READY" },
            workspace_root.display()
        ),
        supported_distribution: None,
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
            "Kast indexer identity does not match the exact workspace root {}.",
            workspace_root.display()
        ),
        supported_distribution: None,
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
        supported_distribution: None,
        evidence: Box::new(unavailable_evidence(workspace_root, workspace_kind)),
    }
}

fn unavailable_evidence(
    workspace_root: &Path,
    workspace_kind: SemanticWorkspaceKind,
) -> SemanticWorkspaceEvidence {
    SemanticWorkspaceEvidence {
        backend_name: Some(BackendName::Indexer.canonical().to_string()),
        workspace_root: workspace_root.display().to_string(),
        workspace_kind,
        source_module_names: vec![],
        limitations: vec![SemanticWorkspaceLimitation::SourceModulesUnavailable],
        evidence_quality: SemanticEvidenceQuality::Unavailable,
        backend_candidates: vec![],
    }
}
