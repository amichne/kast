#[derive(Debug)]
enum ExistingConfigMigrationPlan {
    NoChange,
    Replace(ExistingConfigMigrationPatch),
}

#[derive(Debug)]
struct ExistingConfigMigrationPatch {
    current_link: PathBuf,
    release_root: PathBuf,
    path: PathBuf,
    original: ConfigFileSnapshot,
    migrated_contents: String,
}

#[derive(Debug)]
enum StagedBundleConfig<'a> {
    Default,
    Migrated {
        migration: &'a ExistingConfigMigrationPatch,
        path: PathBuf,
        snapshot: ConfigFileSnapshot,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ConfigFileSnapshot {
    identity: ProjectionFileIdentity,
    contents: Vec<u8>,
    mode: ConfigFileMode,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct ConfigFileMode {
    #[cfg(unix)]
    bits: u32,
}

impl ConfigFileMode {
    fn capture(path: &Path) -> Result<Self> {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let bits = fs::symlink_metadata(path)?.permissions().mode() & 0o7777;
            Ok(Self { bits })
        }
        #[cfg(not(unix))]
        {
            let _ = path;
            Ok(Self {})
        }
    }

    fn apply_to(self, file: &fs::File) -> Result<()> {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            file.set_permissions(fs::Permissions::from_mode(self.bits))?;
        }
        #[cfg(not(unix))]
        let _ = file;
        Ok(())
    }
}

impl ConfigFileSnapshot {
    fn capture(path: &Path) -> Result<Self> {
        let identity = projection_file_identity(path)?;
        if identity.kind != ProjectionFileKind::File {
            return Err(config_migration_conflict(
                path,
                "the configuration target is not a regular file",
            ));
        }
        let mode = ConfigFileMode::capture(path)?;
        let contents = fs::read(path)?;
        let confirmed_identity = projection_file_identity(path)?;
        let confirmed_mode = ConfigFileMode::capture(path)?;
        let confirmed_contents = fs::read(path)?;
        if identity != confirmed_identity
            || mode != confirmed_mode
            || contents != confirmed_contents
        {
            return Err(config_migration_conflict(
                path,
                "the configuration changed while setup inspected it",
            ));
        }
        Ok(Self {
            identity,
            contents,
            mode,
        })
    }

    fn matches(&self, path: &Path) -> bool {
        projection_file_identity(path).ok() == Some(self.identity)
            && ConfigFileMode::capture(path).ok() == Some(self.mode)
            && fs::read(path).is_ok_and(|contents| contents == self.contents)
    }

    fn require_at(&self, path: &Path, state: &str) -> Result<()> {
        if self.matches(path) {
            Ok(())
        } else {
            Err(config_migration_conflict(path, state))
        }
    }
}

impl ExistingConfigMigrationPlan {
    fn stage_for_bundle<'a>(&'a self, path: &Path) -> Result<StagedBundleConfig<'a>> {
        match self {
            Self::NoChange => {
                write_indexer_config(path)?;
                Ok(StagedBundleConfig::Default)
            }
            Self::Replace(patch) => patch.stage_for_bundle(path),
        }
    }

    fn current_patch(&self) -> Option<&ExistingConfigMigrationPatch> {
        match self {
            Self::NoChange => None,
            Self::Replace(patch) => Some(patch),
        }
    }
}

impl<'a> StagedBundleConfig<'a> {
    fn activate<'targets>(
        self,
        targets: &'targets ActivationTargetPaths,
        staged_bundle: &Path,
    ) -> Result<BundleActivationGuard<'targets>> {
        if let Self::Migrated {
            migration,
            path,
            snapshot,
        } = &self
        {
            migration.require_current_release()?;
            migration.original.require_at(
                &migration.path,
                "the configuration changed before bundle promotion",
            )?;
            snapshot.require_at(path, "the staged configuration changed before promotion")?;
        }
        let activation = BundleActivationGuard::prepare(targets)?.activate(staged_bundle)?;
        if let Self::Migrated { migration, .. } = &self
            && let Err(error) = migration.require_source_after_activation(&activation)
        {
            return Err(activation.rollback_into(error));
        }
        Ok(activation)
    }
}

impl ExistingConfigMigrationPatch {
    fn stage_for_bundle<'a>(&'a self, path: &Path) -> Result<StagedBundleConfig<'a>> {
        use std::io::Write;

        let parent = path.parent().ok_or_else(|| {
            CliError::new(
                "SETUP_MIGRATION_TARGET_INVALID",
                "The setup configuration has no parent directory.",
            )
        })?;
        fs::create_dir_all(parent)?;
        let (temporary, mut file) = manifest::create_unique_temporary_file(path, "config")?;
        file.write_all(self.migrated_contents.as_bytes())?;
        self.original.mode.apply_to(&file)?;
        file.sync_all()?;
        drop(file);
        if let Err(error) = fs::rename(&temporary, path) {
            let _ = fs::remove_file(&temporary);
            return Err(error.into());
        }
        manifest::sync_parent_directory(path)?;
        Ok(StagedBundleConfig::Migrated {
            migration: self,
            path: path.to_path_buf(),
            snapshot: ConfigFileSnapshot::capture(path)?,
        })
    }

    fn apply_after_receipt(&self) -> Result<()> {
        use std::io::Write;

        self.require_current_release()?;
        self.original.require_at(
            &self.path,
            "the configuration changed before migration",
        )?;
        let (temporary, mut file) =
            manifest::create_unique_temporary_file(&self.path, "migration")?;
        file.write_all(self.migrated_contents.as_bytes())?;
        self.original.mode.apply_to(&file)?;
        file.sync_all()?;
        drop(file);
        let migrated = ConfigFileSnapshot::capture(&temporary)?;
        let publication = self.publish(&temporary, &migrated);
        match publication {
            Ok(()) => remove_internal_projection_path(
                &temporary,
                Some(self.original.identity),
                "after-config-migration-cleanup-before-parent-sync",
            ),
            Err(mut error) => {
                if migrated.matches(&temporary)
                    && let Err(cleanup_error) = remove_internal_projection_path(
                        &temporary,
                        Some(migrated.identity),
                        "after-config-migration-failure-cleanup-before-parent-sync",
                    )
                {
                    error.details.insert(
                        "configMigrationCleanupError".to_string(),
                        cleanup_error.to_string(),
                    );
                }
                Err(error)
            }
        }
    }

    fn require_source_after_activation(
        &self,
        activation: &BundleActivationGuard<'_>,
    ) -> Result<()> {
        let source_path = match &activation.archived {
            ArchivedCurrentActivation::Referenced { previous, .. } => {
                if fs::canonicalize(previous).ok().as_deref() != Some(&self.release_root) {
                    return Err(config_migration_conflict(
                        &self.path,
                        "bundle promotion archived a different source release",
                    ));
                }
                self.path.clone()
            }
            ArchivedCurrentActivation::MovedRelease {
                original, backup, ..
            }
            | ArchivedCurrentActivation::MovedLegacyCurrent {
                original, backup, ..
            } => {
                if original != &self.release_root {
                    return Err(config_migration_conflict(
                        &self.path,
                        "bundle promotion moved a different source release",
                    ));
                }
                let relative = self.path.strip_prefix(&self.release_root).map_err(|_| {
                    config_migration_conflict(
                        &self.path,
                        "the configuration is outside its source release",
                    )
                })?;
                backup.join(relative)
            }
            ArchivedCurrentActivation::Absent => {
                return Err(config_migration_conflict(
                    &self.path,
                    "bundle promotion did not retain the source release",
                ));
            }
        };
        self.original.require_at(
            &source_path,
            "the source configuration changed during bundle promotion",
        )
    }

    fn publish(&self, temporary: &Path, migrated: &ConfigFileSnapshot) -> Result<()> {
        self.require_current_release()?;
        self.original.require_at(
            &self.path,
            "the configuration changed before migration",
        )?;
        migrated.require_at(temporary, "the prepared migration changed")?;
        test_path_projection_barrier("after-current-config-migration-validation")?;
        self.require_current_release()?;
        self.original.require_at(
            &self.path,
            "the configuration changed after migration validation",
        )?;
        migrated.require_at(temporary, "the prepared migration changed")?;
        test_path_projection_barrier("after-current-config-migration-final-validation")?;
        self.require_current_release()?;
        self.original.require_at(
            &self.path,
            "the configuration changed after final migration validation",
        )?;
        migrated.require_at(temporary, "the prepared migration changed")?;
        rename_exchange(&self.path, temporary)?;
        test_path_projection_barrier("after-current-config-migration-exchange-before-validation")?;

        let published = migrated.matches(&self.path) && self.original.matches(temporary);
        if published {
            return manifest::sync_parent_directory(&self.path);
        }

        let mut error = config_migration_conflict(
            &self.path,
            "the configuration changed during atomic migration",
        );
        error
            .details
            .insert("exchangeRestored".to_string(), "false".to_string());
        error.details.insert(
            "temporaryPath".to_string(),
            temporary.display().to_string(),
        );
        Err(error)
    }

    fn require_current_release(&self) -> Result<()> {
        if fs::canonicalize(&self.current_link).ok().as_deref() == Some(&self.release_root) {
            Ok(())
        } else {
            Err(config_migration_conflict(
                &self.path,
                "the current release changed before configuration migration",
            ))
        }
    }
}

fn plan_existing_config_migration(
    targets: &ActivationTargetPaths,
) -> Result<ExistingConfigMigrationPlan> {
    let release_root = match fs::canonicalize(&targets.current_link) {
        Ok(path) => path,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Ok(ExistingConfigMigrationPlan::NoChange);
        }
        Err(error) => return Err(error.into()),
    };
    let config_file = release_root.join("config/config.toml");
    match fs::symlink_metadata(&config_file) {
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Ok(ExistingConfigMigrationPlan::NoChange);
        }
        Err(error) => return Err(error.into()),
    }
    let original = ConfigFileSnapshot::capture(&config_file)?;
    let contents = String::from_utf8(original.contents.clone())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.utf8_error()))?;
    match crate::runtime::plan_legacy_backend_migration(&contents)? {
        crate::runtime::LegacyBackendMigrationPlan::NoChange => {
            Ok(ExistingConfigMigrationPlan::NoChange)
        }
        crate::runtime::LegacyBackendMigrationPlan::Replace(patch) => {
            Ok(ExistingConfigMigrationPlan::Replace(
                ExistingConfigMigrationPatch {
                    current_link: targets.current_link.clone(),
                    release_root,
                    path: config_file,
                    original,
                    migrated_contents: patch.migrated_contents().to_string(),
                },
            ))
        }
    }
}

fn config_migration_conflict(path: &Path, state: &str) -> CliError {
    let mut error = CliError::new(
        "SETUP_CONFIG_MIGRATION_CONFLICT",
        format!(
            "Cannot migrate configuration at {} because {state}; preserved the observed paths.",
            path.display(),
        ),
    );
    error
        .details
        .insert("configPath".to_string(), path.display().to_string());
    error
}
