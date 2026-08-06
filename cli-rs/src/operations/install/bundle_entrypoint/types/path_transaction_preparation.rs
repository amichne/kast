impl PathProjectionTransaction {
    fn prepare(install_root: &Path, durable: DurablePathProjectionTransaction) -> Result<Self> {
        let journal_path = install_root.join(PATH_PROJECTION_TRANSACTION_FILE);
        if fs::symlink_metadata(&journal_path).is_ok() {
            return Err(CliError::new(
                "PATH_PROJECTION_TRANSACTION_EXISTS",
                format!(
                    "Unrecovered PATH projection transaction exists at {}.",
                    journal_path.display(),
                ),
            ));
        }
        write_projection_transaction_create_new(&journal_path, &durable)?;
        Ok(Self {
            journal_path,
            durable,
        })
    }

    fn apply(&mut self) -> Result<()> {
        match self.durable.mutation.clone() {
            DurablePathProjectionMutation::CreatePrepared { temporary_path } => {
                let temporary_path = PathBuf::from(temporary_path);
                require_path_absent(&temporary_path, "PATH projection temporary path")?;
                test_path_projection_crash("after-control-create-prepare");
                std::os::unix::fs::symlink(
                    Path::new(&self.durable.control_target),
                    &temporary_path,
                )?;
                sync_projection_parent_after(
                    &temporary_path,
                    "after-control-temporary-create-before-parent-sync",
                )?;
                let projected_identity = projection_file_identity(&temporary_path)?;
                self.durable.mutation = DurablePathProjectionMutation::CreateMaterialized {
                    temporary_path: temporary_path.display().to_string(),
                    projected_identity,
                };
                test_path_projection_barrier("before-control-identity-journal-write")?;
                write_projection_transaction_atomic(&self.journal_path, &self.durable)?;
                test_path_projection_crash("after-control-temporary-create");
                self.apply_materialized_create(&temporary_path, projected_identity)
            }
            DurablePathProjectionMutation::CreateMaterialized {
                temporary_path,
                projected_identity,
            } => self.apply_materialized_create(Path::new(&temporary_path), projected_identity),
            DurablePathProjectionMutation::ReplacePrepared {
                temporary_path,
                prior_target,
                prior_identity,
            } => {
                let temporary_path = PathBuf::from(temporary_path);
                require_owned_projection_unchanged(
                    Path::new(&self.durable.control_path),
                    Path::new(&prior_target),
                    prior_identity,
                )?;
                require_path_absent(&temporary_path, "PATH projection replacement path")?;
                test_path_projection_crash("after-control-replace-prepare");
                std::os::unix::fs::symlink(
                    Path::new(&self.durable.control_target),
                    &temporary_path,
                )?;
                sync_projection_parent_after(
                    &temporary_path,
                    "after-control-replacement-create-before-parent-sync",
                )?;
                let projected_identity = projection_file_identity(&temporary_path)?;
                self.durable.mutation = DurablePathProjectionMutation::ReplaceMaterialized {
                    temporary_path: temporary_path.display().to_string(),
                    projected_identity,
                    prior_target,
                    prior_identity,
                };
                write_projection_transaction_atomic(&self.journal_path, &self.durable)?;
                self.apply_materialized_replace()
            }
            DurablePathProjectionMutation::ReplaceMaterialized { .. } => {
                self.apply_materialized_replace()
            }
            DurablePathProjectionMutation::Remove {
                quarantine_path,
                prior_target,
                prior_identity,
            } => {
                let control_path = Path::new(&self.durable.control_path);
                let quarantine_path = PathBuf::from(quarantine_path);
                require_owned_projection_unchanged(
                    control_path,
                    Path::new(&prior_target),
                    prior_identity,
                )?;
                IdentityTransactionalMove::new(
                    control_path,
                    &quarantine_path,
                    prior_identity,
                    "receipt-owned control projection selected for removal",
                )
                .with_after_validation_barrier("before-control-remove")
                .execute()
                .map_err(|error| {
                    let mut changed = projection_changed_error(
                        control_path,
                        format!(
                            "the path changed before its receipt-owned entry could be removed: {error}",
                        ),
                    );
                    changed.details.extend(
                        error
                            .details
                            .into_iter()
                            .map(|(key, value)| (format!("move{key}"), value)),
                    );
                    changed
                })?;
                require_owned_projection_unchanged(
                    &quarantine_path,
                    Path::new(&prior_target),
                    prior_identity,
                )?;
                sync_projection_parent_after(
                    control_path,
                    "after-control-remove-before-parent-sync",
                )
            }
        }
    }

    fn apply_materialized_create(
        &self,
        temporary_path: &Path,
        projected_identity: ProjectionFileIdentity,
    ) -> Result<()> {
        let control_path = Path::new(&self.durable.control_path);
        IdentityTransactionalMove::new(
            temporary_path,
            control_path,
            projected_identity,
            "materialized control projection",
        )
        .with_after_validation_barrier("before-control-create")
        .execute()
        .map_err(|error| {
            let mut changed = projection_changed_error(
                control_path,
                format!("could not create the projection without replacement: {error}"),
            );
            changed.details.extend(
                error
                    .details
                    .into_iter()
                    .map(|(key, value)| (format!("move{key}"), value)),
            );
            changed
        })?;
        sync_projection_parent_after(control_path, "after-control-create-before-parent-sync")
    }

    fn apply_materialized_replace(&self) -> Result<()> {
        let DurablePathProjectionMutation::ReplaceMaterialized {
            temporary_path,
            projected_identity,
            prior_target,
            prior_identity,
        } = &self.durable.mutation
        else {
            return Err(CliError::new(
                "PATH_PROJECTION_TRANSACTION_INVALID",
                "Control replacement is not materialized.",
            ));
        };
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
                test_path_projection_barrier("before-control-replace")?;
                exchange_control_projection(
                    control_path,
                    temporary_path,
                    Path::new(prior_target),
                    *prior_identity,
                    Path::new(&self.durable.control_target),
                    *projected_identity,
                    ControlReplacementState::DesiredPublished,
                    "after-control-replace-before-parent-sync",
                )
            }
            ControlReplacementState::DesiredPublished => sync_projection_parent(control_path),
        }
    }
}
