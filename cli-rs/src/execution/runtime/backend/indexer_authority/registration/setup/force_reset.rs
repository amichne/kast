const EXACT_OWNED_SHUTDOWN_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
const EXACT_OWNED_SHUTDOWN_POLL_INTERVAL: std::time::Duration =
    std::time::Duration::from_millis(25);

struct ExactOwnedSetupShutdown {
    config: KastConfig,
    root: RegisteredWorkspaceRoot,
    runtime_instance_id: Uuid,
}

struct ForceResetCleanup {
    shutdowns: Vec<ExactOwnedSetupShutdown>,
    proven_dead: Vec<(KastConfig, super::super::ownership::ProvenDeadRuntimeOwnership)>,
}

impl ForceResetCleanup {
    fn admit(
        observations: Vec<RuntimeSetupObservation>,
        has_unclassified_descriptors: bool,
    ) -> Result<Self> {
        if has_unclassified_descriptors {
            return Err(force_runtime_not_quiescent());
        }
        let mut shutdowns = Vec::new();
        let mut proven_dead = Vec::new();
        for observation in observations {
            let RuntimeSetupObservation {
                config,
                root,
                ownership,
            } = observation;
            match ownership {
                RuntimeOwnershipSnapshot::Absent(_) => {}
                RuntimeOwnershipSnapshot::ServiceOwned(owned) => {
                    shutdowns.push(ExactOwnedSetupShutdown {
                        config,
                        root,
                        runtime_instance_id: owned.registration.receipt.runtime_instance_id,
                    });
                }
                RuntimeOwnershipSnapshot::ProvenDead(dead) => proven_dead.push((config, dead)),
                RuntimeOwnershipSnapshot::LegacyOwned(_)
                | RuntimeOwnershipSnapshot::Conflict(_)
                | RuntimeOwnershipSnapshot::Ambiguous(_) => {
                    return Err(force_runtime_not_quiescent());
                }
            }
        }
        Ok(Self {
            shutdowns,
            proven_dead,
        })
    }

    fn execute(
        self,
        paths: &crate::manifest::ResolvedKastPaths,
    ) -> Result<RuntimeSetupAuthorization> {
        for shutdown in self.shutdowns {
            shutdown.execute()?;
        }
        for (config, dead) in self.proven_dead {
            super::super::repair::cleanup_proven_dead(&config, &dead)?;
        }
        let service_roots = registered_service_roots(paths)?;
        let descriptors = runtime_setup_descriptors(paths)?;
        if !service_roots.is_empty() || descriptors.registry_has_entries {
            return Err(force_runtime_not_quiescent());
        }
        Ok(RuntimeSetupAuthorization {
            pinned_release_roots: BTreeSet::new(),
        })
    }
}

impl ExactOwnedSetupShutdown {
    fn execute(self) -> Result<()> {
        let current = reconcile_registered_runtime_ownership(&self.config, &self.root)
            .map_err(force_runtime_reconciliation_blocked)?;
        match current {
            RuntimeOwnershipSnapshot::ServiceOwned(owned)
                if owned.registration.receipt.runtime_instance_id == self.runtime_instance_id =>
            {
                super::super::service_manager::unregister(&owned.registration.receipt.manager)
                    .map_err(|error| {
                        force_runtime_teardown_failed(self.runtime_instance_id, error)
                    })?;
            }
            RuntimeOwnershipSnapshot::ProvenDead(dead) => {
                return super::super::repair::cleanup_proven_dead(&self.config, &dead);
            }
            RuntimeOwnershipSnapshot::Absent(_) => return Ok(()),
            _ => return Err(force_runtime_not_quiescent()),
        }

        let deadline = std::time::Instant::now() + EXACT_OWNED_SHUTDOWN_TIMEOUT;
        loop {
            let current = reconcile_registered_runtime_ownership(&self.config, &self.root)
                .map_err(force_runtime_reconciliation_blocked)?;
            match current {
                RuntimeOwnershipSnapshot::Absent(_) => return Ok(()),
                RuntimeOwnershipSnapshot::ProvenDead(dead) => {
                    return super::super::repair::cleanup_proven_dead(&self.config, &dead);
                }
                RuntimeOwnershipSnapshot::ServiceOwned(owned)
                    if owned.registration.receipt.runtime_instance_id
                        == self.runtime_instance_id
                        && std::time::Instant::now() < deadline =>
                {
                    std::thread::sleep(EXACT_OWNED_SHUTDOWN_POLL_INTERVAL);
                }
                RuntimeOwnershipSnapshot::ServiceOwned(owned)
                    if owned.registration.receipt.runtime_instance_id
                        == self.runtime_instance_id =>
                {
                    return Err(force_runtime_teardown_timeout(self.runtime_instance_id));
                }
                _ => return Err(force_runtime_not_quiescent()),
            }
        }
    }
}

fn force_runtime_teardown_failed(runtime_instance_id: Uuid, error: CliError) -> CliError {
    let mut failure = CliError::new(
        "SETUP_RUNTIME_TEARDOWN_FAILED",
        format!(
            "Forced setup could not unload exact-owned runtime {runtime_instance_id}."
        ),
    );
    failure.details.insert(
        "runtimeInstanceId".to_string(),
        runtime_instance_id.to_string(),
    );
    failure
        .details
        .insert("causeCode".to_string(), error.code.to_string());
    failure
        .details
        .insert("causeMessage".to_string(), error.message);
    failure
}

fn force_runtime_teardown_timeout(runtime_instance_id: Uuid) -> CliError {
    let mut failure = CliError::new(
        "SETUP_RUNTIME_TEARDOWN_TIMEOUT",
        format!(
            "Exact-owned runtime {runtime_instance_id} remained live after its service was unloaded."
        ),
    );
    failure.details.insert(
        "runtimeInstanceId".to_string(),
        runtime_instance_id.to_string(),
    );
    failure
}

fn force_runtime_not_quiescent() -> CliError {
    CliError::new(
        "SETUP_RUNTIME_NOT_QUIESCENT",
        "Forced setup cannot delete Kast state while an ambiguous, unclassified, or insufficiently proven runtime registration or descriptor exists.",
    )
}

fn force_runtime_reconciliation_blocked(error: CliError) -> CliError {
    let mut blocker = force_runtime_not_quiescent();
    blocker
        .details
        .insert("ownershipCode".to_string(), error.code.to_string());
    blocker
        .details
        .insert("ownershipMessage".to_string(), error.message);
    blocker
}
