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
