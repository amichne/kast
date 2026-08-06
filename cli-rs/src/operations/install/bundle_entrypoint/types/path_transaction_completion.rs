impl PathProjectionTransaction {
    fn commit(self) -> Result<()> {
        match &self.durable.mutation {
            DurablePathProjectionMutation::CreatePrepared { .. } => {
                return Err(CliError::new(
                    "PATH_PROJECTION_TRANSACTION_INVALID",
                    "Prepared control projection cannot be committed before materialization.",
                ));
            }
            DurablePathProjectionMutation::CreateMaterialized {
                temporary_path,
                projected_identity,
            } => {
                require_identity(
                    Path::new(&self.durable.control_path),
                    *projected_identity,
                    "committed control projection",
                )?;
                remove_internal_projection_path(
                    Path::new(temporary_path),
                    Some(*projected_identity),
                    "after-control-create-cleanup-before-parent-sync",
                )?;
            }
            DurablePathProjectionMutation::ReplacePrepared { .. } => {
                return Err(CliError::new(
                    "PATH_PROJECTION_TRANSACTION_INVALID",
                    "Prepared control replacement cannot be committed before materialization.",
                ));
            }
            DurablePathProjectionMutation::ReplaceMaterialized {
                temporary_path,
                projected_identity,
                prior_target,
                prior_identity,
            } => {
                let control_path = Path::new(&self.durable.control_path);
                let temporary_path = Path::new(temporary_path);
                match control_replacement_state(
                    control_path,
                    temporary_path,
                    Path::new(prior_target),
                    *prior_identity,
                    Path::new(&self.durable.control_target),
                    *projected_identity,
                )? {
                    ControlReplacementState::PriorPublished => {
                        exchange_control_projection(
                            control_path,
                            temporary_path,
                            Path::new(prior_target),
                            *prior_identity,
                            Path::new(&self.durable.control_target),
                            *projected_identity,
                            ControlReplacementState::DesiredPublished,
                            "after-control-replace-before-parent-sync",
                        )?;
                    }
                    ControlReplacementState::DesiredPublished => {}
                }
                remove_internal_projection_path(
                    temporary_path,
                    Some(*prior_identity),
                    "after-control-replace-cleanup-before-parent-sync",
                )?;
            }
            DurablePathProjectionMutation::Remove {
                quarantine_path,
                prior_identity,
                ..
            } => {
                remove_internal_projection_path(
                    Path::new(quarantine_path),
                    Some(*prior_identity),
                    "after-control-cleanup-before-parent-sync",
                )?;
            }
        }
        remove_projection_transaction(&self.journal_path)
    }

    fn rollback(self) -> Result<()> {
        self.rollback_projection()?;
        remove_projection_transaction(&self.journal_path)
    }

    fn rollback_preserving_journal(self) -> Result<()> {
        self.rollback_projection()
    }

    fn rollback_projection(&self) -> Result<()> {
        test_path_projection_barrier("before-control-restore")?;
        match &self.durable.mutation {
            DurablePathProjectionMutation::CreatePrepared { temporary_path } => {
                remove_prepared_control_projection(
                    Path::new(temporary_path),
                    Path::new(&self.durable.control_target),
                )?;
            }
            DurablePathProjectionMutation::CreateMaterialized {
                temporary_path,
                projected_identity,
            } => {
                let control_path = Path::new(&self.durable.control_path);
                if projection_file_identity(control_path).ok() == Some(*projected_identity) {
                    let temporary_path = Path::new(temporary_path);
                    IdentityTransactionalMove::new(
                        control_path,
                        temporary_path,
                        *projected_identity,
                        "control projection selected for rollback",
                    )
                    .with_after_validation_barrier("after-control-create-rollback-validation")
                    .execute()?;
                    sync_projection_parent_after(
                        temporary_path,
                        "after-control-rollback-rename-before-parent-sync",
                    )?;
                }
                remove_internal_projection_path(
                    Path::new(temporary_path),
                    Some(*projected_identity),
                    "after-control-rollback-cleanup-before-parent-sync",
                )?;
            }
            DurablePathProjectionMutation::ReplacePrepared { temporary_path, .. } => {
                remove_prepared_control_projection(
                    Path::new(temporary_path),
                    Path::new(&self.durable.control_target),
                )?;
            }
            DurablePathProjectionMutation::ReplaceMaterialized {
                temporary_path,
                projected_identity,
                prior_target,
                prior_identity,
            } => {
                let control_path = Path::new(&self.durable.control_path);
                let temporary_path = Path::new(temporary_path);
                match control_replacement_state(
                    control_path,
                    temporary_path,
                    Path::new(prior_target),
                    *prior_identity,
                    Path::new(&self.durable.control_target),
                    *projected_identity,
                )? {
                    ControlReplacementState::PriorPublished => {}
                    ControlReplacementState::DesiredPublished => {
                        exchange_control_projection(
                            control_path,
                            temporary_path,
                            Path::new(prior_target),
                            *prior_identity,
                            Path::new(&self.durable.control_target),
                            *projected_identity,
                            ControlReplacementState::PriorPublished,
                            "after-control-replace-rollback-before-parent-sync",
                        )?;
                    }
                }
                remove_internal_projection_path(
                    temporary_path,
                    Some(*projected_identity),
                    "after-control-replace-rollback-cleanup-before-parent-sync",
                )?;
            }
            DurablePathProjectionMutation::Remove {
                quarantine_path,
                prior_target,
                prior_identity,
            } => {
                let control_path = Path::new(&self.durable.control_path);
                let quarantine_path = Path::new(quarantine_path);
                if projection_file_identity(quarantine_path).ok() == Some(*prior_identity) {
                    if fs::symlink_metadata(control_path).is_ok() {
                        return Err(projection_recovery_conflict(control_path, quarantine_path));
                    }
                    require_owned_projection_unchanged(
                        quarantine_path,
                        Path::new(prior_target),
                        *prior_identity,
                    )?;
                    IdentityTransactionalMove::new(
                        quarantine_path,
                        control_path,
                        *prior_identity,
                        "control quarantine selected for rollback",
                    )
                    .with_after_validation_barrier("after-control-remove-rollback-validation")
                    .execute()?;
                    require_owned_projection_unchanged(
                        control_path,
                        Path::new(prior_target),
                        *prior_identity,
                    )?;
                    sync_projection_parent_after(
                        control_path,
                        "after-control-rollback-restore-before-parent-sync",
                    )?;
                }
                require_owned_projection_unchanged(
                    control_path,
                    Path::new(prior_target),
                    *prior_identity,
                )?;
                sync_projection_parent(control_path)?;
            }
        }
        Ok(())
    }
}
