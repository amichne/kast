#[derive(Debug, Clone, Copy)]
enum MutationFailurePoint {
    BeforeJournal,
    RecoveryJournalPersistence,
    RecoveryJournalDirectorySync,
    AfterJournal,
    AfterWrite(usize),
    AfterAllWrites,
    AfterRefresh,
    AfterDiagnostics,
    AfterSemanticVerification,
    AfterDurableEvidence,
}

impl MutationFailurePoint {
    fn active(self) -> bool {
        #[cfg(debug_assertions)]
        {
            let Ok(value) = std::env::var("KAST_TEST_MUTATION_FAILURE_POINT") else {
                return false;
            };
            match self {
                Self::BeforeJournal => value == "BEFORE_RECOVERY_JOURNAL",
                Self::RecoveryJournalPersistence => value == "RECOVERY_JOURNAL_PERSISTENCE",
                Self::RecoveryJournalDirectorySync => {
                    value == "RECOVERY_JOURNAL_DIRECTORY_SYNC"
                }
                Self::AfterJournal => value == "AFTER_RECOVERY_JOURNAL",
                Self::AfterWrite(index) => {
                    value == format!("AFTER_WRITE_{index}")
                        || value == "AFTER_EACH_INDIVIDUAL_CAS_CREATE"
                }
                Self::AfterAllWrites => {
                    matches!(
                        value.as_str(),
                        "AFTER_ALL_WRITES" | "AFTER_MUTATION_BEFORE_VERIFIED_EVIDENCE"
                    )
                }
                Self::AfterRefresh => value == "AFTER_REFRESH",
                Self::AfterDiagnostics => value == "AFTER_DIAGNOSTICS",
                Self::AfterSemanticVerification => value == "AFTER_SEMANTIC_VERIFICATION",
                Self::AfterDurableEvidence => {
                    matches!(
                        value.as_str(),
                        "AFTER_DURABLE_VERIFIED_EVIDENCE" | "AFTER_VERIFIED_EVIDENCE"
                    )
                }
            }
        }
        #[cfg(not(debug_assertions))]
        {
            let _ = self;
            false
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RecoveryObservationClass {
    AllPre,
    AllPost,
    Mixed,
    Foreign,
}

pub(crate) fn run_recover(raw_recovery_id: String) -> Result<i32> {
    let recovery_id = parse_recovery_id(&raw_recovery_id)?;
    let paths = PlanPaths::new(recovery_id);
    let _operation_lock = PlanOperationLock::acquire(&paths.lock)?;
    let mut plan = read_plan(&paths.plan, recovery_id)?;
    let workspace_root = require_current_workspace(&plan, recovery_id)?;
    if let StoredPlanState::Terminal { receipt } = &plan.state {
        return replay_terminal_receipt(&paths, &plan, receipt);
    }
    let mut journal = read_recovery(&paths.recovery, recovery_id, &plan)?;
    let lease = match OwnedMutationLease::acquire(plan.plan_id, &workspace_root) {
        Ok(lease) => lease,
        Err(error) => {
            return print_recovery_required(
                &plan,
                format!("Recovery could not acquire its exact-root lease: {}.", error.message),
            );
        }
    };
    let lease_id = lease.id();
    journal.rotate_mutation_attempt();
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(
            &plan,
            lease,
            format!("Recovery could not persist its fresh mutation attempt: {}.", error.message),
        );
    }
    let inspection = match inspect_mutation_scratch(
        &workspace_root,
        &journal,
        lease_id.clone(),
    ) {
        Ok(inspection) => inspection,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if inspection.has_blocker() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Gated recovery inspection found unowned or unsafe mutation scratch and performed no write.",
        );
    }
    for scratch in journal.owned_scratch.clone() {
        if inspection.scratch_is_present(&scratch)
            && let Err(error) = restore_owned_mutation_scratch(
                &workspace_root,
                &journal,
                &scratch,
                lease_id.clone(),
            )
        {
            return stop_with_recovery_required(&plan, lease, error.message);
        }
        if !inspection.scratch_is_present(&scratch) && !inspection.scratch_is_absent(&scratch) {
            return stop_with_recovery_required(
                &plan,
                lease,
                "Gated recovery inspection did not close one journal-owned scratch set.",
            );
        }
        if let Err(error) = journal.remove_scratch(&scratch) {
            return stop_with_recovery_required(&plan, lease, error.message);
        }
        if let Err(error) = replace_recovery(&paths.recovery, &journal) {
            return stop_with_recovery_required(&plan, lease, error.message);
        }
    }
    let final_inspection = match inspect_mutation_scratch(
        &workspace_root,
        &journal,
        lease_id.clone(),
    ) {
        Ok(inspection) => inspection,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if final_inspection.has_blocker() || final_inspection.owned_present() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Recovery could not prove the exact scratch namespace clear after reconciliation.",
        );
    }
    let observations = match observe_exact_transitions(
        &workspace_root,
        &journal.transitions,
        lease_id.clone(),
        Some(journal.mutation_attempt_id),
    ) {
        Ok(observations) => observations,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    match classify_recovery_observations(&journal.transitions, &observations) {
        RecoveryObservationClass::AllPre => {
            if let Err(error) = refresh_restored_preimages(
                &workspace_root,
                &journal.transitions,
                lease_id.clone(),
            ) {
                return stop_with_recovery_required(&plan, lease, error.message);
            }
            let terminal_scratch = match inspect_mutation_scratch(
                &workspace_root,
                &journal,
                lease_id,
            ) {
                Ok(inspection) => inspection,
                Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
            };
            if !journal.owned_scratch.is_empty()
                || terminal_scratch.has_blocker()
                || terminal_scratch.owned_present()
            {
                return stop_with_recovery_required(
                    &plan,
                    lease,
                    "Rolled-back source proof found unresolved mutation scratch.",
                );
            }
            if let Err(error) = lease.release() {
                return print_recovery_required(
                    &plan,
                    format!("Recovery proved the exact pre-state but could not release its lease: {}.", error.message),
                );
            }
            let receipt = TerminalMutationReceipt::rolled_back(
                &plan,
                "Recovery proved that every exact source preimage is retained.",
            );
            finish_terminal_receipt(&paths, &mut plan, receipt)
        }
        RecoveryObservationClass::AllPost => finish_recovered_postimages(
            &paths,
            &mut plan,
            &mut journal,
            &workspace_root,
            lease,
            lease_id,
            &observations,
        ),
        RecoveryObservationClass::Mixed => roll_back_mixed_transitions(
            &paths,
            &mut plan,
            &mut journal,
            &workspace_root,
            lease,
            lease_id,
            &observations,
        ),
        RecoveryObservationClass::Foreign => stop_with_recovery_required(
            &plan,
            lease,
            "Recovery observed a foreign source image and performed no write.",
        ),
    }
}
