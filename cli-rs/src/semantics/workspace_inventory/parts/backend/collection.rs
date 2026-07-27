pub(super) fn collect_backend_inventory(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> BackendWorkspaceInventory {
    let first = collect_attempt(root, kind_domain, rpc);
    match first {
        Err(failure) if is_stale(&failure.failure) => match collect_attempt(root, kind_domain, rpc)
        {
            Ok(inventory) => inventory,
            Err(second) if is_stale(&second.failure) => stale_inventory(second.modules),
            Err(second) => failure_inventory(second),
        },
        Ok(inventory) => inventory,
        Err(failure) => failure_inventory(failure),
    }
}

pub(super) fn revalidate_backend_inventory(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    before: &BackendWorkspaceInventory,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> WorkspaceLaneStamp<super::model::BackendWorkspaceStamp> {
    if let Some(snapshot) = before.snapshot_token() {
        return match validate_snapshot(kind_domain, snapshot, rpc) {
            Ok(()) => before.stamp().map_or_else(
                || {
                    WorkspaceLaneStamp::Unavailable(WorkspaceLaneUnavailableReason::new(
                        "BACKEND_LEASE_STAMP_UNAVAILABLE",
                    ))
                },
                WorkspaceLaneStamp::Available,
            ),
            Err(failure) => WorkspaceLaneStamp::Unavailable(WorkspaceLaneUnavailableReason::new(
                format!("BACKEND_LEASE_REVALIDATION:{failure:?}"),
            )),
        };
    }

    backend_inventory_barrier_stamp(&collect_backend_inventory(root, kind_domain, rpc))
}

fn backend_inventory_barrier_stamp(
    inventory: &BackendWorkspaceInventory,
) -> WorkspaceLaneStamp<super::model::BackendWorkspaceStamp> {
    inventory.stamp().map_or_else(
        || {
            WorkspaceLaneStamp::Unavailable(WorkspaceLaneUnavailableReason::new(format!(
                "BACKEND_{:?}:{:?}",
                inventory.coverage(),
                inventory.limitations()
            )))
        },
        WorkspaceLaneStamp::Available,
    )
}

fn collect_attempt(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> Result<BackendWorkspaceInventory, BackendAttemptFailure> {
    let metadata =
        fetch_metadata(root, kind_domain, rpc).map_err(|failure| BackendAttemptFailure {
            failure,
            scope: BackendFailureScope::Metadata,
            modules: BTreeMap::new(),
        })?;
    let mut files = BTreeMap::<WorkspaceFilePath, BTreeSet<BackendModuleName>>::new();
    let mut modules = BTreeMap::new();
    let mut limitations = BTreeMap::new();
    let mut workspace_coverage = BackendWorkspaceCoverage::Complete;

    for module in &metadata.modules {
        if !module.containment_complete {
            workspace_coverage = BackendWorkspaceCoverage::Partial;
            increment(
                &mut limitations,
                WorkspaceInventoryLimitationCode::PathContainmentUnprovable,
            );
        }
        match exhaust_module(root, kind_domain, &metadata.snapshot, module, rpc) {
            Ok(module_files) => {
                for path in module_files {
                    files.entry(path).or_default().insert(module.name.clone());
                }
                modules.insert(
                    module.name.clone(),
                    module_inventory(
                        module,
                        if module.containment_complete {
                            BackendModuleCoverage::Complete
                        } else {
                            BackendModuleCoverage::Partial
                        },
                    ),
                );
            }
            Err(failure) if is_project_model_incomplete(&failure) || is_stale(&failure) => {
                return Err(BackendAttemptFailure {
                    failure,
                    scope: BackendFailureScope::WholeAttempt,
                    modules: partial_modules(&metadata.modules),
                });
            }
            Err(failure) => {
                workspace_coverage = BackendWorkspaceCoverage::Partial;
                increment(
                    &mut limitations,
                    WorkspaceInventoryLimitationCode::BackendPageIncomplete,
                );
                if matches!(failure, BackendRpcFailure::Containment { .. }) {
                    increment(
                        &mut limitations,
                        WorkspaceInventoryLimitationCode::PathContainmentUnprovable,
                    );
                }
                modules.insert(
                    module.name.clone(),
                    module_inventory(module, BackendModuleCoverage::Partial),
                );
            }
        }
    }

    validate_snapshot(kind_domain, &metadata.snapshot, rpc).map_err(|failure| {
        BackendAttemptFailure {
            failure,
            scope: BackendFailureScope::WholeAttempt,
            modules: partial_modules(&metadata.modules),
        }
    })?;
    Ok(BackendWorkspaceInventory::new(
        files,
        modules,
        workspace_coverage,
        Some(metadata.snapshot),
        limitations,
    ))
}
