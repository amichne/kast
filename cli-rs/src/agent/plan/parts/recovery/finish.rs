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
        lease: Box::new(lease_receipt.clone()),
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
