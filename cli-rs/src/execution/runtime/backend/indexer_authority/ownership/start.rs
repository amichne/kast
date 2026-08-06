fn start_indexer_runtime(
    request: SemanticRuntimeRequest,
) -> std::result::Result<AdmittedIndexerRuntime, SemanticRuntimeRejection> {
    let _install_use_lock = registration::storage::InstallUseLock::acquire().map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let _launch_lock = WorkspaceLaunchLock::acquire(&request.config, &request.workspace_root)
        .map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    loop {
        match reconcile_runtime_ownership(&request.config, &request.workspace_root).map_err(
            |error| runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error),
        )? {
        RuntimeOwnershipSnapshot::ServiceOwned(owned) if !owned.proven_dead.is_empty() => {
            repair::cleanup_proven_dead(&request.config, &owned.proven_dead).map_err(|error| {
                runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
            })?;
            continue;
        }
        RuntimeOwnershipSnapshot::ServiceOwned(owned) => {
            let runtime_id = owned.registration.receipt.runtime_instance_id.to_string();
            let registered_request = registered_runtime_request(&request, &owned.registration)
                .map_err(|error| {
                    runtime_cli_rejection(
                        &request.workspace_root,
                        request.workspace_kind,
                        error,
                    )
                })?;
            if let Ok(candidate) = admitted_candidate(&registered_request)
                && candidate.descriptor.runtime_instance_id.as_deref() == Some(&runtime_id)
            {
                return construct_registered_admitted_runtime(
                    &request,
                    &owned.registration,
                    candidate,
                    false,
                );
            }
            let candidate = wait_for_registered_runtime(&registered_request, &runtime_id)?;
            return construct_registered_admitted_runtime(
                &request,
                &owned.registration,
                candidate,
                false,
            );
        }
        RuntimeOwnershipSnapshot::LegacyOwned(legacy) => {
            if !legacy.proven_dead.is_empty() {
                repair::cleanup_proven_dead(&request.config, &legacy.proven_dead).map_err(
                    |error| {
                        runtime_cli_rejection(
                            &request.workspace_root,
                            request.workspace_kind,
                            error,
                        )
                    },
                )?;
                continue;
            }
            repair::stop_legacy_runtime(&request.config, *legacy).map_err(|error| {
                runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
            })?;
            continue;
        }
        RuntimeOwnershipSnapshot::ProvenDead(dead) => {
            repair::cleanup_proven_dead(&request.config, &dead).map_err(|error| {
                runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
            })?;
            continue;
        }
        RuntimeOwnershipSnapshot::Absent(absent) => {
            debug_assert_eq!(absent.workspace_root, request.workspace_root);
            break;
        }
        RuntimeOwnershipSnapshot::Conflict(conflict) => {
            return Err(runtime_cli_rejection(
                &request.workspace_root,
                request.workspace_kind,
                CliError::new(
                    "RUNTIME_OWNERSHIP_CONFLICT",
                    format!(
                        "More than one live runtime owns {}: {}.",
                        conflict.workspace_root.display(),
                        conflict.runtime_instance_ids.join(", ")
                    ),
                ),
            ));
        }
        RuntimeOwnershipSnapshot::Ambiguous(ambiguity) => {
            return Err(runtime_cli_rejection(
                &request.workspace_root,
                request.workspace_kind,
                CliError::new(
                    "RUNTIME_OWNERSHIP_AMBIGUOUS",
                    format!("{}: {}", ambiguity.workspace_root.display(), ambiguity.reason),
                ),
            ));
        }
        }
    }
    #[cfg(target_os = "macos")]
    let runtime_libs_dir = None;
    #[cfg(not(target_os = "macos"))]
    let runtime_libs_dir = Some(
        request
            .config
            .indexer
            .runtime_libs_dir
            .clone()
            .filter(|path| path.is_dir())
            .ok_or_else(|| {
                indexer_distribution_unavailable_rejection(
                    &request.workspace_root,
                    request.workspace_kind,
                )
            })?,
    );
    let daemon_args = DaemonStartArgs {
        workspace_root: Some(request.workspace_root.clone()),
        runtime_libs_dir,
        ..DaemonStartArgs::from(request.runtime_args.clone())
    };
    let prepared = prepare_service_registration(&request, daemon_args).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    service_manager::register(&prepared.validated.receipt.manager).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    publish_active_registration(&prepared).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    service_manager::start(&prepared.validated).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let runtime_id = prepared.validated.receipt.runtime_instance_id.to_string();
    let registered_request = registered_runtime_request(&request, &prepared.validated).map_err(
        |error| runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error),
    )?;
    let candidate = wait_for_registered_runtime(&registered_request, &runtime_id)?;
    construct_registered_admitted_runtime(&request, &prepared.validated, candidate, true)
}

fn wait_for_registered_runtime(
    request: &SemanticRuntimeRequest,
    runtime_id: &str,
) -> std::result::Result<RuntimeCandidateStatus, SemanticRuntimeRejection> {
    let started_at = Instant::now();
    if let Some(candidate) = poll_for_runtime_candidate(
        request.wait_timeout_ms,
        250,
        || u64::try_from(started_at.elapsed().as_millis()).unwrap_or(u64::MAX),
        || {
            admitted_candidate(request).ok().filter(|candidate| {
                candidate.descriptor.runtime_instance_id.as_deref() == Some(runtime_id)
            })
        },
        |duration_ms| thread::sleep(Duration::from_millis(duration_ms)),
    ) {
        return Ok(candidate);
    }
    Err(runtime_cli_rejection(
        &request.workspace_root,
        request.workspace_kind,
        CliError::new(
            "RUNTIME_TIMEOUT",
            format!(
                "Timed out waiting for the indexer for {}.",
                request.workspace_root.display()
            ),
        ),
    ))
}

fn registered_runtime_request(
    request: &SemanticRuntimeRequest,
    registration: &registration::ValidatedServiceRegistration,
) -> Result<SemanticRuntimeRequest> {
    let mut registered_request = request.clone();
    registered_request.config.paths.descriptor_dir =
        ownership::service_descriptor_directory(registration)?.to_path_buf();
    Ok(registered_request)
}

fn construct_registered_admitted_runtime(
    request: &SemanticRuntimeRequest,
    registration: &registration::ValidatedServiceRegistration,
    candidate: RuntimeCandidateStatus,
    started: bool,
) -> std::result::Result<AdmittedIndexerRuntime, SemanticRuntimeRejection> {
    let registered_request = registered_runtime_request(request, registration).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    construct_admitted_runtime(registered_request, candidate, started)
}

#[cfg(test)]
mod final_review_tests {
    use super::*;

    #[cfg(unix)]
    #[test]
    fn registered_runtime_admission_revalidates_from_persisted_descriptor_directory_final_review_regression()
     {
        use std::os::unix::net::UnixListener;

        let temp = tempfile::tempdir().expect("runtime directory");
        let workspace = temp.path().join("workspace");
        fs::create_dir(&workspace).expect("workspace");
        let workspace = fs::canonicalize(workspace).expect("canonical workspace");
        let caller_descriptor_directory = PathBuf::from("/caller-descriptors");
        let persisted_descriptor_directory = temp.path().join("persisted-descriptors");
        fs::create_dir(&persisted_descriptor_directory).expect("persisted descriptors");
        let socket_path = temp.path().join("runtime.sock");
        let _listener = UnixListener::bind(&socket_path).expect("runtime socket");
        let candidate = runtime_candidate(&workspace, &socket_path);
        fs::write(
            persisted_descriptor_directory.join("daemons.json"),
            serde_json::to_vec(&vec![candidate.descriptor.clone()]).expect("descriptor JSON"),
        )
        .expect("descriptor registry");
        let request = semantic_runtime_request(&workspace, &caller_descriptor_directory);
        let registration = validated_registration(&persisted_descriptor_directory);

        let admission =
            construct_registered_admitted_runtime(&request, &registration, candidate, false)
                .expect("registered admission");

        assert_eq!(
            request.config.paths.descriptor_dir,
            caller_descriptor_directory
        );
        assert_eq!(
            admission.config().paths.descriptor_dir,
            persisted_descriptor_directory
        );
        admission
            .validate_current()
            .expect("persisted descriptor revalidation");
    }

    fn semantic_runtime_request(
        workspace_root: &Path,
        descriptor_directory: &Path,
    ) -> SemanticRuntimeRequest {
        let mut config = KastConfig::defaults();
        config.paths.descriptor_dir = descriptor_directory.to_path_buf();
        SemanticRuntimeRequest {
            workspace_root: workspace_root.to_path_buf(),
            config,
            workspace_kind: SemanticWorkspaceKind::StandaloneGradleWorkspace,
            availability: SemanticRuntimeAvailability::StartIfMissing,
            accept_indexing: true,
            wait_timeout_ms: 1,
            runtime_args: RuntimeArgs {
                workspace_root: Some(PathBuf::from("/workspace")),
                idea_home: None,
                wait_timeout_ms: 1,
                accept_indexing: Some(true),
                no_auto_start: None,
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
            },
        }
    }

    #[cfg(unix)]
    fn runtime_candidate(workspace_root: &Path, socket_path: &Path) -> RuntimeCandidateStatus {
        let pid = u64::from(std::process::id());
        let backend_version = "test".to_string();
        let descriptor = ServerInstanceDescriptor {
            workspace_root: workspace_root.display().to_string(),
            backend_name: BackendName::Indexer.canonical().to_string(),
            backend_version: backend_version.clone(),
            runtime_instance_id: Some(uuid::Uuid::nil().to_string()),
            process_start_epoch_millis: Some(
                process_start_epoch_seconds(pid).expect("process start") * 1_000,
            ),
            owner_uid: Some(u64::from(unsafe { libc::geteuid() })),
            socket_file_identity: current_socket_file_identity(
                socket_path.to_str().expect("UTF-8 socket path"),
            )
            .expect("socket identity"),
            transport: "uds".to_string(),
            socket_path: socket_path.display().to_string(),
            pid,
            schema_version: SCHEMA_VERSION,
        };
        RuntimeCandidateStatus {
            descriptor_path: "registered-runtime".to_string(),
            descriptor,
            pid_alive: true,
            reachable: true,
            ready: true,
            runtime_status: Some(RuntimeStatusResponse {
                state: RuntimeState::Ready,
                healthy: true,
                active: true,
                indexing: false,
                backend_name: BackendName::Indexer.canonical().to_string(),
                backend_version: backend_version.clone(),
                workspace_root: workspace_root.display().to_string(),
                message: None,
                warnings: Vec::new(),
                source_module_names: Vec::new(),
                dependent_module_names_by_source_module_name: serde_json::Map::new(),
                reference_index_ready: true,
                reference_coverage_state: ReferenceCoverageState::Complete,
                reference_coverage_limitations: Vec::new(),
                published_workspace_generation: None,
                schema_version: SCHEMA_VERSION,
            }),
            capabilities: Some(serde_json::json!({
                "backendName": BackendName::Indexer.canonical(),
                "backendVersion": backend_version,
                "workspaceRoot": workspace_root,
                "schemaVersion": SCHEMA_VERSION
            })),
            error_message: None,
            schema_version: SCHEMA_VERSION,
        }
    }

    fn validated_registration(
        descriptor_directory: &Path,
    ) -> registration::ValidatedServiceRegistration {
        let runtime_instance_id = uuid::Uuid::nil();
        let launch = serde_json::from_value(serde_json::json!({
            "schemaVersion": 1,
            "workspaceRoot": "/workspace",
            "workspaceKey": "workspace-key",
            "runtimeInstanceId": runtime_instance_id,
            "ownerUid": 0,
            "workingDirectory": "/workspace",
            "command": ["/bin/true", "--socket-path=/runtime.sock"],
            "environment": [],
            "logFile": "/runtime.log",
            "descriptorDirectory": descriptor_directory,
            "socketPath": "/runtime.sock",
            "launcherPath": "/bin/true",
            "launcherSha256": "0".repeat(64),
            "runtimeConfigPath": "/runtime-config.json",
            "runtimeConfigSha256": "0".repeat(64)
        }))
        .expect("service launch registration");
        registration::ValidatedServiceRegistration {
            directory: PathBuf::from("/registration"),
            receipt_path: PathBuf::from("/registration/receipt.json"),
            receipt_sha256: "0".repeat(64),
            receipt: registration::ServiceRegistrationReceipt {
                schema_version: 1,
                workspace_root: "/workspace".to_string(),
                workspace_key: "workspace-key".to_string(),
                runtime_instance_id,
                launch_path: "/registration/launch.json".to_string(),
                launch_sha256: "0".repeat(64),
                definition_sha256: "0".repeat(64),
                manager: registration::ServiceManagerRegistration::Test {
                    state_path: "/manager-state.json".to_string(),
                    definition_path: "/registration/service.test.json".to_string(),
                },
            },
            launch,
        }
    }
}
