struct ProvenDeadSetupCleanup {
    candidates: Vec<(KastConfig, super::super::ownership::ProvenDeadRuntimeOwnership)>,
}

impl ProvenDeadSetupCleanup {
    fn admit(
        observations: Vec<(KastConfig, RuntimeOwnershipSnapshot)>,
        has_unclassified_descriptors: bool,
    ) -> Result<Self> {
        if has_unclassified_descriptors {
            return Err(force_runtime_not_quiescent());
        }
        let mut candidates = Vec::new();
        for (config, ownership) in observations {
            match ownership {
                RuntimeOwnershipSnapshot::Absent(_) => {}
                RuntimeOwnershipSnapshot::ProvenDead(dead) => candidates.push((config, dead)),
                RuntimeOwnershipSnapshot::ServiceOwned(_)
                | RuntimeOwnershipSnapshot::LegacyOwned(_)
                | RuntimeOwnershipSnapshot::Conflict(_)
                | RuntimeOwnershipSnapshot::Ambiguous(_) => {
                    return Err(force_runtime_not_quiescent());
                }
            }
        }
        Ok(Self { candidates })
    }

    fn execute(
        self,
        paths: &crate::manifest::ResolvedKastPaths,
    ) -> Result<RuntimeSetupAuthorization> {
        for (config, dead) in self.candidates {
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

fn force_runtime_not_quiescent() -> CliError {
    CliError::new(
        "SETUP_RUNTIME_NOT_QUIESCENT",
        "Forced setup cannot delete Kast state while a live, ambiguous, or unclassified runtime registration or descriptor exists. An exact owned epoch must complete automatic idle shutdown before setup can continue.",
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
