#[derive(Debug, Clone)]
enum ArchivedCurrentActivation {
    Absent,
    Referenced {
        previous: PathBuf,
        backup: PathBuf,
        current: PublishedActivationProjection,
    },
    MovedRelease {
        original: PathBuf,
        backup: PathBuf,
        identity: ProjectionFileIdentity,
        current: PublishedActivationProjection,
    },
    MovedLegacyCurrent {
        original: PathBuf,
        backup: PathBuf,
        identity: ProjectionFileIdentity,
    },
}

#[derive(Debug)]
struct BundleActivationGuard<'a> {
    targets: &'a ActivationTargetPaths,
    archived: ArchivedCurrentActivation,
    archive_applied: bool,
    candidate: Option<PublishedBundleCandidate>,
    candidate_quarantine: Option<PathBuf>,
    referenced_backup: Option<PublishedActivationProjection>,
    prior_previous: Option<PublishedActivationProjection>,
    previous_publication: ProjectionPublication,
    current_publication: ProjectionPublication,
}

impl<'a> BundleActivationGuard<'a> {
    fn prepare(targets: &'a ActivationTargetPaths) -> Result<Self> {
        let backups = targets.resolved.install_root.join("backups");
        fs::create_dir_all(&backups)?;
        let prior_previous = capture_optional_activation_projection(&targets.previous_link)?;
        let archived = match fs::read_link(&targets.current_link) {
            Ok(mut previous) => {
                if previous.is_relative() {
                    previous = targets.resolved.install_root.join(previous);
                }
                let digest = previous
                    .file_name()
                    .and_then(|name| name.to_str())
                    .unwrap_or("previous")
                    .to_string();
                let current =
                    PublishedActivationProjection::capture(&targets.current_link, &previous)?;
                if previous == targets.version_dir && previous.exists() {
                    let identity = projection_file_identity(&previous)?;
                    ArchivedCurrentActivation::MovedRelease {
                        original: previous,
                        backup: unique_internal_projection_path(
                            &backups.join(format!("{digest}-replaced")),
                            "activation-archive",
                        ),
                        identity,
                        current,
                    }
                } else {
                    ArchivedCurrentActivation::Referenced {
                        previous,
                        backup: unique_internal_projection_path(
                            &backups.join(&digest),
                            "activation-backup",
                        ),
                        current,
                    }
                }
            }
            Err(read_link_error) => match fs::symlink_metadata(&targets.current_link) {
                Ok(_) => ArchivedCurrentActivation::MovedLegacyCurrent {
                    original: targets.current_link.clone(),
                    backup: unique_internal_projection_path(
                        &backups.join("legacy-current"),
                        "activation-archive",
                    ),
                    identity: projection_file_identity(&targets.current_link)?,
                },
                Err(error) if error.kind() == io::ErrorKind::NotFound => {
                    ArchivedCurrentActivation::Absent
                }
                Err(_) => return Err(read_link_error.into()),
            },
        };
        Ok(Self {
            targets,
            archived,
            archive_applied: false,
            candidate: None,
            candidate_quarantine: None,
            referenced_backup: None,
            prior_previous,
            previous_publication: ProjectionPublication::NotPublished,
            current_publication: ProjectionPublication::NotPublished,
        })
    }

    fn activate(mut self, staged: &Path) -> Result<Self> {
        let result = (|| {
            self.archive_current()?;
            test_path_projection_failure("after-current-archive")?;
            require_path_absent(
                &self.targets.version_dir,
                "bundle activation candidate destination",
            )?;
            let candidate = PublishedBundleCandidate::capture(staged)?;
            IdentityTransactionalMove::new(
                staged,
                &self.targets.version_dir,
                candidate.root_identity,
                "staged bundle candidate selected for publication",
            )
            .with_after_validation_barrier("before-bundle-candidate-publication")
            .with_after_publication_barrier(
                "after-bundle-candidate-publication-before-validation",
            )
            .execute()?;
            self.candidate = Some(candidate);
            sync_projection_move_parents(staged, &self.targets.version_dir)?;
            test_path_projection_barrier("before-current-activation-publication")?;
            self.current_publication = ProjectionPublication::Ambiguous;
            self.current_publication = ProjectionPublication::Proven(self.publish_current()?);
            if let Some(previous) = self.previous_path().map(Path::to_path_buf) {
                test_path_projection_barrier("before-previous-activation-publication")?;
                self.previous_publication = ProjectionPublication::Ambiguous;
                self.previous_publication =
                    ProjectionPublication::Proven(self.publish_previous(&previous)?);
            }
            Ok(())
        })();
        match result {
            Ok(()) => Ok(self),
            Err(error) => Err(self.rollback_into(error)),
        }
    }

    fn archive_current(&mut self) -> Result<()> {
        match &self.archived {
            ArchivedCurrentActivation::Absent => {}
            ArchivedCurrentActivation::Referenced {
                previous, backup, ..
            } => {
                self.referenced_backup = Some(PublishedActivationProjection::create_at_absent(
                    backup, previous,
                )?);
                self.archive_applied = true;
            }
            ArchivedCurrentActivation::MovedRelease {
                original,
                backup,
                identity,
                ..
            }
            | ArchivedCurrentActivation::MovedLegacyCurrent {
                original,
                backup,
                identity,
            } => {
                IdentityTransactionalMove::new(
                    original,
                    backup,
                    *identity,
                    "current activation selected for archive",
                )
                .with_after_publication_barrier(
                    "after-current-archive-publication-before-validation",
                )
                .execute()?;
                self.archive_applied = true;
                sync_projection_move_parents(original, backup)?;
            }
        }
        Ok(())
    }

    fn previous_path(&self) -> Option<&Path> {
        match &self.archived {
            ArchivedCurrentActivation::Absent => None,
            ArchivedCurrentActivation::Referenced { previous, .. } => Some(previous),
            ArchivedCurrentActivation::MovedRelease { backup, .. }
            | ArchivedCurrentActivation::MovedLegacyCurrent { backup, .. } => Some(backup),
        }
    }

    fn publish_current(&self) -> Result<PublishedActivationProjection> {
        match &self.archived {
            ArchivedCurrentActivation::Absent
            | ArchivedCurrentActivation::MovedLegacyCurrent { .. } => {
                PublishedActivationProjection::create_at_absent(
                    &self.targets.current_link,
                    &self.targets.version_dir,
                )
            }
            ArchivedCurrentActivation::Referenced { current, .. } => {
                current.replace_at(&self.targets.current_link, &self.targets.version_dir)
            }
            ArchivedCurrentActivation::MovedRelease { current, .. } => {
                current.require_at(&self.targets.current_link)?;
                Ok(current.clone())
            }
        }
    }

    fn publish_previous(&self, target: &Path) -> Result<PublishedActivationProjection> {
        match &self.prior_previous {
            Some(previous) => previous.replace_at(&self.targets.previous_link, target),
            None => PublishedActivationProjection::create_at_absent(
                &self.targets.previous_link,
                target,
            ),
        }
    }

    fn backup_path(&self) -> Option<&Path> {
        match &self.archived {
            ArchivedCurrentActivation::Absent => None,
            ArchivedCurrentActivation::Referenced { backup, .. } => self
                .referenced_backup
                .as_ref()
                .map(|_| backup.as_path()),
            ArchivedCurrentActivation::MovedRelease { backup, .. }
            | ArchivedCurrentActivation::MovedLegacyCurrent { backup, .. } => Some(backup),
        }
    }

    fn rollback_into(mut self, mut error: CliError) -> CliError {
        if let Err(rollback_error) = self.rollback() {
            error.details.insert(
                "bundleRollbackError".to_string(),
                rollback_error.to_string(),
            );
        }
        if let Some(quarantine) = &self.candidate_quarantine {
            error.details.insert(
                "candidateQuarantine".to_string(),
                quarantine.display().to_string(),
            );
        }
        error
    }

    fn restore_previous(&self) -> Result<()> {
        let Some(published) = self
            .previous_publication
            .proven_at(&self.targets.previous_link)?
        else {
            return Ok(());
        };
        match &self.prior_previous {
            Some(previous) => published
                .replace_at(&self.targets.previous_link, &previous.target)
                .map(|_| ()),
            None => published.remove_at(&self.targets.previous_link),
        }
    }

    fn rollback(&mut self) -> Result<()> {
        let current = self
            .current_publication
            .proven_at(&self.targets.current_link)?;
        match self.archived.clone() {
            ArchivedCurrentActivation::Absent => {
                if let Some(current) = current {
                    current.remove_at(&self.targets.current_link)?;
                }
                self.quarantine_candidate()
            }
            ArchivedCurrentActivation::Referenced { previous, .. } => {
                if let Some(current) = current {
                    current.replace_at(&self.targets.current_link, &previous)?;
                }
                self.quarantine_candidate()?;
                self.restore_previous()
            }
            ArchivedCurrentActivation::MovedRelease {
                original,
                backup,
                identity,
                ..
            } => {
                self.quarantine_candidate()?;
                if self.archive_applied {
                    require_path_absent(&original, "archived release restoration path")?;
                    IdentityTransactionalMove::new(
                        &backup,
                        &original,
                        identity,
                        "archived release selected for restoration",
                    )
                    .execute()?;
                    sync_projection_move_parents(&original, &backup)?;
                }
                self.restore_previous()?;
                if let Some(current) = current {
                    current.require_at(&self.targets.current_link)?;
                }
                Ok(())
            }
            ArchivedCurrentActivation::MovedLegacyCurrent {
                original,
                backup,
                identity,
            } => {
                if let Some(current) = current {
                    current.remove_at(&self.targets.current_link)?;
                }
                self.quarantine_candidate()?;
                if self.archive_applied {
                    require_path_absent(&original, "legacy activation restoration path")?;
                    IdentityTransactionalMove::new(
                        &backup,
                        &original,
                        identity,
                        "legacy activation selected for restoration",
                    )
                    .execute()?;
                    sync_projection_move_parents(&original, &backup)?;
                }
                self.restore_previous()?;
                Ok(())
            }
        }
    }

    fn quarantine_candidate(&mut self) -> Result<()> {
        let Some(candidate) = &self.candidate else {
            return Ok(());
        };
        let quarantine_base = self
            .targets
            .resolved
            .install_root
            .join("backups")
            .join(self.targets.version_dir.file_name().unwrap_or_default());
        let quarantine = unique_internal_projection_path(&quarantine_base, "candidate-rollback");
        IdentityTransactionalMove::new(
            &self.targets.version_dir,
            &quarantine,
            candidate.root_identity,
            "published activation candidate selected for quarantine",
        )
        .execute()?;
        self.candidate_quarantine = Some(quarantine.clone());
        sync_projection_move_parents(&self.targets.version_dir, &quarantine)
    }

    fn commit(self) {}
}

fn activation_rollback_conflict(path: &Path, state: &str) -> CliError {
    let mut error = CliError::new(
        "BUNDLE_ACTIVATION_ROLLBACK_CONFLICT",
        format!("Bundle activation rollback at {} is unsafe: {state}.", path.display()),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    error
}
