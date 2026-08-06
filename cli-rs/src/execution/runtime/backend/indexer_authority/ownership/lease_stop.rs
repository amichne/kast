pub(super) fn stop_exact_owned_runtime(
    workspace_root: &Path,
    expected: &WorkspaceLeaseRuntimeIdentity,
) -> Result<bool> {
    let _install_use_lock = super::registration::storage::InstallUseLock::acquire()?;
    let workspace_root = fs::canonicalize(workspace_root)?;
    let config = KastConfig::load(&workspace_root)?;
    let _lock = WorkspaceLaunchLock::acquire(&config, &workspace_root)?;
    loop {
        match reconcile_runtime_ownership(&config, &workspace_root)? {
            RuntimeOwnershipSnapshot::ServiceOwned(owned) => {
                if !owned_runtime_matches_lease(&owned, expected)? {
                    return Ok(false);
                }
                if !owned.proven_dead.is_empty() {
                    cleanup_proven_dead(&config, &owned.proven_dead)?;
                    continue;
                }
                stop_service_runtime(&config, *owned)?;
                return Ok(true);
            }
            RuntimeOwnershipSnapshot::LegacyOwned(owned) => {
                if !legacy_runtime_matches_lease(&owned, expected)? {
                    return Ok(false);
                }
                if !owned.proven_dead.is_empty() {
                    cleanup_proven_dead(&config, &owned.proven_dead)?;
                    continue;
                }
                stop_legacy_runtime(&config, *owned)?;
                return Ok(true);
            }
            RuntimeOwnershipSnapshot::Absent(_) | RuntimeOwnershipSnapshot::ProvenDead(_) => {
                return Ok(false);
            }
            RuntimeOwnershipSnapshot::Conflict(_) => {
                return Err(CliError::new(
                    "RUNTIME_OWNERSHIP_CONFLICT",
                    "The leased workspace has duplicate live runtimes.",
                ));
            }
            RuntimeOwnershipSnapshot::Ambiguous(ambiguity) => {
                return Err(CliError::new(
                    "RUNTIME_OWNERSHIP_AMBIGUOUS",
                    ambiguity.reason,
                ));
            }
        }
    }
}

fn owned_runtime_matches_lease(
    owned: &ServiceOwnedRuntime,
    expected: &WorkspaceLeaseRuntimeIdentity,
) -> Result<bool> {
    let Some(descriptor) = &owned.descriptor else {
        return Ok(false);
    };
    Ok(registered_descriptor_matches_lease(descriptor, expected)
        && super::super::process_identity(owned.process.identity.pid)? == expected.process)
}

fn legacy_runtime_matches_lease(
    owned: &LegacyOwnedRuntime,
    expected: &WorkspaceLeaseRuntimeIdentity,
) -> Result<bool> {
    Ok(registered_descriptor_matches_lease(&owned.descriptor, expected)
        && super::super::process_identity(owned.process.identity.pid)? == expected.process)
}

fn registered_descriptor_matches_lease(
    observed: &RegisteredDescriptor,
    expected: &WorkspaceLeaseRuntimeIdentity,
) -> bool {
    observed.id == expected.descriptor_path && observed.descriptor == expected.descriptor
}
