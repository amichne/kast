fn inspect_workspace(
    workspace_root: &Path,
    preference: RuntimeBackendPreference,
    prune_stale_descriptors: bool,
) -> Result<WorkspaceInspection> {
    let config = KastConfig::load(workspace_root)?;
    inspect_workspace_with_config(workspace_root, &config, preference, prune_stale_descriptors)
}

fn inspect_workspace_with_config(
    workspace_root: &Path,
    config: &KastConfig,
    preference: RuntimeBackendPreference,
    prune_stale_descriptors: bool,
) -> Result<WorkspaceInspection> {
    let descriptor_directory = config.paths.descriptor_dir.clone();
    let backend_name = preference.backend_filter();
    let registered =
        find_compatible_descriptors(&descriptor_directory, workspace_root, backend_name)?;
    let mut candidates = Vec::with_capacity(registered.len());
    for descriptor in registered {
        candidates.push(inspect_descriptor(
            &descriptor_directory,
            descriptor,
            prune_stale_descriptors,
        )?);
    }
    candidates.sort_by(|a, b| {
        b.ready
            .cmp(&a.ready)
            .then_with(|| a.descriptor_path.cmp(&b.descriptor_path))
    });
    let selected = select_status_candidate(&candidates, backend_name);
    Ok(WorkspaceInspection {
        descriptor_directory,
        candidates,
        selected,
    })
}

fn inspect_descriptor(
    descriptor_directory: &Path,
    registered: RegisteredDescriptor,
    prune_stale_descriptors: bool,
) -> Result<RuntimeCandidateStatus> {
    let pid_alive = is_process_alive(registered.descriptor.pid);
    if !pid_alive {
        if prune_stale_descriptors {
            delete_descriptor(descriptor_directory, &registered.descriptor)?;
        }
        return Ok(RuntimeCandidateStatus {
            descriptor_path: registered.id,
            descriptor: registered.descriptor.clone(),
            pid_alive: false,
            reachable: false,
            ready: false,
            runtime_status: None,
            capabilities: None,
            error_message: Some(format!(
                "Process {} is not alive",
                registered.descriptor.pid
            )),
            schema_version: SCHEMA_VERSION,
        });
    }

    let socket_path = Path::new(&registered.descriptor.socket_path);
    let status_result = rpc::request::<RuntimeStatusResponse>(
        socket_path,
        "runtime/status",
        Value::Object(Default::default()),
    )
    .and_then(|status| {
        validate_runtime_status_identity(&registered.descriptor, &status)?;
        Ok(status)
    });
    let (runtime_status, error_message) = match status_result {
        Ok(status) => (Some(status), None),
        Err(error) => (None, Some(error.message)),
    };
    let capabilities = if runtime_status.is_some() {
        rpc::request::<Value>(
            socket_path,
            "capabilities",
            Value::Object(Default::default()),
        )
        .ok()
    } else {
        None
    };
    let ready = runtime_status.as_ref().is_some_and(is_ready);
    Ok(RuntimeCandidateStatus {
        descriptor_path: registered.id,
        descriptor: registered.descriptor,
        pid_alive: true,
        reachable: runtime_status.is_some(),
        ready,
        runtime_status,
        capabilities,
        error_message,
        schema_version: SCHEMA_VERSION,
    })
}

fn validate_runtime_status_identity(
    descriptor: &ServerInstanceDescriptor,
    status: &RuntimeStatusResponse,
) -> Result<()> {
    let descriptor_root = config::normalize(PathBuf::from(&descriptor.workspace_root));
    let status_root = config::normalize(PathBuf::from(&status.workspace_root));
    if descriptor_root != status_root || descriptor.backend_name != status.backend_name {
        return Err(CliError::new(
            "RUNTIME_IDENTITY_MISMATCH",
            format!(
                "Runtime status identity {}:{} does not match descriptor identity {}:{}",
                status_root.display(),
                status.backend_name,
                descriptor_root.display(),
                descriptor.backend_name,
            ),
        ));
    }
    Ok(())
}

fn wait_for_servable(
    workspace_root: &Path,
    backend_name: Option<BackendName>,
    accept_indexing: bool,
    wait_timeout_ms: u64,
) -> Result<RuntimeCandidateStatus> {
    let deadline = Instant::now() + Duration::from_millis(wait_timeout_ms);
    let preference = backend_name
        .map(RuntimeBackendPreference::Fixed)
        .unwrap_or(RuntimeBackendPreference::Automatic);
    while Instant::now() < deadline {
        let inspection = inspect_workspace(workspace_root, preference, true)?;
        if let Some(selected) =
            select_servable(&inspection.candidates, backend_name, accept_indexing)
        {
            return Ok(selected);
        }
        thread::sleep(Duration::from_millis(250));
    }
    Err(CliError::new(
        "RUNTIME_TIMEOUT",
        format!(
            "Timed out waiting for {} runtime to become {} for {}",
            backend_name.map(BackendName::canonical).unwrap_or("any"),
            if accept_indexing { "servable" } else { "ready" },
            workspace_root.display()
        ),
    ))
}

fn select_servable(
    candidates: &[RuntimeCandidateStatus],
    backend_name: Option<BackendName>,
    accept_indexing: bool,
) -> Option<RuntimeCandidateStatus> {
    let mut matches: Vec<_> = candidates
        .iter()
        .filter(|candidate| {
            backend_name
                .is_none_or(|backend| candidate.descriptor.backend_name == backend.canonical())
        })
        .filter(|candidate| {
            if accept_indexing {
                candidate.runtime_status.as_ref().is_some_and(is_servable)
            } else {
                candidate.ready
            }
        })
        .cloned()
        .collect();
    matches.sort_by(|a, b| {
        (b.descriptor.backend_name == "idea")
            .cmp(&(a.descriptor.backend_name == "idea"))
            .then_with(|| {
                (b.descriptor.backend_name == "headless")
                    .cmp(&(a.descriptor.backend_name == "headless"))
            })
            .then_with(|| a.descriptor_path.cmp(&b.descriptor_path))
    });
    matches.into_iter().next()
}

fn select_status_candidate(
    candidates: &[RuntimeCandidateStatus],
    backend_name: Option<BackendName>,
) -> Option<RuntimeCandidateStatus> {
    let mut matches: Vec<_> = candidates
        .iter()
        .filter(|candidate| {
            backend_name
                .is_none_or(|backend| candidate.descriptor.backend_name == backend.canonical())
        })
        .cloned()
        .collect();
    matches.sort_by(|a, b| {
        b.ready
            .cmp(&a.ready)
            .then_with(|| {
                (b.descriptor.backend_name == "idea").cmp(&(a.descriptor.backend_name == "idea"))
            })
            .then_with(|| {
                (b.descriptor.backend_name == "headless")
                    .cmp(&(a.descriptor.backend_name == "headless"))
            })
            .then_with(|| a.descriptor_path.cmp(&b.descriptor_path))
    });
    matches.into_iter().next()
}

fn is_servable(status: &RuntimeStatusResponse) -> bool {
    matches!(status.state, RuntimeState::Ready | RuntimeState::Indexing)
        && status.healthy
        && status.active
}

fn is_ready(status: &RuntimeStatusResponse) -> bool {
    matches!(status.state, RuntimeState::Ready)
        && status.healthy
        && status.active
        && !status.indexing
}

fn find_compatible_descriptors(
    descriptor_directory: &Path,
    workspace_root: &Path,
    backend_name: Option<BackendName>,
) -> Result<Vec<RegisteredDescriptor>> {
    let descriptors = read_descriptors(descriptor_directory)?;
    let normalized = config::normalize(workspace_root.to_path_buf());
    Ok(descriptors
        .into_iter()
        .filter(|descriptor| {
            backend_name.is_none_or(|backend| descriptor.backend_name == backend.canonical())
        })
        .filter(|descriptor| descriptor_matches_workspace(descriptor, &normalized))
        .map(|descriptor| RegisteredDescriptor {
            id: descriptor_id(&descriptor),
            descriptor,
        })
        .collect())
}

fn descriptor_matches_workspace(
    descriptor: &ServerInstanceDescriptor,
    workspace_root: &Path,
) -> bool {
    let descriptor_root = config::normalize(PathBuf::from(&descriptor.workspace_root));
    descriptor_root == workspace_root
}
