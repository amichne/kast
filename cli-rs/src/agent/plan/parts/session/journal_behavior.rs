impl RecoveryJournal {
    fn prepared(
        plan: &StoredPlan,
        transitions: Vec<ExactMutationTransition>,
        pre_diagnostics: CompilerDiagnosticSnapshot,
    ) -> Result<Self> {
        let root = Path::new(&plan.workspace_root);
        validate_sorted_transition_set(root, &transitions)?;
        let mutation_attempt_id = Uuid::new_v4();
        let owned_scratch = transitions
            .iter()
            .enumerate()
            .map(|(index, transition)| {
                MutationScratchAuthority::new(
                    root,
                    transition,
                    index,
                    mutation_attempt_id,
                    MutationScratchDirection::Forward,
                )
            })
            .collect::<Result<Vec<_>>>()?;
        Ok(Self {
            schema_version: RECOVERY_SCHEMA_VERSION,
            recovery_id: plan.plan_id,
            plan_id: plan.plan_id,
            workspace_root: plan.workspace_root.clone(),
            transitions,
            pre_diagnostics,
            mutation_attempt_id,
            owned_scratch,
            state: RecoveryJournalState::Prepared,
        })
    }

    fn validate(&self, recovery_id: Uuid, plan: &StoredPlan) -> Result<()> {
        if self.schema_version != RECOVERY_SCHEMA_VERSION
            || self.recovery_id != recovery_id
            || self.plan_id != plan.plan_id
            || self.workspace_root != plan.workspace_root
            || self.mutation_attempt_id.get_version() != Some(Version::Random)
        {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "The recovery journal identity does not match its change plan and exact root.",
            ));
        }
        validate_sorted_transition_set(Path::new(&self.workspace_root), &self.transitions)?;
        let expected = plan.operation.transitions(Path::new(&self.workspace_root))?;
        if self.transitions != expected {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "The recovery journal transitions do not match the persisted mutation authority.",
            ));
        }
        let expected_scratch = self
            .owned_scratch
            .iter()
            .map(|scratch| {
                let transition = self.transitions.get(scratch.transition_index).ok_or_else(|| {
                    CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "Mutation scratch referenced an unknown exact transition.",
                    )
                })?;
                MutationScratchAuthority::new(
                    Path::new(&self.workspace_root),
                    transition,
                    scratch.transition_index,
                    scratch.owner_attempt_id,
                    scratch.direction,
                )
            })
            .collect::<Result<Vec<_>>>()?;
        let mut scratch_paths = self
            .owned_scratch
            .iter()
            .flat_map(|scratch| scratch.paths())
            .map(|path| path.absolute_path.as_str())
            .collect::<Vec<_>>();
        scratch_paths.sort_unstable();
        let mut scratch_transitions = BTreeSet::new();
        if self.owned_scratch != expected_scratch
            || self.owned_scratch.iter().any(|scratch| {
                scratch.owner_attempt_id.get_version() != Some(Version::Random)
                    || !scratch_transitions.insert(scratch.transition_index)
            })
            || scratch_paths
                .windows(2)
                .any(|window| window[0] >= window[1])
        {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "The journal-owned scratch roles do not bind their exact transition images and paths.",
            ));
        }
        let expected_pre_files = self
            .transitions
            .iter()
            .filter_map(|transition| match &transition.preimage {
                ExactMutationPreimage::Absent => None,
                ExactMutationPreimage::Present { image } => Some(CompilerDiagnosticFileHash {
                    file_path: transition.absolute_path.clone(),
                    sha256: image.sha256().to_string(),
                }),
            })
            .collect::<Vec<_>>();
        self.pre_diagnostics
            .validate_for_files(&expected_pre_files)?;
        match &self.state {
            RecoveryJournalState::Writing {
                completed_transition_count,
            } if *completed_transition_count == 0
                || *completed_transition_count > self.transitions.len() =>
            {
                return Err(CliError::new(
                    "KAST_RECOVERY_INVALID",
                    "The recovery write checkpoint exceeded its exact transition set.",
                ));
            }
            RecoveryJournalState::SemanticVerified {
                compiler_verification,
            } => compiler_verification.validate_for(&plan.operation, &self.transitions)?,
            RecoveryJournalState::DurableVerifiedEvidence {
                files,
                diagnostics,
                compiler_verification,
            }
            | RecoveryJournalState::VerifiedEvidence {
                files,
                diagnostics,
                compiler_verification,
                ..
            } => {
                if !self.owned_scratch.is_empty() {
                    return Err(CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "Verified recovery evidence cannot retain journal-owned mutation scratch.",
                    ));
                }
                compiler_verification.validate_for(&plan.operation, &self.transitions)?;
                let expected_files = self
                    .transitions
                    .iter()
                    .map(ExactMutationTransition::verified_file)
                    .collect::<Vec<_>>();
                if files != &expected_files
                    || diagnostics
                        != &compiler_verification
                            .analysis
                            .post_diagnostics
                            .verified_diagnostics()
                {
                    return Err(CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "Durable verified evidence does not bind its exact transitions.",
                    ));
                }
                if let RecoveryJournalState::VerifiedEvidence { lease, .. } = &self.state
                    && lease
                        .validate_for(plan.plan_id, Path::new(&plan.workspace_root))
                        .is_err()
                {
                    return Err(CliError::new(
                        "KAST_RECOVERY_INVALID",
                        "Released verified evidence has no valid lease release receipt.",
                    ));
                }
            }
            RecoveryJournalState::Prepared
            | RecoveryJournalState::Writing { .. }
            | RecoveryJournalState::WritesApplied
            | RecoveryJournalState::Refreshed
            | RecoveryJournalState::DiagnosticsVerified => {}
        }
        Ok(())
    }

    fn validate_verified_terminal_evidence(
        &self,
        receipt: &TerminalMutationReceipt,
    ) -> Result<()> {
        let TerminalMutationReceipt::Verified {
            files,
            diagnostics,
            compiler_verification,
            lease,
            ..
        } = receipt
        else {
            return Ok(());
        };
        let RecoveryJournalState::VerifiedEvidence {
            files: journal_files,
            diagnostics: journal_diagnostics,
            compiler_verification: journal_verification,
            lease: journal_lease,
        } = &self.state
        else {
            return Err(terminal_recovery_evidence_mismatch());
        };
        if files != journal_files
            || diagnostics != journal_diagnostics
            || compiler_verification.as_ref() != journal_verification.as_ref()
            || lease != journal_lease
        {
            return Err(terminal_recovery_evidence_mismatch());
        }
        Ok(())
    }
}

fn terminal_recovery_evidence_mismatch() -> CliError {
    CliError::new(
        "KAST_TERMINAL_RECOVERY_EVIDENCE_MISMATCH",
        "The terminal plan and recovery journal do not contain the same verified source and lease evidence.",
    )
}

fn validate_sorted_transition_set(
    workspace_root: &Path,
    transitions: &[ExactMutationTransition],
) -> Result<()> {
    if !workspace_root.is_absolute() || transitions.is_empty() {
        return Err(CliError::new(
            "KAST_RECOVERY_INVALID",
            "The mutation transition set needs one exact-root transition.",
        ));
    }
    let mut previous: Option<&str> = None;
    for transition in transitions {
        let relative = Path::new(&transition.relative_path);
        let absolute = Path::new(&transition.absolute_path);
        if transition.relative_path.is_empty()
            || relative.is_absolute()
            || !relative
                .components()
                .all(|component| matches!(component, std::path::Component::Normal(_)))
            || !absolute.is_absolute()
            || !absolute.starts_with(workspace_root)
            || workspace_root.join(relative) != absolute
            || previous.is_some_and(|path| path >= transition.relative_path.as_str())
            || transition.postimage.validate().is_err()
            || matches!(
                &transition.preimage,
                ExactMutationPreimage::Present { image }
                    if image.validate().is_err() || image == &transition.postimage
            )
        {
            return Err(CliError::new(
                "KAST_RECOVERY_INVALID",
                "The mutation transition set is not unique, path-sorted, root-bound, and byte-exact.",
            ));
        }
        previous = Some(&transition.relative_path);
    }
    Ok(())
}
