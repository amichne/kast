impl MutationScratchRecoveryResult {
    fn validate_restore(
        &self,
        journal: &RecoveryJournal,
        transition: &ExactMutationTransition,
        scratch: &MutationScratchAuthority,
    ) -> Result<()> {
        let expected_target = match &transition.preimage {
            ExactMutationPreimage::Absent => (MutationScratchTargetState::Absent, None),
            ExactMutationPreimage::Present { image } => {
                (MutationScratchTargetState::Present, Some(image.sha256()))
            }
        };
        let expected_observations = [
            (&scratch.quarantine, MutationScratchRole::Quarantine),
            (&scratch.prepared, MutationScratchRole::Prepared),
            (
                &scratch.prepared_cleanup,
                MutationScratchRole::PreparedCleanup,
            ),
            (
                &scratch.quarantine_cleanup,
                MutationScratchRole::QuarantineCleanup,
            ),
        ];
        let observations_match = self.scratch_observations.len() == expected_observations.len()
            && self
                .scratch_observations
                .iter()
                .zip(expected_observations)
                .all(|(observation, (path, role))| {
                    observation.file_path == path.absolute_path
                        && observation.ownership == MutationScratchOwnership::Owned
                        && observation.role == role
                        && observation.state == MutationScratchState::Absent
                        && observation.sha256.is_none()
                });
        if self.schema_version != crate::SCHEMA_VERSION
            || self.mutation_attempt_id != journal.mutation_attempt_id.hyphenated().to_string()
            || self.action != MutationScratchRecoveryAction::RestorePreimage
            || self.outcome != MutationScratchRecoveryOutcome::RestoredPreimage
            || self.target_state != expected_target.0
            || self.target_sha256.as_deref() != expected_target.1
            || !observations_match
        {
            return Err(CliError::new(
                "KAST_MUTATION_SCRATCH_RECOVERY_INVALID",
                "Mutation scratch recovery did not prove the exact preimage and four absent owned roles.",
            ));
        }
        Ok(())
    }
}

fn inspect_mutation_scratch(
    workspace_root: &Path,
    journal: &RecoveryJournal,
    lease_id: AgentWorkspaceLeaseId,
) -> Result<MutationScratchInspectResult> {
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/inspect-mutation-scratch",
        serde_json::to_value(journal.inspect_query()?)?,
        LeasedRawOperation::ScratchRecovery,
    )?;
    let result: MutationScratchInspectResult =
        parse_closed_raw(raw, "mutation scratch inspection")?;
    result.validate_for(journal)?;
    Ok(result)
}

fn restore_owned_mutation_scratch(
    workspace_root: &Path,
    journal: &RecoveryJournal,
    scratch: &MutationScratchAuthority,
    lease_id: AgentWorkspaceLeaseId,
) -> Result<()> {
    let transition = journal
        .transitions
        .get(scratch.transition_index)
        .ok_or_else(|| {
            CliError::new(
                "KAST_RECOVERY_INVALID",
                "Mutation scratch recovery referenced an unknown exact transition.",
            )
        })?;
    let query = MutationScratchRecoveryQuery {
        mutation_attempt_id: journal.mutation_attempt_id.hyphenated().to_string(),
        action: MutationScratchRecoveryAction::RestorePreimage,
        scratch_direction: scratch.direction,
        target_file_path: transition.absolute_path.clone(),
        preimage: transition.preimage.clone(),
        postimage: transition.postimage.clone(),
        scratch: scratch.wire_set(),
    };
    let raw = execute_leased_raw_value(
        workspace_root,
        lease_id,
        "raw/recover-mutation-scratch",
        serde_json::to_value(query)?,
        LeasedRawOperation::ScratchRecovery,
    )?;
    let result: MutationScratchRecoveryResult =
        parse_closed_raw(raw, "mutation scratch recovery")?;
    result.validate_restore(journal, transition, scratch)
}
