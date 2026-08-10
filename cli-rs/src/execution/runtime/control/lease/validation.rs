
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
        if binding.workspace_root != admission.workspace_root()
            || binding.backend_name != admission.backend()
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
            error.details.insert(
                "ownerPid".to_string(),
                binding.owner.process.pid.to_string(),
            );
            return Err(error);
        }
        let receipt = WorkspaceLeaseReleaseReceipt::RecoveredAbandonedOwner {
            released_at: crate::manifest::current_timestamp(),
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
    Ok(())
}

fn validate_token_request_identity(
    claims: &WorkspaceLeaseTokenClaims,
    workspace_root: &Path,
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

fn validate_workspace_lease_record_mac(secret: &[u8], record: &WorkspaceLeaseRecord) -> Result<()> {
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
