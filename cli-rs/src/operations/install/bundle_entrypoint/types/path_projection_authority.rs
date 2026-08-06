impl PathProjectionAuthority {
    fn capture(targets: &ActivationTargetPaths) -> Result<Self> {
        let control_path = manifest::home_dir().join(".local/bin/kastctl");
        let desired_target = targets.resolved.active_binary.clone();
        let receipt = authority_manifest_from_file(
            &targets.current_link.join(manifest::INSTALL_MANIFEST_FILE),
        )
        .ok();
        let receipt_owned =
            validated_receipt_owned_control_projection(receipt.as_ref(), targets, &control_path);
        let receipt_relinquishes_control = receipt.as_ref().is_some_and(|receipt| {
            receipt_explicitly_relinquishes_control(receipt, targets, &control_path)
        });
        let force_reset_journal = load_force_reset_path_authority(targets, &control_path)?;
        let state = match fs::symlink_metadata(&control_path) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                ExistingControlProjection::Absent
            }
            Err(error) => return Err(error.into()),
            Ok(_) if receipt_owned.is_some() => ExistingControlProjection::ReceiptOwned(
                receipt_owned.expect("receipt-owned state was checked"),
            ),
            Ok(_) if receipt_relinquishes_control => ExistingControlProjection::Unmanaged,
            Ok(_) => match force_reset_journal.as_ref() {
                Some(journal) => match require_owned_projection_unchanged(
                    &control_path,
                    &journal.owned.target,
                    journal.owned.identity,
                ) {
                    Ok(()) => ExistingControlProjection::ReceiptOwned(journal.owned.clone()),
                    Err(error) => ExistingControlProjection::ForceResetAuthorityChanged(
                        ChangedForceResetControlProjection {
                            authority_path: journal.path.clone(),
                            validation_error: error.to_string(),
                        },
                    ),
                },
                None => ExistingControlProjection::Unmanaged,
            },
        };
        Ok(Self {
            control_path,
            expected_target: desired_target,
            state,
            force_reset_journal,
        })
    }

    fn preserve_for_force_reset(&mut self, install_root: &Path) -> Result<()> {
        let owned = match &self.state {
            ExistingControlProjection::ReceiptOwned(owned) => owned.clone(),
            ExistingControlProjection::Absent
            | ExistingControlProjection::ForceResetAuthorityChanged(_)
            | ExistingControlProjection::Unmanaged => {
                return Ok(());
            }
        };
        require_owned_projection_unchanged(&self.control_path, &owned.target, owned.identity)?;
        if config::normalize(owned.target.clone())
            != config::normalize(self.expected_target.clone())
        {
            return Err(CliError::new(
                "FORCE_RESET_PATH_AUTHORITY_TARGET_UNSUPPORTED",
                format!(
                    "Forced setup cannot preserve control authority from {} for candidate target {}.",
                    owned.target.display(),
                    self.expected_target.display(),
                ),
            ));
        }
        if self
            .force_reset_journal
            .as_ref()
            .is_some_and(|journal| journal.owned == owned)
        {
            return sync_projection_parent(
                &self
                    .force_reset_journal
                    .as_ref()
                    .expect("matching force-reset journal was checked")
                    .path,
            );
        }
        self.remove_force_reset_journal()?;
        self.force_reset_journal = Some(write_force_reset_path_authority_create_new(
            install_root,
            &self.control_path,
            &owned,
        )?);
        Ok(())
    }

    fn complete_force_reset_recovery(&mut self) -> Result<()> {
        self.remove_force_reset_journal()
    }

    fn remove_force_reset_journal(&mut self) -> Result<()> {
        let Some(journal) = self.force_reset_journal.take() else {
            return Ok(());
        };
        if let Err(error) = remove_internal_projection_path(
            &journal.path,
            Some(journal.identity),
            "after-force-authority-cleanup-before-parent-sync",
        ) {
            self.force_reset_journal = Some(journal);
            return Err(error);
        }
        Ok(())
    }

    fn require_profile(&self, profile: manifest::SetupProfile) -> Result<()> {
        if profile.projects_control_command() {
            match &self.state {
                ExistingControlProjection::ForceResetAuthorityChanged(changed) => {
                    return Err(force_reset_path_authority_changed(
                        &self.control_path,
                        &changed.authority_path,
                        &changed.validation_error,
                    ));
                }
                ExistingControlProjection::Unmanaged => {
                    let mut error = CliError::new(
                        "PATH_PROJECTION_UNMANAGED",
                        format!(
                            "Development setup cannot replace unmanaged command {}.",
                            self.control_path.display(),
                        ),
                    );
                    error
                        .details
                        .insert("path".to_string(), self.control_path.display().to_string());
                    error
                        .details
                        .insert("setupProfile".to_string(), "DEVELOPMENT".to_string());
                    return Err(error);
                }
                ExistingControlProjection::Absent | ExistingControlProjection::ReceiptOwned(_) => {}
            }
        }
        Ok(())
    }

    fn control_target(&self) -> &Path {
        &self.expected_target
    }

    fn prior_setup_profile(&self) -> manifest::SetupProfile {
        match self.state {
            ExistingControlProjection::ReceiptOwned(_) => manifest::SetupProfile::Development,
            ExistingControlProjection::Absent
            | ExistingControlProjection::ForceResetAuthorityChanged(_)
            | ExistingControlProjection::Unmanaged => manifest::SetupProfile::Standard,
        }
    }

    fn carry_prior_ownership_into(&self, receipt: &mut manifest::KastInstallManifest) {
        receipt.setup_profile = self.prior_setup_profile();
        receipt
            .path_projections
            .retain(|projection| projection.command != manifest::PathProjectionCommand::Kastctl);
        receipt
            .owned_paths
            .retain(|path| Path::new(path) != self.control_path);
        if matches!(self.state, ExistingControlProjection::ReceiptOwned(_)) {
            let ExistingControlProjection::ReceiptOwned(owned) = &self.state else {
                unreachable!("receipt-owned state was already established")
            };
            receipt
                .owned_paths
                .push(self.control_path.display().to_string());
            receipt
                .path_projections
                .push(manifest::PathProjectionReceipt {
                    command: manifest::PathProjectionCommand::Kastctl,
                    path: self.control_path.display().to_string(),
                    target: owned.target.display().to_string(),
                });
        }
    }

    fn begin_transaction(
        &self,
        profile: manifest::SetupProfile,
        receipt_path: &Path,
        release_digest: &str,
        install_root: &Path,
    ) -> Result<Option<PathProjectionTransaction>> {
        match (&self.state, profile.projects_control_command()) {
            (ExistingControlProjection::Unmanaged, false)
            | (ExistingControlProjection::ForceResetAuthorityChanged(_), false)
            | (ExistingControlProjection::Absent, false) => Ok(None),
            (ExistingControlProjection::Unmanaged, true)
            | (ExistingControlProjection::ForceResetAuthorityChanged(_), true) => {
                self.require_profile(profile)?;
                Ok(None)
            }
            (ExistingControlProjection::Absent, true) => {
                let transaction_nonce = new_projection_transaction_nonce();
                let temporary_path =
                    internal_projection_path(&self.control_path, "create", &transaction_nonce);
                PathProjectionTransaction::prepare(
                    install_root,
                    DurablePathProjectionTransaction {
                        schema_version: PATH_PROJECTION_TRANSACTION_SCHEMA_VERSION,
                        control_path: self.control_path.display().to_string(),
                        control_target: self.expected_target.display().to_string(),
                        receipt_path: receipt_path.display().to_string(),
                        release_digest: release_digest.to_string(),
                        intended_profile: profile,
                        transaction_nonce,
                        mutation: DurablePathProjectionMutation::CreatePrepared {
                            temporary_path: temporary_path.display().to_string(),
                        },
                    },
                )
                .map(Some)
            }
            (ExistingControlProjection::ReceiptOwned(owned), true) => {
                require_owned_projection_unchanged(
                    &self.control_path,
                    &owned.target,
                    owned.identity,
                )?;
                if config::normalize(owned.target.clone())
                    == config::normalize(self.expected_target.clone())
                {
                    return Ok(None);
                }
                let transaction_nonce = new_projection_transaction_nonce();
                let temporary_path =
                    internal_projection_path(&self.control_path, "replace", &transaction_nonce);
                PathProjectionTransaction::prepare(
                    install_root,
                    DurablePathProjectionTransaction {
                        schema_version: PATH_PROJECTION_TRANSACTION_SCHEMA_VERSION,
                        control_path: self.control_path.display().to_string(),
                        control_target: self.expected_target.display().to_string(),
                        receipt_path: receipt_path.display().to_string(),
                        release_digest: release_digest.to_string(),
                        intended_profile: profile,
                        transaction_nonce,
                        mutation: DurablePathProjectionMutation::ReplacePrepared {
                            temporary_path: temporary_path.display().to_string(),
                            prior_target: owned.target.display().to_string(),
                            prior_identity: owned.identity,
                        },
                    },
                )
                .map(Some)
            }
            (ExistingControlProjection::ReceiptOwned(owned), false) => {
                let transaction_nonce = new_projection_transaction_nonce();
                let quarantine_path =
                    internal_projection_path(&self.control_path, "remove", &transaction_nonce);
                PathProjectionTransaction::prepare(
                    install_root,
                    DurablePathProjectionTransaction {
                        schema_version: PATH_PROJECTION_TRANSACTION_SCHEMA_VERSION,
                        control_path: self.control_path.display().to_string(),
                        control_target: self.expected_target.display().to_string(),
                        receipt_path: receipt_path.display().to_string(),
                        release_digest: release_digest.to_string(),
                        intended_profile: profile,
                        transaction_nonce,
                        mutation: DurablePathProjectionMutation::Remove {
                            quarantine_path: quarantine_path.display().to_string(),
                            prior_target: owned.target.display().to_string(),
                            prior_identity: owned.identity,
                        },
                    },
                )
                .map(Some)
            }
        }
    }
}
