fn inspect_indexer_workspace(
    workspace_root: &Path,
    stale_descriptor_policy: StaleDescriptorPolicy,
) -> Result<WorkspaceInspection> {
    let config = KastConfig::load(workspace_root)?;
    inspect_indexer_workspace_with_config(workspace_root, &config, stale_descriptor_policy)
}

fn inspect_indexer_workspace_status_only(
    workspace_root: &Path,
    config: &KastConfig,
) -> Result<WorkspaceInspection> {
    inspect_indexer_workspace_with_probe(
        workspace_root,
        config,
        StaleDescriptorPolicy::Preserve,
        RuntimeInspectionProbe::StatusOnly,
    )
}

fn inspect_indexer_workspace_with_config(
    workspace_root: &Path,
    config: &KastConfig,
    stale_descriptor_policy: StaleDescriptorPolicy,
) -> Result<WorkspaceInspection> {
    inspect_indexer_workspace_with_probe(
        workspace_root,
        config,
        stale_descriptor_policy,
        RuntimeInspectionProbe::SemanticAdmission,
    )
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RuntimeInspectionProbe {
    StatusOnly,
    SemanticAdmission,
}

fn inspect_indexer_workspace_with_probe(
    workspace_root: &Path,
    config: &KastConfig,
    stale_descriptor_policy: StaleDescriptorPolicy,
    probe: RuntimeInspectionProbe,
) -> Result<WorkspaceInspection> {
    let descriptor_directory = config.paths.descriptor_dir.clone();
    let registered = find_indexer_descriptors(&descriptor_directory, workspace_root)?;
    let mut candidates = Vec::with_capacity(registered.len());
    for descriptor in registered {
        candidates.push(inspect_descriptor(
            &descriptor_directory,
            descriptor,
            stale_descriptor_policy,
            probe,
        )?);
    }
    candidates.sort_by(|a, b| {
        b.ready
            .cmp(&a.ready)
            .then_with(|| a.descriptor_path.cmp(&b.descriptor_path))
    });
    Ok(WorkspaceInspection { candidates })
}

fn inspect_descriptor(
    descriptor_directory: &Path,
    registered: RegisteredDescriptor,
    stale_descriptor_policy: StaleDescriptorPolicy,
    probe: RuntimeInspectionProbe,
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
    .and_then(RuntimeStatusResponse::validate_protocol)
    .and_then(|status| {
        validate_runtime_status_identity(&registered.descriptor, &status)?;
        Ok(status)
    });
    let (runtime_status, error_message) = match status_result {
        Ok(status) => (Some(status), None),
        Err(error) => (None, Some(error.message)),
    };
    let capabilities = if runtime_status.is_some()
        && probe == RuntimeInspectionProbe::SemanticAdmission
    {
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
        && status.healthy()
        && status.active()
}

fn is_ready(status: &RuntimeStatusResponse) -> bool {
    matches!(status.state, RuntimeState::Ready)
        && status.healthy()
        && status.active()
        && !status.indexing()
}

fn find_indexer_descriptors(
    descriptor_directory: &Path,
    workspace_root: &Path,
) -> Result<Vec<RegisteredDescriptor>> {
    let normalized = config::normalize(workspace_root.to_path_buf());
    let path = descriptor_directory.join("daemons.json");
    let mut descriptors = Vec::new();
    for element in read_descriptor_elements(&path)? {
        let backend = element.get("backendName").and_then(Value::as_str);
        let root = element.get("workspaceRoot").and_then(Value::as_str);
        let matches_root = root
            .map(PathBuf::from)
            .map(config::normalize)
            .is_some_and(|root| root == normalized);
        if matches_root && backend.is_none() {
            return Err(CliError::new(
                "RUNTIME_DESCRIPTOR_REGISTRY_INVALID",
                "A descriptor for the exact workspace has no valid backend identity.",
            ));
        }
        if backend != Some(BackendName::Indexer.canonical()) {
            continue;
        }
        let Some(_) = root else {
            return Err(CliError::new(
                "RUNTIME_DESCRIPTOR_REGISTRY_INVALID",
                "A Kast indexer descriptor has no valid workspace identity.",
            ));
        };
        if !matches_root {
            continue;
        }
        descriptors.push(
            serde_json::from_value::<ServerInstanceDescriptor>(element).map_err(|error| {
                CliError::new(
                    "RUNTIME_DESCRIPTOR_REGISTRY_INVALID",
                    format!("A Kast indexer descriptor for the exact workspace is invalid: {error}"),
                )
            })?,
        );
    }
    Ok(descriptors
        .into_iter()
        .filter(|descriptor| descriptor.backend_name == BackendName::Indexer.canonical())
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
