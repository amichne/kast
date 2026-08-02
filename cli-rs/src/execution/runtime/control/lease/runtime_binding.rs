
fn ensure_lease_runtime(
    admission: &SemanticWorkspaceAdmission,
    wait_timeout_ms: u64,
) -> Result<WorkspaceEnsureResult> {
    if !admission.candidate().ready {
        let deadline = Instant::now() + Duration::from_millis(wait_timeout_ms);
        while Instant::now() < deadline {
            admission.validate_current()?;
            let inspection =
                inspect_headless_workspace(admission.workspace_root(), StaleDescriptorPolicy::Preserve)?;
            if let Some(candidate) = inspection.candidates.into_iter().find(|candidate| {
                candidate.descriptor_path == admission.candidate().descriptor_path
                    && candidate.descriptor == admission.candidate().descriptor
            }) {
                if candidate.ready {
                    return lease_runtime_result(admission, candidate);
                }
            } else {
                return Err(CliError::new(
                    "WORKSPACE_LEASE_RUNTIME_REPLACED",
                    "The exact headless runtime changed while lease acquisition waited for READY.",
                ));
            }
            thread::sleep(Duration::from_millis(25));
        }
        return Err(CliError::new(
            "RUNTIME_TIMEOUT",
            format!(
                "Timed out waiting for the exact headless runtime for {} to reach READY.",
                admission.workspace_root().display()
            ),
        ));
    }
    lease_runtime_result(admission, admission.candidate().clone())
}

fn lease_runtime_result(
    admission: &SemanticWorkspaceAdmission,
    candidate: RuntimeCandidateStatus,
) -> Result<WorkspaceEnsureResult> {
    let path_resolution = config::path_resolution_report(
        admission.config(),
        Some(admission.workspace_root()),
        config::PathResolutionMode::Cli,
    )?;
    Ok(WorkspaceEnsureResult {
        workspace_root: admission.workspace_root().display().to_string(),
        descriptor_directory: admission.config().paths.descriptor_dir.display().to_string(),
        path_resolution,
        started: admission.started(),
        log_file: admission.started().then(|| {
            daemon_log_file(admission.config(), admission.workspace_root(), admission.backend())
                .display()
                .to_string()
        }),
        selected: candidate,
        note: None,
        schema_version: SCHEMA_VERSION,
    })
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
        accept_indexing: Some(true),
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
    let current = process_identity(identity.pid).ok();
    process_identity_matches(identity, current.as_ref())
}

fn process_identity_matches(
    expected: &WorkspaceLeaseProcessIdentity,
    current: Option<&WorkspaceLeaseProcessIdentity>,
) -> bool {
    current == Some(expected)
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
    headless_authority::require_headless_backend(backend_name)?;
    let inspection =
        inspect_headless_workspace(workspace_root, StaleDescriptorPolicy::Preserve)?;
    for candidate in &inspection.candidates {
        if runtime_descriptor_matches(&candidate.descriptor, &candidate.descriptor_path, expected) {
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

fn runtime_descriptor_matches(
    descriptor: &ServerInstanceDescriptor,
    descriptor_path: &str,
    expected: &WorkspaceLeaseRuntimeIdentity,
) -> bool {
    descriptor == &expected.descriptor && descriptor_path == expected.descriptor_path
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

fn release_active_binding(binding: &WorkspaceLeaseBinding) -> Result<WorkspaceLeaseReleaseReceipt> {
    let (runtime_stopped, reason) = match binding.ownership {
        WorkspaceLeaseOwnership::Borrowed => {
            (false, WorkspaceLeaseReleaseReason::BorrowedRuntimePreserved)
        }
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
    headless_authority::require_headless_backend(backend_name)?;
    let inspection =
        inspect_headless_workspace(workspace_root, StaleDescriptorPolicy::Preserve)?;
    let Some(candidate) = inspection.candidates.into_iter().find(|candidate| {
        runtime_descriptor_matches(&candidate.descriptor, &candidate.descriptor_path, expected)
            && process_identity_is_live(&expected.process)
    }) else {
        return Ok(false);
    };
    if candidate.pid_alive {
        terminate_process(candidate.descriptor.pid, false);
        for _ in 0..20 {
            if !is_process_alive(candidate.descriptor.pid) {
                break;
            }
            thread::sleep(Duration::from_millis(250));
        }
        if is_process_alive(candidate.descriptor.pid) {
            terminate_process(candidate.descriptor.pid, true);
        }
    }
    delete_descriptor(&inspection.descriptor_directory, &candidate.descriptor)?;
    Ok(true)
}
