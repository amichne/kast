fn start_indexer_runtime(
    request: &SemanticRuntimeRequest,
) -> std::result::Result<(RuntimeCandidateStatus, bool), SemanticRuntimeRejection> {
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
            if let Ok(candidate) = admitted_candidate(request)
                && candidate.descriptor.runtime_instance_id.as_deref() == Some(&runtime_id)
            {
                return Ok((candidate, false));
            }
            return wait_for_registered_runtime(request, &runtime_id, false);
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
    let prepared = prepare_service_registration(request, daemon_args).map_err(|error| {
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
    wait_for_registered_runtime(request, &runtime_id, true)
}

fn wait_for_registered_runtime(
    request: &SemanticRuntimeRequest,
    runtime_id: &str,
    started: bool,
) -> std::result::Result<(RuntimeCandidateStatus, bool), SemanticRuntimeRejection> {
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
        return Ok((candidate, started));
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
