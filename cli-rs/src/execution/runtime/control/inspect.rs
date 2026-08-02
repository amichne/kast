fn inspect_headless_workspace(
    workspace_root: &Path,
    stale_descriptor_policy: StaleDescriptorPolicy,
) -> Result<WorkspaceInspection> {
    let config = KastConfig::load(workspace_root)?;
    inspect_headless_workspace_with_config(workspace_root, &config, stale_descriptor_policy)
}

fn inspect_headless_workspace_with_config(
    workspace_root: &Path,
    config: &KastConfig,
    stale_descriptor_policy: StaleDescriptorPolicy,
) -> Result<WorkspaceInspection> {
    let descriptor_directory = config.paths.descriptor_dir.clone();
    let registered = find_headless_descriptors(&descriptor_directory, workspace_root)?;
    let mut candidates = Vec::with_capacity(registered.len());
    for descriptor in registered {
        candidates.push(inspect_descriptor(
            &descriptor_directory,
            descriptor,
            stale_descriptor_policy,
        )?);
    }
    candidates.sort_by(|a, b| {
        b.ready
            .cmp(&a.ready)
            .then_with(|| a.descriptor_path.cmp(&b.descriptor_path))
    });
    Ok(WorkspaceInspection {
        descriptor_directory,
        candidates,
    })
}

fn inspect_descriptor(
    descriptor_directory: &Path,
    registered: RegisteredDescriptor,
    stale_descriptor_policy: StaleDescriptorPolicy,
) -> Result<RuntimeCandidateStatus> {
    let pid_alive = is_process_alive(registered.descriptor.pid);
    if !pid_alive {
        if stale_descriptor_policy == StaleDescriptorPolicy::Prune {
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

fn find_headless_descriptors(
    descriptor_directory: &Path,
    workspace_root: &Path,
) -> Result<Vec<RegisteredDescriptor>> {
    let descriptors = read_descriptors(descriptor_directory)?;
    let normalized = config::normalize(workspace_root.to_path_buf());
    Ok(descriptors
        .into_iter()
        .filter(|descriptor| descriptor.backend_name == BackendName::Headless.canonical())
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
