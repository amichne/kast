fn start_indexer_runtime<C: RequiredCapability>(
    request: SemanticRuntimeRequest<C>,
) -> std::result::Result<AdmittedIndexerRuntime<C>, SemanticRuntimeRejection> {
    let _install_use_lock = registration::storage::InstallUseLock::acquire().map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let _launch_lock = WorkspaceLaunchLock::acquire(&request.config, &request.workspace_root)
        .map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    let observed = reconcile_runtime_ownership(&request.config, &request.workspace_root)
        .map_err(|error| {
            runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
        })?;
    let admitted_workspace = request
        .demand
        .admit(CanonicalWorkspaceRoot::from_canonical(
            request.workspace_root.clone(),
        ));
    let launch_permit = match observed {
        RuntimeOwnershipSnapshot::ServiceOwned(owned) if owned.proven_dead.is_empty() => {
            return admit_service_owned_runtime(&request, *owned);
        }
        RuntimeOwnershipSnapshot::ServiceOwned(owned) => {
            let expected_runtime_id = owned.registration.receipt.runtime_instance_id;
            repair::cleanup_proven_dead(&request.config, &owned.proven_dead).map_err(|error| {
                runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
            })?;
            match reconcile_runtime_ownership(&request.config, &request.workspace_root).map_err(
                |error| {
                    runtime_cli_rejection(
                        &request.workspace_root,
                        request.workspace_kind,
                        error,
                    )
                },
            )? {
                RuntimeOwnershipSnapshot::ServiceOwned(current)
                    if current.proven_dead.is_empty()
                        && current.registration.receipt.runtime_instance_id
                            == expected_runtime_id =>
                {
                    return admit_service_owned_runtime(&request, *current);
                }
                _ => {
                    return Err(lifecycle_rejection(
                        &request,
                        TypestateLifecycleBlocker::IdentityChanged,
                    ));
                }
            }
        }
        RuntimeOwnershipSnapshot::Absent(absent) => {
            debug_assert_eq!(absent.workspace_root, request.workspace_root);
            admitted_workspace.observe_absent().permit_launch()
        }
        RuntimeOwnershipSnapshot::ProvenDead(dead) => {
            repair::cleanup_proven_dead(&request.config, &dead).map_err(|error| {
                runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
            })?;
            match reconcile_runtime_ownership(&request.config, &request.workspace_root).map_err(
                |error| {
                    runtime_cli_rejection(
                        &request.workspace_root,
                        request.workspace_kind,
                        error,
                    )
                },
            )? {
                RuntimeOwnershipSnapshot::Absent(_) => admitted_workspace
                    .observe_proven_dead()
                    .permit_single_replacement(),
                _ => {
                    return Err(lifecycle_rejection(
                        &request,
                        TypestateLifecycleBlocker::ReplacementFailed,
                    ));
                }
            }
        }
        RuntimeOwnershipSnapshot::LegacyOwned(_)
        | RuntimeOwnershipSnapshot::Conflict(_) => {
            return Err(lifecycle_rejection(
                &request,
                TypestateLifecycleBlocker::OwnershipConflict,
            ));
        }
        RuntimeOwnershipSnapshot::Ambiguous(_) => {
            return Err(lifecycle_rejection(
                &request,
                TypestateLifecycleBlocker::OwnershipAmbiguous,
            ));
        }
    };
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
    let starting = launch_permit.starting(RuntimeEpochId::from_validated(
            prepared.validated.receipt.runtime_instance_id.to_string(),
        ));
    service_manager::start(&prepared.validated, &starting).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    let runtime_id = prepared.validated.receipt.runtime_instance_id.to_string();
    let registered_request = registered_runtime_request(&request, &prepared.validated).map_err(
        |error| runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error),
    )?;
    let candidate = wait_for_registered_runtime(&registered_request, &runtime_id)?;
    construct_registered_admitted_runtime(
        &request,
        &prepared.validated,
        candidate,
        RuntimeAdmissionPath::Starting(starting),
    )
}

fn admit_service_owned_runtime<C: RequiredCapability>(
    request: &SemanticRuntimeRequest<C>,
    owned: ownership::ServiceOwnedRuntime,
) -> std::result::Result<AdmittedIndexerRuntime<C>, SemanticRuntimeRejection> {
    let runtime_id = owned.registration.receipt.runtime_instance_id.to_string();
    let registered_request = registered_runtime_request(request, &owned.registration).map_err(
        |error| runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error),
    )?;
    if let Ok(candidate) = admitted_candidate(&registered_request)
        && candidate.descriptor.runtime_instance_id.as_deref() == Some(&runtime_id)
    {
        return construct_registered_admitted_runtime(
            request,
            &owned.registration,
            candidate,
            RuntimeAdmissionPath::Reused,
        );
    }
    let candidate = wait_for_registered_runtime(&registered_request, &runtime_id)?;
    construct_registered_admitted_runtime(
        request,
        &owned.registration,
        candidate,
        RuntimeAdmissionPath::Reused,
    )
}

fn wait_for_registered_runtime<C: RequiredCapability>(
    request: &SemanticRuntimeRequest<C>,
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

fn registered_runtime_request<C: RequiredCapability>(
    request: &SemanticRuntimeRequest<C>,
    registration: &registration::ValidatedServiceRegistration,
) -> Result<SemanticRuntimeRequest<C>> {
    let mut registered_request = request.clone();
    registered_request.config.paths.descriptor_dir =
        ownership::service_descriptor_directory(registration)?.to_path_buf();
    Ok(registered_request)
}

fn construct_registered_admitted_runtime<C: RequiredCapability>(
    request: &SemanticRuntimeRequest<C>,
    registration: &registration::ValidatedServiceRegistration,
    candidate: RuntimeCandidateStatus,
    admission_path: RuntimeAdmissionPath<C>,
) -> std::result::Result<AdmittedIndexerRuntime<C>, SemanticRuntimeRejection> {
    let registered_request = registered_runtime_request(request, registration).map_err(|error| {
        runtime_cli_rejection(&request.workspace_root, request.workspace_kind, error)
    })?;
    construct_admitted_runtime(registered_request, candidate, admission_path)
}

#[cfg(test)]
include!("start/final_review_tests.rs");
