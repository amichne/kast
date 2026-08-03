#[derive(Debug, Clone, Copy)]
enum MutationFailurePoint {
    BeforeJournal,
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
        return print_terminal_receipt(receipt);
    }
    let mut journal = read_recovery(&paths.recovery, recovery_id, &plan)?;
    let lease = match OwnedMutationLease::acquire(&workspace_root) {
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

fn classify_recovery_observations(
    transitions: &[ExactMutationTransition],
    observations: &[RawExactFileObservation],
) -> RecoveryObservationClass {
    let mut pre = 0usize;
    let mut post = 0usize;
    for (transition, observation) in transitions.iter().zip(observations) {
        if transition.matches_pre(observation) {
            pre += 1;
        } else if transition.matches_post(observation) {
            post += 1;
        } else {
            return RecoveryObservationClass::Foreign;
        }
    }
    if pre == transitions.len() {
        RecoveryObservationClass::AllPre
    } else if post == transitions.len() {
        RecoveryObservationClass::AllPost
    } else {
        RecoveryObservationClass::Mixed
    }
}

#[allow(clippy::too_many_arguments)]
fn finish_recovered_postimages(
    paths: &PlanPaths,
    plan: &mut StoredPlan,
    journal: &mut RecoveryJournal,
    workspace_root: &Path,
    lease: OwnedMutationLease,
    lease_id: AgentWorkspaceLeaseId,
    observations: &[RawExactFileObservation],
) -> Result<i32> {
    let post_files = journal
        .transitions
        .iter()
        .map(|transition| CompilerDiagnosticFileHash {
            file_path: transition.absolute_path.clone(),
            sha256: transition.postimage.sha256().to_string(),
        })
        .collect::<Vec<_>>();
    let verification = (|| {
        let refresh = refresh_exact_transitions(
            workspace_root,
            &journal.transitions,
            lease_id.clone(),
        )?;
        let post_diagnostics = collect_complete_diagnostics(
            workspace_root,
            &post_files,
            lease_id.clone(),
        )?;
        let deltas =
            compare_diagnostic_snapshots(&journal.pre_diagnostics, &post_diagnostics)?;
        let semantic_postcondition = verify_mutation_postcondition(
            workspace_root,
            &plan.operation,
            &journal.transitions,
            lease_id.clone(),
        )?;
        Ok((refresh, post_diagnostics, deltas, semantic_postcondition))
    })();
    let (refresh, post_diagnostics, deltas, semantic_postcondition) = match verification {
        Ok(evidence) => evidence,
        Err(error) if deterministic_recovery_verification_failure(&error) => {
            return roll_back_mixed_transitions(
                paths,
                plan,
                journal,
                workspace_root,
                lease,
                lease_id,
                observations,
            );
        }
        Err(error) => return stop_with_recovery_required(plan, lease, error.message),
    };
    let compiler_verification = CompilerVerificationEvidence {
        pre_diagnostics: journal.pre_diagnostics.clone(),
        refresh,
        analysis: CompilerAnalysisEvidence {
            outcome: CompleteCompilerAnalysis::Complete,
            post_diagnostics: post_diagnostics.clone(),
            deltas,
        },
        semantic_postcondition,
    };
    journal.state = RecoveryJournalState::SemanticVerified {
        compiler_verification: Box::new(compiler_verification.clone()),
    };
    if let Err(error) = replace_recovery(&paths.recovery, journal) {
        return stop_with_recovery_required(plan, lease, error.message);
    }
    let terminal_scratch = match inspect_mutation_scratch(
        workspace_root,
        journal,
        lease_id.clone(),
    ) {
        Ok(inspection) => inspection,
        Err(error) => return stop_with_recovery_required(plan, lease, error.message),
    };
    if !journal.owned_scratch.is_empty()
        || terminal_scratch.has_blocker()
        || terminal_scratch.owned_present()
    {
        return stop_with_recovery_required(
            plan,
            lease,
            "Verified source proof found unresolved mutation scratch.",
        );
    }
    let files = journal
        .transitions
        .iter()
        .map(ExactMutationTransition::verified_file)
        .collect::<Vec<_>>();
    let diagnostics = post_diagnostics.verified_diagnostics();
    journal.state = RecoveryJournalState::DurableVerifiedEvidence {
        files: files.clone(),
        diagnostics,
        compiler_verification: Box::new(compiler_verification.clone()),
    };
    if let Err(error) = replace_recovery(&paths.recovery, journal) {
        return stop_with_recovery_required(plan, lease, error.message);
    }
    let lease_receipt = match lease.release() {
        Ok(receipt) => receipt,
        Err(error) => {
            return print_recovery_required(
                plan,
                format!("Recovery verified source state but could not release its lease: {}.", error.message),
            );
        }
    };
    journal.state = RecoveryJournalState::VerifiedEvidence {
        files: files.clone(),
        diagnostics,
        compiler_verification: Box::new(compiler_verification.clone()),
        lease: lease_receipt.clone(),
    };
    if let Err(error) = replace_recovery(&paths.recovery, journal) {
        return print_recovery_required(plan, error.message);
    }
    let receipt = TerminalMutationReceipt::verified(
        plan,
        files,
        diagnostics,
        compiler_verification,
        lease_receipt,
    );
    finish_terminal_receipt(paths, plan, receipt)
}

fn deterministic_recovery_verification_failure(error: &CliError) -> bool {
    matches!(
        error.code,
        "KAST_NEW_COMPILER_ERROR"
            | "KAST_COMPILER_VERIFICATION_INVALID"
            | "KAST_MUTATION_POSTCONDITION_FAILED"
            | "KAST_MUTATION_POSTCONDITION_INVALID"
            | "KAST_RAW_MUTATION_SESSION_INVALID"
    )
}

#[allow(clippy::too_many_arguments)]
fn roll_back_mixed_transitions(
    paths: &PlanPaths,
    plan: &mut StoredPlan,
    journal: &mut RecoveryJournal,
    workspace_root: &Path,
    lease: OwnedMutationLease,
    lease_id: AgentWorkspaceLeaseId,
    observations: &[RawExactFileObservation],
) -> Result<i32> {
    for index in (0..journal.transitions.len()).rev() {
        let transition = journal.transitions[index].clone();
        let observation = &observations[index];
        if transition.matches_pre(observation) {
            continue;
        }
        if let Err(error) = journal.arm_restore_scratch(index) {
            return stop_with_recovery_required(plan, lease, error.message);
        }
        if let Err(error) = replace_recovery(&paths.recovery, journal) {
            return stop_with_recovery_required(
                plan,
                lease,
                format!("Recovery could not persist restore scratch authority: {}.", error.message),
            );
        }
        let scratch = match journal.active_scratch(index) {
            Ok(scratch) => scratch.clone(),
            Err(error) => return stop_with_recovery_required(plan, lease, error.message),
        };
        let admission = match inspect_mutation_scratch(
            workspace_root,
            journal,
            lease_id.clone(),
        ) {
            Ok(inspection) => inspection,
            Err(error) => return stop_with_recovery_required(plan, lease, error.message),
        };
        if admission.has_blocker()
            || admission.owned_present()
            || !admission.scratch_is_absent(&scratch)
        {
            return stop_with_recovery_required(
                plan,
                lease,
                "Restore scratch admission did not prove every predeclared role absent and safe.",
            );
        }
        if let Err(error) = restore_exact_transition(
            workspace_root,
            &transition,
            lease_id.clone(),
            journal.mutation_attempt_id,
            &scratch,
        ) {
            let detail_failure = journal
                .validate_backend_recovery_details(&error)
                .err()
                .map(|failure| failure.message);
            let mut reason = format!(
                "Recovery could not restore an exact transition: {}.",
                error.message
            );
            if let Some(failure) = detail_failure {
                reason.push_str(" Its backend recovery path details were invalid: ");
                reason.push_str(&failure);
                reason.push('.');
            }
            return stop_with_recovery_required(plan, lease, reason);
        }
        let inspection = match inspect_mutation_scratch(
            workspace_root,
            journal,
            lease_id.clone(),
        ) {
            Ok(inspection) => inspection,
            Err(error) => return stop_with_recovery_required(plan, lease, error.message),
        };
        if inspection.has_blocker()
            || inspection.owned_present()
            || !inspection.scratch_is_absent(&scratch)
        {
            return stop_with_recovery_required(
                plan,
                lease,
                "A restore write did not leave every owned scratch role absent and safe.",
            );
        }
        if let Err(error) = journal.remove_scratch(&scratch) {
            return stop_with_recovery_required(plan, lease, error.message);
        }
        if let Err(error) = replace_recovery(&paths.recovery, journal) {
            return stop_with_recovery_required(plan, lease, error.message);
        }
    }
    let restored = match observe_exact_transitions(
        workspace_root,
        &journal.transitions,
        lease_id.clone(),
        Some(journal.mutation_attempt_id),
    ) {
        Ok(observations) => observations,
        Err(error) => return stop_with_recovery_required(plan, lease, error.message),
    };
    if !restored
        .iter()
        .zip(&journal.transitions)
        .all(|(observation, transition)| transition.matches_pre(observation))
    {
        return stop_with_recovery_required(
            plan,
            lease,
            "Recovery writes completed but exact observation did not prove every preimage.",
        );
    }
    if let Err(error) = refresh_restored_preimages(
        workspace_root,
        &journal.transitions,
        lease_id.clone(),
    ) {
        return stop_with_recovery_required(plan, lease, error.message);
    }
    let terminal_scratch = match inspect_mutation_scratch(
        workspace_root,
        journal,
        lease_id,
    ) {
        Ok(inspection) => inspection,
        Err(error) => return stop_with_recovery_required(plan, lease, error.message),
    };
    if !journal.owned_scratch.is_empty()
        || terminal_scratch.has_blocker()
        || terminal_scratch.owned_present()
    {
        return stop_with_recovery_required(
            plan,
            lease,
            "Restored source proof found unresolved mutation scratch.",
        );
    }
    if let Err(error) = lease.release() {
        return print_recovery_required(
            plan,
            format!("Recovery restored source state but could not release its lease: {}.", error.message),
        );
    }
    let receipt = TerminalMutationReceipt::rolled_back(
        plan,
        "Recovery restored every exact source preimage in reverse transition order.",
    );
    finish_terminal_receipt(paths, plan, receipt)
}

fn restore_exact_transition(
    workspace_root: &Path,
    transition: &ExactMutationTransition,
    lease_id: AgentWorkspaceLeaseId,
    mutation_attempt_id: Uuid,
    scratch: &MutationScratchAuthority,
) -> Result<()> {
    match &transition.preimage {
        ExactMutationPreimage::Present { image } => {
            let request = AgentExactFileImageCasRequest::restore(
                transition.absolute_path.clone(),
                image,
                &transition.postimage,
            )
            .for_attempt(mutation_attempt_id, scratch.wire_set());
            let raw = execute_leased_raw_value(
                workspace_root,
                lease_id,
                "raw/exact-file-image-cas",
                serde_json::to_value(&request)?,
                LeasedRawOperation::ExactFileImageCas,
            )?;
            let response: AgentExactFileImageCasResponse =
                parse_closed_raw(raw, "recovery exact-file CAS")?;
            response.validate_for(&request).map_err(|message| {
                CliError::new("KAST_EXACT_FILE_CAS_INVALID", message)
            })
        }
        ExactMutationPreimage::Absent => {
            let raw = execute_leased_raw_value(
                workspace_root,
                lease_id,
                "raw/apply-edits",
                json!({
                    "mutationAttemptId": mutation_attempt_id.hyphenated().to_string(),
                    "edits": [],
                    "fileHashes": [],
                    "mutationScratchSets": [scratch.wire_set()],
                    "fileOperations": [{
                        "type": "DELETE_FILE",
                        "filePath": &transition.absolute_path,
                        "expectedHash": transition.postimage.sha256(),
                    }],
                }),
                LeasedRawOperation::FileOperation,
            )?;
            let result: RawApplyEditsResult = parse_closed_raw(raw, "recovery add-file delete")?;
            if result.schema_version != crate::SCHEMA_VERSION
                || !result.applied.is_empty()
                || result.affected_files != [transition.absolute_path.clone()]
                || !result.created_files.is_empty()
                || result.deleted_files != [transition.absolute_path.clone()]
            {
                return Err(CliError::new(
                    "KAST_ADD_FILE_DELETE_INVALID",
                    "The raw recovery delete did not bind one exact postimage path.",
                ));
            }
            Ok(())
        }
    }
}

fn require_current_workspace(plan: &StoredPlan, plan_id: Uuid) -> Result<PathBuf> {
    let workspace_root = canonical_workspace_root()?;
    if plan.workspace_root != workspace_root.display().to_string() {
        return Err(CliError::new(
            "KAST_PLAN_WORKSPACE_MISMATCH",
            format!(
                "Plan {plan_id} belongs to {}, not {}.",
                plan.workspace_root,
                workspace_root.display()
            ),
        ));
    }
    Ok(workspace_root)
}

fn parse_recovery_id(raw: &str) -> Result<Uuid> {
    Uuid::parse_str(raw)
        .ok()
        .filter(|id| id.get_version() == Some(Version::Random))
        .filter(|id| id.hyphenated().to_string() == raw)
        .ok_or_else(|| {
            CliError::new(
                "CLI_USAGE",
                "Recovery ids must be canonical lowercase version-4 UUIDs returned by `kast apply`.",
            )
        })
}

fn finish_terminal_receipt(
    paths: &PlanPaths,
    plan: &mut StoredPlan,
    receipt: TerminalMutationReceipt,
) -> Result<i32> {
    receipt.validate_for(plan)?;
    if paths.recovery.exists() {
        let journal = read_recovery(&paths.recovery, plan.plan_id, plan)?;
        if !journal.owned_scratch.is_empty() {
            return print_recovery_required(
                plan,
                "A terminal receipt is forbidden while journal-owned mutation scratch remains unresolved.",
            );
        }
    }
    plan.state = StoredPlanState::Terminal {
        receipt: Box::new(receipt.clone()),
    };
    let persistence = if terminal_receipt_persistence_failure_active() {
        Err(CliError::new(
            "KAST_TEST_TERMINAL_RECEIPT_PERSISTENCE_FAILED",
            "Terminal receipt persistence failed at the deterministic test seam.",
        ))
    } else {
        replace_plan(&paths.plan, plan)
    };
    if let Err(error) = persistence {
        if paths.recovery.exists() {
            return print_recovery_required(
                plan,
                format!(
                    "Terminal source evidence is retained in the durable recovery journal, but the terminal receipt could not be persisted: {}.",
                    error.message
                ),
            );
        }
        return Err(error);
    }
    print_terminal_receipt(&receipt)
}

fn terminal_receipt_persistence_failure_active() -> bool {
    cfg!(debug_assertions)
        && std::env::var("KAST_TEST_MUTATION_FAILURE_POINT")
            .is_ok_and(|value| value == "TERMINAL_RECEIPT_PERSISTENCE")
}

fn print_terminal_receipt(receipt: &TerminalMutationReceipt) -> Result<i32> {
    output::print_structured(receipt, crate::cli::OutputFormat::Toon)?;
    Ok(receipt.exit_code())
}

fn print_recovery_required(plan: &StoredPlan, reason: impl Into<String>) -> Result<i32> {
    let receipt = RecoveryRequiredReceipt::new(plan, reason);
    output::print_structured(&receipt, crate::cli::OutputFormat::Toon)?;
    Ok(1)
}

#[cfg(test)]
mod recovery_classification_tests {
    use super::*;

    fn transition(path: &str, pre: &[u8], post: &[u8]) -> ExactMutationTransition {
        ExactMutationTransition {
            relative_path: format!("src/{path}.kt"),
            absolute_path: format!("/workspace/src/{path}.kt"),
            preimage: ExactMutationPreimage::Present {
                image: AgentExactByteImage::from_bytes(pre),
            },
            postimage: AgentExactByteImage::from_bytes(post),
        }
    }

    fn present(path: &str, bytes: &[u8]) -> RawExactFileObservation {
        RawExactFileObservation::Present {
            file_path: format!("src/{path}.kt"),
            image: AgentExactByteImage::from_bytes(bytes),
        }
    }

    #[test]
    fn recovery_classification_is_closed_over_all_pre_post_mixed_and_foreign() {
        let transitions = [
            transition("A", b"pre-a", b"post-a"),
            transition("B", b"pre-b", b"post-b"),
        ];
        assert_eq!(
            classify_recovery_observations(
                &transitions,
                &[present("A", b"pre-a"), present("B", b"pre-b")],
            ),
            RecoveryObservationClass::AllPre
        );
        assert_eq!(
            classify_recovery_observations(
                &transitions,
                &[present("A", b"post-a"), present("B", b"post-b")],
            ),
            RecoveryObservationClass::AllPost
        );
        assert_eq!(
            classify_recovery_observations(
                &transitions,
                &[present("A", b"post-a"), present("B", b"pre-b")],
            ),
            RecoveryObservationClass::Mixed
        );
        assert_eq!(
            classify_recovery_observations(
                &transitions,
                &[present("A", b"foreign"), present("B", b"pre-b")],
            ),
            RecoveryObservationClass::Foreign
        );
    }
}
