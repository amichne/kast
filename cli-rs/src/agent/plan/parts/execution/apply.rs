fn run_apply_typed(
    plan_id: crate::agent::public_protocol::PlanId,
    output_format: OutputFormat,
) -> Result<i32> {
    let plan_id = plan_id.uuid();
    let paths = PlanPaths::new(plan_id);
    let _operation_lock = PlanOperationLock::acquire(&paths.lock)?;
    let mut plan = read_plan(&paths.plan, plan_id)?;
    plan.set_runtime_output(
        output_format,
        crate::agent::public_protocol::OperationId::ChangeApply,
    );
    let workspace_root = require_current_workspace(&plan, plan_id)?;
    if let StoredPlanState::Terminal { receipt } = &plan.state {
        return replay_terminal_receipt(&paths, &plan, receipt);
    }
    if recovery_namespace_may_be_occupied(&paths.recovery) {
        read_recovery(&paths.recovery, plan_id, &plan)?;
        return print_recovery_required(
            &plan,
            "This plan already has a durable recovery journal; use `kast change recover --recovery-id <RECOVERY_ID>` before retrying apply.",
        );
    }

    let content = load_persisted_plan_content(&plan, &paths)?;
    let lease = OwnedMutationLease::acquire(plan.plan_id, &workspace_root)?;
    let lease_id = lease.id();
    let prewrite: Result<_> = (|| {
        let transitions = plan.operation.transitions(&workspace_root)?;
        let observations = observe_exact_transitions(
            &workspace_root,
            &transitions,
            lease_id.clone(),
            None,
        )?;
        if !observations
            .iter()
            .zip(&transitions)
            .all(|(observation, transition)| transition.matches_pre(observation))
        {
            return Ok(Err(transitions));
        }
        revalidate_persisted_authority(
            &plan,
            content.as_deref(),
            &workspace_root,
            lease_id.clone(),
        )?;
        let pre_files = transitions
            .iter()
            .filter_map(|transition| match &transition.preimage {
                ExactMutationPreimage::Absent => None,
                ExactMutationPreimage::Present { image } => Some(CompilerDiagnosticFileHash {
                    file_path: transition.absolute_path.clone(),
                    sha256: image.sha256().to_string(),
                }),
            })
            .collect::<Vec<_>>();
        let pre_diagnostics = collect_complete_diagnostics(
            &workspace_root,
            &pre_files,
            lease_id.clone(),
        )?;
        Ok(Ok((transitions, pre_diagnostics)))
    })();
    let (transitions, pre_diagnostics) = match prewrite {
        Ok(Ok(value)) => value,
        Ok(Err(_)) => {
            if let Err(error) = lease.release() {
                return Err(prewrite_release_error(
                    "Apply observed a source conflict",
                    &error,
                ));
            }
            let receipt = TerminalMutationReceipt::conflicted(
                &plan,
                "At least one source path no longer matches its exact planned preimage.",
            );
            return finish_terminal_receipt(&paths, &mut plan, receipt);
        }
        Err(error) => {
            if let Err(release_error) = lease.release() {
                return Err(prewrite_release_error(&error.message, &release_error));
            }
            if is_definitive_revalidation_rejection(&error) {
                let receipt = TerminalMutationReceipt::rejected(&plan, error.message);
                return finish_terminal_receipt(&paths, &mut plan, receipt);
            }
            return Err(error);
        }
    };
    if MutationFailurePoint::BeforeJournal.active() {
        return fail_before_recovery_journal(lease, CliError::new(
            "KAST_TEST_MUTATION_INTERRUPTED",
            "Apply stopped before its recovery journal became durable.",
        ));
    }
    let mut journal = RecoveryJournal::prepared(&plan, transitions, pre_diagnostics)?;
    if let Err(error) = write_initial_recovery(&paths.recovery, &journal) {
        if recovery_namespace_may_be_occupied(&paths.recovery) {
            return stop_with_recovery_required(
                &plan,
                lease,
                format!("A recovery journal namespace entry exists after persistence failed: {}", error.message),
            );
        }
        return fail_before_recovery_journal(lease, error);
    }
    if MutationFailurePoint::AfterJournal.active() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Apply stopped after its recovery journal became durable.",
        );
    }
    let initial_scratch = match inspect_mutation_scratch(
        &workspace_root,
        &journal,
        lease_id.clone(),
    ) {
        Ok(result) => result,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if initial_scratch.has_blocker() || initial_scratch.owned_present() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Mutation scratch admission did not prove every predeclared role absent and safe.",
        );
    }
    let admitted_observations = match observe_exact_transitions(
        &workspace_root,
        &journal.transitions,
        lease_id.clone(),
        Some(journal.mutation_attempt_id),
    ) {
        Ok(observations) => observations,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if !admitted_observations
        .iter()
        .zip(&journal.transitions)
        .all(|(observation, transition)| transition.matches_pre(observation))
    {
        return stop_with_recovery_required(
            &plan,
            lease,
            "The exact source preimage changed after the mutation attempt became durable.",
        );
    }

    for index in 0..journal.transitions.len() {
        let scratch = match journal.active_scratch(index) {
            Ok(scratch) => scratch.clone(),
            Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
        };
        if let Err(error) = apply_exact_transition(
            &workspace_root,
            &journal.transitions[index],
            lease_id.clone(),
            journal.mutation_attempt_id,
            &scratch,
        ) {
            let detail_failure = journal
                .validate_backend_recovery_details(&error)
                .err()
                .map(|failure| failure.message);
            let mut reason = format!(
                "An exact transition may have written before it failed: {}.",
                error.message
            );
            if let Some(failure) = detail_failure {
                reason.push_str(" Its backend recovery path details were invalid: ");
                reason.push_str(&failure);
                reason.push('.');
            }
            if let Ok(inspection) =
                inspect_mutation_scratch(&workspace_root, &journal, lease_id.clone())
                && (inspection.has_blocker() || inspection.owned_present())
            {
                reason.push_str(" Gated inspection found retained mutation scratch.");
            }
            return stop_with_recovery_required(&plan, lease, reason);
        }
        let inspection = match inspect_mutation_scratch(
            &workspace_root,
            &journal,
            lease_id.clone(),
        ) {
            Ok(result) => result,
            Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
        };
        if inspection.has_blocker()
            || inspection.owned_present()
            || !inspection.scratch_is_absent(&scratch)
        {
            return stop_with_recovery_required(
                &plan,
                lease,
                "A completed exact write did not leave every owned scratch role absent and safe.",
            );
        }
        if let Err(error) = journal.remove_scratch(&scratch) {
            return stop_with_recovery_required(&plan, lease, error.message);
        }
        journal.state = RecoveryJournalState::Writing {
            completed_transition_count: index + 1,
        };
        if let Err(error) = replace_recovery(&paths.recovery, &journal) {
            return stop_with_recovery_required(
                &plan,
                lease,
                format!("A completed write could not be checkpointed: {}.", error.message),
            );
        }
        if MutationFailurePoint::AfterWrite(index + 1).active() {
            return stop_with_recovery_required(
                &plan,
                lease,
                format!("Apply stopped after exact transition {}.", index + 1),
            );
        }
    }
    journal.state = RecoveryJournalState::WritesApplied;
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterAllWrites.active() {
        return stop_with_recovery_required(&plan, lease, "Apply stopped after all exact writes.");
    }
    let post_observations = match observe_exact_transitions(
        &workspace_root,
        &journal.transitions,
        lease_id.clone(),
        Some(journal.mutation_attempt_id),
    ) {
        Ok(observations) => observations,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    if !post_observations
        .iter()
        .zip(&journal.transitions)
        .all(|(observation, transition)| transition.matches_post(observation))
    {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Exact observation did not prove every planned postimage after writes.",
        );
    }

    let refresh = match refresh_exact_transitions(
        &workspace_root,
        &journal.transitions,
        lease_id.clone(),
    ) {
        Ok(refresh) => refresh,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    journal.state = RecoveryJournalState::Refreshed;
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterRefresh.active() {
        return stop_with_recovery_required(&plan, lease, "Apply stopped after compiler refresh.");
    }

    let post_files = journal
        .transitions
        .iter()
        .map(|transition| CompilerDiagnosticFileHash {
            file_path: transition.absolute_path.clone(),
            sha256: transition.postimage.sha256().to_string(),
        })
        .collect::<Vec<_>>();
    let post_diagnostics = match collect_complete_diagnostics(
        &workspace_root,
        &post_files,
        lease_id.clone(),
    ) {
        Ok(diagnostics) => diagnostics,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    let deltas = match compare_diagnostic_snapshots(&journal.pre_diagnostics, &post_diagnostics) {
        Ok(deltas) => deltas,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
    };
    journal.state = RecoveryJournalState::DiagnosticsVerified;
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterDiagnostics.active() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Apply stopped after exact diagnostic comparison.",
        );
    }

    let semantic_postcondition = match verify_mutation_postcondition(
        &workspace_root,
        &plan.operation,
        &journal.transitions,
        lease_id.clone(),
    ) {
        Ok(evidence) => evidence,
        Err(error) => return stop_with_recovery_required(&plan, lease, error.message),
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
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterSemanticVerification.active() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Apply stopped after compiler-backed semantic verification.",
        );
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
            "Terminal verification found unresolved owned, unowned, or unsafe mutation scratch.",
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
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return stop_with_recovery_required(&plan, lease, error.message);
    }
    if MutationFailurePoint::AfterDurableEvidence.active() {
        return stop_with_recovery_required(
            &plan,
            lease,
            "Apply stopped after verified evidence became durable.",
        );
    }
    let lease_receipt = match lease.release() {
        Ok(receipt) => receipt,
        Err(error) => {
            return print_recovery_required(
                &plan,
                format!("Apply verified source state but could not release its lease: {}.", error.message),
            );
        }
    };
    journal.state = RecoveryJournalState::VerifiedEvidence {
        files: files.clone(),
        diagnostics,
        compiler_verification: Box::new(compiler_verification.clone()),
        lease: Box::new(lease_receipt.clone()),
    };
    if let Err(error) = replace_recovery(&paths.recovery, &journal) {
        return print_recovery_required(
            &plan,
            format!("Released verified evidence could not be made durable: {}.", error.message),
        );
    }
    let receipt = TerminalMutationReceipt::verified(
        &plan,
        files,
        diagnostics,
        compiler_verification,
        lease_receipt,
    );
    finish_terminal_receipt(&paths, &mut plan, receipt)
}
