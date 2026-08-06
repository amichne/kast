#[derive(Debug, Serialize, Clone, Copy, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SetupStatus {
    Activated,
    Current,
}

const DEVELOPER_SKILL_REFERENCE: &str = "/kast:developer";

#[derive(Debug, Serialize, Clone, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DeveloperOperationsRoute {
    pub cli: String,
    pub help_args: [&'static str; 1],
    pub skill: &'static str,
}

impl DeveloperOperationsRoute {
    pub(crate) fn try_from_cli_path(path: &Path) -> Result<Self> {
        let control_cli = ControlCliPath::parse(path)?;
        Ok(Self {
            cli: control_cli.0.display().to_string(),
            help_args: ["--help"],
            skill: DEVELOPER_SKILL_REFERENCE,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ControlCliPath(PathBuf);

impl ControlCliPath {
    fn parse(path: &Path) -> Result<Self> {
        let is_executable_file = fs::metadata(path)
            .is_ok_and(|metadata| metadata.is_file())
            && is_executable(path).unwrap_or(false);
        if crate::entrypoint_for_path(path) == Some(crate::Entrypoint::Control)
            && is_executable_file
        {
            return Ok(Self(path.to_path_buf()));
        }
        Err(CliError::new(
            "DEVELOPER_OPERATIONS_ROUTE_INVALID",
            format!(
                "Developer operations require an existing executable `kastctl` path, got {}.",
                path.display()
            ),
        ))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SetupMode {
    Reconcile,
    Force,
}

const PATH_PROJECTION_TRANSACTION_SCHEMA_VERSION: u32 = 3;
const PATH_PROJECTION_TRANSACTION_FILE: &str = "path-projection-transaction.json";
const FORCE_RESET_PATH_AUTHORITY_SCHEMA_VERSION: u32 = 1;
const FORCE_RESET_PATH_AUTHORITY_FILE: &str = "force-reset-path-authority.json";

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum ProjectionFileKind {
    Symlink,
    File,
    Directory,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProjectionFileIdentity {
    device: u64,
    inode: u64,
    kind: ProjectionFileKind,
}

#[derive(Debug)]
struct IdentityTransactionalMove<'a> {
    source: &'a Path,
    destination: &'a Path,
    expected_identity: ProjectionFileIdentity,
    label: &'static str,
    after_validation_barrier: Option<&'static str>,
}

impl<'a> IdentityTransactionalMove<'a> {
    fn new(
        source: &'a Path,
        destination: &'a Path,
        expected_identity: ProjectionFileIdentity,
        label: &'static str,
    ) -> Self {
        Self {
            source,
            destination,
            expected_identity,
            label,
            after_validation_barrier: None,
        }
    }

    fn with_after_validation_barrier(mut self, stage: &'static str) -> Self {
        self.after_validation_barrier = Some(stage);
        self
    }

    fn execute(self) -> Result<()> {
        require_identity(self.source, self.expected_identity, self.label)?;
        require_path_absent(self.destination, "identity-transactional move destination")?;
        require_identity(self.source, self.expected_identity, self.label)?;
        require_path_absent(self.destination, "identity-transactional move destination")?;
        if let Some(stage) = self.after_validation_barrier {
            test_path_projection_barrier(stage)?;
        }
        rename_no_replace(self.source, self.destination)?;
        if let Err(mut error) = require_identity(
            self.destination,
            self.expected_identity,
            "identity-transactional move result",
        ) {
            if let Err(restoration_error) =
                restore_identity_transactional_move(self.source, self.destination)
            {
                error.message = format!(
                    "{} The moved path could not be restored: {restoration_error}",
                    error.message,
                );
                error.details.insert(
                    "restorationError".to_string(),
                    restoration_error.to_string(),
                );
            }
            error
                .details
                .insert("sourcePath".to_string(), self.source.display().to_string());
            error.details.insert(
                "destinationPath".to_string(),
                self.destination.display().to_string(),
            );
            return Err(error);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ReceiptOwnedControlProjection {
    target: PathBuf,
    identity: ProjectionFileIdentity,
    receipt_release_digest: String,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct DurableForceResetPathAuthority {
    schema_version: u32,
    install_root: String,
    control_path: String,
    prior_target: String,
    prior_identity: ProjectionFileIdentity,
    receipt_release_digest: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ForceResetPathAuthorityJournal {
    path: PathBuf,
    identity: ProjectionFileIdentity,
    owned: ReceiptOwnedControlProjection,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ChangedForceResetControlProjection {
    authority_path: PathBuf,
    validation_error: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum ExistingControlProjection {
    Absent,
    ReceiptOwned(ReceiptOwnedControlProjection),
    ForceResetAuthorityChanged(ChangedForceResetControlProjection),
    Unmanaged,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct PathProjectionAuthority {
    control_path: PathBuf,
    expected_target: PathBuf,
    state: ExistingControlProjection,
    force_reset_journal: Option<ForceResetPathAuthorityJournal>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(tag = "kind", rename_all = "SCREAMING_SNAKE_CASE")]
enum DurablePathProjectionMutation {
    CreatePrepared {
        temporary_path: String,
    },
    CreateMaterialized {
        temporary_path: String,
        projected_identity: ProjectionFileIdentity,
    },
    ReplacePrepared {
        temporary_path: String,
        prior_target: String,
        prior_identity: ProjectionFileIdentity,
    },
    ReplaceMaterialized {
        temporary_path: String,
        projected_identity: ProjectionFileIdentity,
        prior_target: String,
        prior_identity: ProjectionFileIdentity,
    },
    Remove {
        quarantine_path: String,
        prior_target: String,
        prior_identity: ProjectionFileIdentity,
    },
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct DurablePathProjectionTransaction {
    schema_version: u32,
    control_path: String,
    control_target: String,
    receipt_path: String,
    release_digest: String,
    intended_profile: manifest::SetupProfile,
    transaction_nonce: String,
    mutation: DurablePathProjectionMutation,
}

#[derive(Debug)]
struct PathProjectionTransaction {
    journal_path: PathBuf,
    durable: DurablePathProjectionTransaction,
}

#[derive(Debug)]
struct AgentCommandProjection {
    path: PathBuf,
    created_identity: Option<ProjectionFileIdentity>,
}

fn test_path_projection_barrier(stage: &str) -> Result<()> {
    if env::var("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION").as_deref() != Ok("1") {
        return Ok(());
    }
    let Some(directory) = env::var_os("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER") else {
        return Ok(());
    };
    if env::var("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE").as_deref() != Ok(stage) {
        return Ok(());
    }
    let directory = PathBuf::from(directory);
    fs::create_dir_all(&directory)?;
    fs::write(directory.join(format!("{stage}.ready")), b"ready\n")?;
    let release = directory.join(format!("{stage}.continue"));
    let started = std::time::Instant::now();
    while !release.is_file() {
        if started.elapsed() > std::time::Duration::from_secs(10) {
            return Err(CliError::new(
                "SETUP_TEST_BARRIER_TIMEOUT",
                format!("Timed out waiting to continue setup barrier `{stage}`."),
            ));
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    Ok(())
}

fn test_path_projection_failure(point: &str) -> Result<()> {
    if env::var("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION").as_deref() == Ok("1")
        && env::var("KAST_TEST_SETUP_PATH_PROJECTION_FAILURE_POINT").as_deref() == Ok(point)
    {
        return Err(CliError::new(
            "SETUP_TEST_PATH_PROJECTION_FAILURE",
            format!("Injected setup PATH projection failure at `{point}`."),
        ));
    }
    Ok(())
}

fn test_path_projection_crash(point: &str) {
    if env::var("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION").as_deref() == Ok("1")
        && env::var("KAST_TEST_SETUP_PATH_PROJECTION_CRASH_POINT").as_deref() == Ok(point)
    {
        std::process::exit(86);
    }
}

impl PathProjectionAuthority {
    fn capture(targets: &ActivationTargetPaths) -> Result<Self> {
        let control_path = manifest::home_dir().join(".local/bin/kastctl");
        let desired_target = targets.resolved.active_binary.clone();
        let receipt = authority_manifest_from_file(
            &targets.current_link.join(manifest::INSTALL_MANIFEST_FILE),
        )
        .ok();
        let receipt_owned = validated_receipt_owned_control_projection(
            receipt.as_ref(),
            targets,
            &control_path,
        );
        let receipt_relinquishes_control = receipt.as_ref().is_some_and(|receipt| {
            receipt_explicitly_relinquishes_control(receipt, targets, &control_path)
        });
        let force_reset_journal =
            load_force_reset_path_authority(targets, &control_path)?;
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
                        Ok(()) => {
                            ExistingControlProjection::ReceiptOwned(journal.owned.clone())
                        }
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
        require_owned_projection_unchanged(
            &self.control_path,
            &owned.target,
            owned.identity,
        )?;
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
                    error.details.insert(
                        "path".to_string(),
                        self.control_path.display().to_string(),
                    );
                    error.details.insert(
                        "setupProfile".to_string(),
                        "DEVELOPMENT".to_string(),
                    );
                    return Err(error);
                }
                ExistingControlProjection::Absent
                | ExistingControlProjection::ReceiptOwned(_) => {}
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
            | ExistingControlProjection::Unmanaged => {
                manifest::SetupProfile::Standard
            }
        }
    }

    fn carry_prior_ownership_into(&self, receipt: &mut manifest::KastInstallManifest) {
        receipt.setup_profile = self.prior_setup_profile();
        receipt.path_projections.retain(|projection| {
            projection.command != manifest::PathProjectionCommand::Kastctl
        });
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
                let temporary_path = internal_projection_path(
                    &self.control_path,
                    "create",
                    &transaction_nonce,
                );
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
                let temporary_path = internal_projection_path(
                    &self.control_path,
                    "replace",
                    &transaction_nonce,
                );
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
                let quarantine_path = internal_projection_path(
                    &self.control_path,
                    "remove",
                    &transaction_nonce,
                );
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

impl PathProjectionTransaction {
    fn prepare(
        install_root: &Path,
        durable: DurablePathProjectionTransaction,
    ) -> Result<Self> {
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
            } => {
                self.apply_materialized_create(Path::new(&temporary_path), projected_identity)
            }
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
                    .with_after_validation_barrier(
                        "after-control-create-rollback-validation",
                    )
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
                        return Err(projection_recovery_conflict(
                            control_path,
                            quarantine_path,
                        ));
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
                    .with_after_validation_barrier(
                        "after-control-remove-rollback-validation",
                    )
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

impl AgentCommandProjection {
    fn project(path: &Path, target: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        match fs::symlink_metadata(path) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                std::os::unix::fs::symlink(target, path)?;
                let identity = projection_file_identity(path)?;
                let projection = Self {
                    path: path.to_path_buf(),
                    created_identity: Some(identity),
                };
                if let Err(mut error) = sync_projection_parent_after(
                    path,
                    "after-agent-create-before-parent-sync",
                ) {
                    if let Err(barrier_error) =
                        test_path_projection_barrier("before-agent-create-failure-cleanup")
                    {
                        error.details.insert(
                            "agentProjectionCleanupBarrierError".to_string(),
                            barrier_error.to_string(),
                        );
                    }
                    if let Err(rollback_error) = projection.rollback() {
                        error.details.insert(
                            "agentProjectionRollbackError".to_string(),
                            rollback_error.to_string(),
                        );
                    }
                    return Err(error);
                }
                Ok(projection)
            }
            Err(error) => Err(error.into()),
            Ok(_) if exact_projection_matches(path, target) => {
                sync_projection_parent(path)?;
                Ok(Self {
                    path: path.to_path_buf(),
                    created_identity: None,
                })
            }
            Ok(_) => Err(projection_changed_error(
                path,
                "the agent command path is not the expected Kast projection",
            )),
        }
    }

    fn rollback(&self) -> Result<()> {
        let Some(identity) = self.created_identity else {
            return Ok(());
        };
        if projection_file_identity(&self.path).ok() != Some(identity) {
            return Ok(());
        }
        let rollback_path = self
            .path
            .with_extension(format!("kast-rollback-{}", std::process::id()));
        IdentityTransactionalMove::new(
            &self.path,
            &rollback_path,
            identity,
            "agent projection selected for rollback",
        )
        .with_after_validation_barrier("after-agent-rollback-validation")
        .execute()?;
        sync_projection_parent_after(
            &rollback_path,
            "after-agent-rollback-rename-before-parent-sync",
        )?;
        remove_internal_projection_path(
            &rollback_path,
            Some(identity),
            "after-agent-rollback-cleanup-before-parent-sync",
        )
    }
}

fn validated_receipt_owned_control_projection(
    receipt: Option<&manifest::KastInstallManifest>,
    targets: &ActivationTargetPaths,
    control_path: &Path,
) -> Option<ReceiptOwnedControlProjection> {
    let receipt = receipt?;
    if !receipt_is_current_install_authority(receipt, targets)
        || receipt.setup_profile != manifest::SetupProfile::Development
    {
        return None;
    }
    let mut projections = receipt.path_projections.iter().filter(|projection| {
        projection.command == manifest::PathProjectionCommand::Kastctl
    });
    let projection = projections.next()?;
    let recorded_target = config::normalize(PathBuf::from(&projection.target));
    if projections.next().is_some()
        || Path::new(&projection.path) != control_path
        || !recorded_target.starts_with(&targets.current_link)
        || recorded_target.file_name().and_then(|name| name.to_str()) != Some("kastctl")
        || !exact_projection_matches(control_path, &recorded_target)
    {
        return None;
    }
    let identity = projection_file_identity(control_path).ok()?;
    (identity.kind == ProjectionFileKind::Symlink).then_some(ReceiptOwnedControlProjection {
        target: recorded_target,
        identity,
        receipt_release_digest: receipt.release_digest.clone(),
    })
}

fn receipt_is_current_install_authority(
    receipt: &manifest::KastInstallManifest,
    targets: &ActivationTargetPaths,
) -> bool {
    receipt.schema_version == crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION
        && receipt.tool == "kast"
        && !receipt.release_digest.is_empty()
        && config::normalize(PathBuf::from(&receipt.roots.install))
            == config::normalize(targets.resolved.install_root.clone())
}

fn receipt_explicitly_relinquishes_control(
    receipt: &manifest::KastInstallManifest,
    targets: &ActivationTargetPaths,
    control_path: &Path,
) -> bool {
    receipt_is_current_install_authority(receipt, targets)
        && receipt.setup_profile == manifest::SetupProfile::Standard
        && !receipt.path_projections.iter().any(|projection| {
            projection.command == manifest::PathProjectionCommand::Kastctl
        })
        && !receipt
            .owned_paths
            .iter()
            .any(|path| Path::new(path) == control_path)
}

fn load_force_reset_path_authority(
    targets: &ActivationTargetPaths,
    control_path: &Path,
) -> Result<Option<ForceResetPathAuthorityJournal>> {
    let path = targets
        .resolved
        .install_root
        .join(FORCE_RESET_PATH_AUTHORITY_FILE);
    let identity = match fs::symlink_metadata(&path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
        Ok(_) => projection_file_identity(&path)?,
    };
    if identity.kind != ProjectionFileKind::File {
        return Err(force_reset_path_authority_invalid(
            &path,
            "the authority snapshot is not a regular file",
        ));
    }
    let contents = fs::read_to_string(&path)?;
    require_identity(&path, identity, "force-reset PATH authority snapshot")?;
    let durable: DurableForceResetPathAuthority = serde_json::from_str(&contents).map_err(|error| {
        force_reset_path_authority_invalid(&path, format!("invalid JSON: {error}"))
    })?;
    let target = config::normalize(PathBuf::from(&durable.prior_target));
    let expected_target = config::normalize(targets.resolved.active_binary.clone());
    if durable.schema_version != FORCE_RESET_PATH_AUTHORITY_SCHEMA_VERSION
        || config::normalize(PathBuf::from(&durable.install_root))
            != config::normalize(targets.resolved.install_root.clone())
        || Path::new(&durable.control_path) != control_path
        || target != expected_target
        || durable.prior_identity.kind != ProjectionFileKind::Symlink
        || durable.receipt_release_digest.is_empty()
    {
        return Err(force_reset_path_authority_invalid(
            &path,
            "the authority snapshot does not match this Kast installation",
        ));
    }
    Ok(Some(ForceResetPathAuthorityJournal {
        path,
        identity,
        owned: ReceiptOwnedControlProjection {
            target,
            identity: durable.prior_identity,
            receipt_release_digest: durable.receipt_release_digest,
        },
    }))
}

fn write_force_reset_path_authority_create_new(
    install_root: &Path,
    control_path: &Path,
    owned: &ReceiptOwnedControlProjection,
) -> Result<ForceResetPathAuthorityJournal> {
    use std::io::Write;
    fs::create_dir_all(install_root)?;
    let path = install_root.join(FORCE_RESET_PATH_AUTHORITY_FILE);
    let durable = DurableForceResetPathAuthority {
        schema_version: FORCE_RESET_PATH_AUTHORITY_SCHEMA_VERSION,
        install_root: install_root.display().to_string(),
        control_path: control_path.display().to_string(),
        prior_target: owned.target.display().to_string(),
        prior_identity: owned.identity,
        receipt_release_digest: owned.receipt_release_digest.clone(),
    };
    let encoded = serde_json::to_vec_pretty(&durable)?;
    let staging_directory = install_root.join("staging/force-authority");
    fs::create_dir_all(&staging_directory)?;
    let temporary_template = staging_directory.join(FORCE_RESET_PATH_AUTHORITY_FILE);
    let (temporary_path, mut file) =
        manifest::create_unique_temporary_file(&temporary_template, "write")?;
    let temporary_identity = projection_file_identity(&temporary_path)?;
    if temporary_identity.kind != ProjectionFileKind::File {
        return Err(force_reset_path_authority_invalid(
            &temporary_path,
            "the authority snapshot temporary path is not a regular file",
        ));
    }
    let split = encoded.len().div_ceil(2);
    let write_result = (|| -> Result<()> {
        file.write_all(&encoded[..split])?;
        test_path_projection_crash("during-force-authority-write");
        file.write_all(&encoded[split..])?;
        file.write_all(b"\n")?;
        file.sync_all()?;
        manifest::test_install_durability_failure(
            "after-force-authority-temporary-sync-before-publish",
        )
    })();
    drop(file);
    if let Err(error) = write_result {
        return Err(with_force_authority_temporary_cleanup(
            error,
            &temporary_path,
            temporary_identity,
        ));
    }
    let publish_result = (|| -> Result<()> {
        IdentityTransactionalMove::new(
            &temporary_path,
            &path,
            temporary_identity,
            "force-reset PATH authority temporary snapshot",
        )
        .execute()?;
        manifest::test_install_durability_failure(
            "after-force-authority-publish-before-parent-sync",
        )?;
        sync_projection_move_parents(&temporary_path, &path)?;
        require_identity(
            &path,
            temporary_identity,
            "published force-reset PATH authority snapshot",
        )
    })();
    if let Err(error) = publish_result {
        return Err(with_force_authority_temporary_cleanup(
            error,
            &temporary_path,
            temporary_identity,
        ));
    }
    Ok(ForceResetPathAuthorityJournal {
        path,
        identity: temporary_identity,
        owned: owned.clone(),
    })
}

fn with_force_authority_temporary_cleanup(
    mut error: CliError,
    temporary_path: &Path,
    temporary_identity: ProjectionFileIdentity,
) -> CliError {
    if let Err(cleanup_error) = remove_internal_projection_path(
        temporary_path,
        Some(temporary_identity),
        "after-force-authority-temporary-cleanup-before-parent-sync",
    ) {
        error.details.insert(
            "temporaryCleanupError".to_string(),
            cleanup_error.to_string(),
        );
        error.details.insert(
            "temporaryPath".to_string(),
            temporary_path.display().to_string(),
        );
    }
    error
}

fn force_reset_path_authority_invalid(path: &Path, reason: impl Into<String>) -> CliError {
    let mut error = CliError::new(
        "FORCE_RESET_PATH_AUTHORITY_INVALID",
        format!(
            "Force-reset PATH authority at {} is invalid: {}.",
            path.display(),
            reason.into(),
        ),
    );
    error
        .details
        .insert("authorityPath".to_string(), path.display().to_string());
    error
}

fn force_reset_path_authority_changed(
    control_path: &Path,
    authority_path: &Path,
    validation_error: &str,
) -> CliError {
    let mut error = CliError::new(
        "FORCE_RESET_PATH_AUTHORITY_CHANGED",
        format!(
            "Force-reset recovery cannot authorize changed command {}.",
            control_path.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), control_path.display().to_string());
    error.details.insert(
        "authorityPath".to_string(),
        authority_path.display().to_string(),
    );
    error
        .details
        .insert("validationError".to_string(), validation_error.to_string());
    error
}

fn authority_manifest_from_file(path: &Path) -> Result<manifest::KastInstallManifest> {
    let contents = fs::read_to_string(path)?;
    let raw: serde_json::Value = serde_json::from_str(&contents).map_err(|error| {
        CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!("Invalid install manifest at {}: {error}", path.display()),
        )
    })?;
    let has_explicit_authority = raw
        .as_object()
        .is_some_and(|receipt| receipt.contains_key("schemaVersion") && receipt.contains_key("tool"));
    if !has_explicit_authority {
        return Err(CliError::new(
            "INSTALL_MANIFEST_AUTHORITY_MISSING",
            format!(
                "Install manifest at {} has no explicit schemaVersion or tool authority.",
                path.display(),
            ),
        ));
    }
    serde_json::from_value(raw).map_err(|error| {
        CliError::new(
            "INSTALL_MANIFEST_INVALID",
            format!("Invalid install manifest at {}: {error}", path.display()),
        )
    })
}

fn require_owned_projection_unchanged(
    path: &Path,
    expected_target: &Path,
    expected_identity: ProjectionFileIdentity,
) -> Result<()> {
    if projection_file_identity(path).ok() == Some(expected_identity)
        && exact_projection_matches(path, expected_target)
    {
        return Ok(());
    }
    Err(projection_changed_error(
        path,
        "the receipt-owned identity or target changed after setup inspection",
    ))
}

fn exact_projection_matches(path: &Path, recorded_target: &Path) -> bool {
    let recorded_target = config::normalize(recorded_target.to_path_buf());
    fs::read_link(path).is_ok_and(|actual_target| {
        let actual_target = if actual_target.is_absolute() {
            actual_target
        } else {
            path.parent()
                .unwrap_or_else(|| Path::new("."))
                .join(actual_target)
        };
        config::normalize(actual_target) == recorded_target
    })
}

fn projection_changed_error(path: &Path, reason: impl Into<String>) -> CliError {
    let mut error = CliError::new(
        "PATH_PROJECTION_CHANGED",
        format!("PATH projection {} changed: {}.", path.display(), reason.into()),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    error
}

fn projection_recovery_conflict(path: &Path, quarantine: &Path) -> CliError {
    let mut error = CliError::new(
        "PATH_PROJECTION_RECOVERY_CONFLICT",
        format!(
            "Cannot restore receipt-owned projection {} because another path exists; preserved the prior projection at {}.",
            path.display(),
            quarantine.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    error.details.insert(
        "quarantinePath".to_string(),
        quarantine.display().to_string(),
    );
    error
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ControlReplacementState {
    PriorPublished,
    DesiredPublished,
}

fn control_replacement_state(
    control_path: &Path,
    temporary_path: &Path,
    prior_target: &Path,
    prior_identity: ProjectionFileIdentity,
    desired_target: &Path,
    projected_identity: ProjectionFileIdentity,
) -> Result<ControlReplacementState> {
    let prior_is_published = projection_file_identity(control_path).ok() == Some(prior_identity)
        && exact_projection_matches(control_path, prior_target)
        && projection_file_identity(temporary_path).ok() == Some(projected_identity)
        && exact_projection_matches(temporary_path, desired_target);
    if prior_is_published {
        return Ok(ControlReplacementState::PriorPublished);
    }
    let desired_is_published =
        projection_file_identity(control_path).ok() == Some(projected_identity)
            && exact_projection_matches(control_path, desired_target)
            && projection_file_identity(temporary_path).ok() == Some(prior_identity)
            && exact_projection_matches(temporary_path, prior_target);
    if desired_is_published {
        return Ok(ControlReplacementState::DesiredPublished);
    }
    Err(projection_recovery_conflict(control_path, temporary_path))
}

fn require_control_replacement_state(
    control_path: &Path,
    temporary_path: &Path,
    prior_target: &Path,
    prior_identity: ProjectionFileIdentity,
    desired_target: &Path,
    projected_identity: ProjectionFileIdentity,
    expected: ControlReplacementState,
) -> Result<()> {
    if control_replacement_state(
        control_path,
        temporary_path,
        prior_target,
        prior_identity,
        desired_target,
        projected_identity,
    )? == expected
    {
        Ok(())
    } else {
        Err(projection_recovery_conflict(control_path, temporary_path))
    }
}

#[derive(Debug, Clone, Copy)]
struct ProjectionExchangeSnapshot {
    control_identity: ProjectionFileIdentity,
    temporary_identity: ProjectionFileIdentity,
}

impl ProjectionExchangeSnapshot {
    fn capture(control_path: &Path, temporary_path: &Path) -> Result<Self> {
        Ok(Self {
            control_identity: projection_file_identity(control_path)?,
            temporary_identity: projection_file_identity(temporary_path)?,
        })
    }

    fn require_reversed(self, control_path: &Path, temporary_path: &Path) -> Result<()> {
        require_identity(
            control_path,
            self.temporary_identity,
            "restored public control projection",
        )?;
        require_identity(
            temporary_path,
            self.control_identity,
            "restored temporary control projection",
        )
    }
}

#[allow(clippy::too_many_arguments)]
fn exchange_control_projection(
    control_path: &Path,
    temporary_path: &Path,
    prior_target: &Path,
    prior_identity: ProjectionFileIdentity,
    desired_target: &Path,
    projected_identity: ProjectionFileIdentity,
    expected_state: ControlReplacementState,
    durability_failure_point: &str,
) -> Result<()> {
    rename_exchange(control_path, temporary_path)?;
    match require_control_replacement_state(
        control_path,
        temporary_path,
        prior_target,
        prior_identity,
        desired_target,
        projected_identity,
        expected_state,
    ) {
        Ok(()) => sync_projection_parent_after(control_path, durability_failure_point),
        Err(mut error) => {
            let exchange_restored = match restore_projection_exchange(control_path, temporary_path)
            {
                Ok(()) => true,
                Err(restoration_error) => {
                    error.message = format!(
                        "{} The exchanged paths could not be restored: {restoration_error}",
                        error.message,
                    );
                    error.details.insert(
                        "exchangeRestorationError".to_string(),
                        restoration_error.to_string(),
                    );
                    false
                }
            };
            error.details.insert(
                "exchangeRestored".to_string(),
                exchange_restored.to_string(),
            );
            Err(error)
        }
    }
}

fn restore_projection_exchange(control_path: &Path, temporary_path: &Path) -> Result<()> {
    let snapshot = ProjectionExchangeSnapshot::capture(control_path, temporary_path);
    rename_exchange(control_path, temporary_path)?;
    let verification = snapshot.and_then(|snapshot| {
        snapshot.require_reversed(control_path, temporary_path)
    });
    let durability = sync_projection_move_parents(control_path, temporary_path);
    if let Err(mut error) = verification {
        if let Err(durability_error) = durability {
            error.details.insert(
                "restorationDurabilityError".to_string(),
                durability_error.to_string(),
            );
        }
        return Err(error);
    }
    durability
}

fn remove_prepared_control_projection(path: &Path, expected_target: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            sync_projection_parent(path)
        }
        Err(error) => Err(error.into()),
        Ok(_) => Err(prepared_projection_recovery_conflict(path, expected_target)),
    }
}

fn prepared_projection_recovery_conflict(path: &Path, expected_target: &Path) -> CliError {
    let mut error = CliError::new(
        "PATH_PROJECTION_RECOVERY_CONFLICT",
        format!(
            "Cannot remove unproven PATH projection transaction artifact {}; preserved the changed path.",
            path.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    error.details.insert(
        "expectedTarget".to_string(),
        expected_target.display().to_string(),
    );
    error
}

fn require_path_absent(path: &Path, label: &str) -> Result<()> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
        Ok(_) => Err(CliError::new(
            "PATH_PROJECTION_INTERNAL_PATH_OCCUPIED",
            format!("{label} is already occupied: {}.", path.display()),
        )),
    }
}

fn require_identity(
    path: &Path,
    expected: ProjectionFileIdentity,
    label: &str,
) -> Result<()> {
    if projection_file_identity(path).ok() == Some(expected) {
        Ok(())
    } else {
        Err(CliError::new(
            "PATH_PROJECTION_IDENTITY_CHANGED",
            format!("{label} identity changed at {}.", path.display()),
        ))
    }
}

fn restore_identity_transactional_move(source: &Path, destination: &Path) -> Result<()> {
    let moved_identity = projection_file_identity(destination)?;
    require_path_absent(source, "identity-transactional move restoration path")?;
    rename_no_replace(destination, source)?;
    let verification = require_identity(
        source,
        moved_identity,
        "restored identity-transactional move source",
    );
    let durability = sync_projection_move_parents(source, destination);
    if let Err(mut error) = verification {
        if let Err(durability_error) = durability {
            error.details.insert(
                "restorationDurabilityError".to_string(),
                durability_error.to_string(),
            );
        }
        return Err(error);
    }
    durability
}

fn sync_projection_move_parents(first: &Path, second: &Path) -> Result<()> {
    let first_result = manifest::sync_parent_directory(first);
    let second_result = manifest::sync_parent_directory(second);
    match (first_result, second_result) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(error), Ok(())) | (Ok(()), Err(error)) => Err(error),
        (Err(mut first_error), Err(second_error)) => {
            first_error.details.insert(
                "secondParentSyncError".to_string(),
                second_error.to_string(),
            );
            Err(first_error)
        }
    }
}

#[cfg(unix)]
fn projection_file_identity(path: &Path) -> Result<ProjectionFileIdentity> {
    use std::os::unix::fs::MetadataExt;
    let metadata = fs::symlink_metadata(path)?;
    let file_type = metadata.file_type();
    let kind = if file_type.is_symlink() {
        ProjectionFileKind::Symlink
    } else if file_type.is_file() {
        ProjectionFileKind::File
    } else if file_type.is_dir() {
        ProjectionFileKind::Directory
    } else {
        ProjectionFileKind::Other
    };
    Ok(ProjectionFileIdentity {
        device: metadata.dev(),
        inode: metadata.ino(),
        kind,
    })
}

#[cfg(not(unix))]
fn projection_file_identity(_path: &Path) -> Result<ProjectionFileIdentity> {
    Err(CliError::new(
        "PATH_PROJECTION_PLATFORM_UNSUPPORTED",
        "Receipt-owned PATH projections require Unix filesystem identity.",
    ))
}

#[cfg(target_os = "macos")]
fn rename_no_replace(source: &Path, target: &Path) -> Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let source = CString::new(source.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "source path contains NUL"))?;
    let target = CString::new(target.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "target path contains NUL"))?;
    let result = unsafe { libc::renamex_np(source.as_ptr(), target.as_ptr(), libc::RENAME_EXCL) };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error().into())
    }
}

#[cfg(target_os = "macos")]
fn rename_exchange(first: &Path, second: &Path) -> Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let first = CString::new(first.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "first path contains NUL"))?;
    let second = CString::new(second.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "second path contains NUL"))?;
    let result = unsafe { libc::renamex_np(first.as_ptr(), second.as_ptr(), libc::RENAME_SWAP) };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error().into())
    }
}

#[cfg(target_os = "linux")]
fn rename_no_replace(source: &Path, target: &Path) -> Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let source = CString::new(source.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "source path contains NUL"))?;
    let target = CString::new(target.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "target path contains NUL"))?;
    let result = unsafe {
        libc::renameat2(
            libc::AT_FDCWD,
            source.as_ptr(),
            libc::AT_FDCWD,
            target.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error().into())
    }
}

#[cfg(target_os = "linux")]
fn rename_exchange(first: &Path, second: &Path) -> Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;
    let first = CString::new(first.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "first path contains NUL"))?;
    let second = CString::new(second.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "second path contains NUL"))?;
    let result = unsafe {
        libc::renameat2(
            libc::AT_FDCWD,
            first.as_ptr(),
            libc::AT_FDCWD,
            second.as_ptr(),
            libc::RENAME_EXCHANGE,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error().into())
    }
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
fn rename_no_replace(_source: &Path, _target: &Path) -> Result<()> {
    Err(CliError::new(
        "PATH_PROJECTION_PLATFORM_UNSUPPORTED",
        "Atomic no-replace PATH projection is supported only on macOS and Linux.",
    ))
}

#[cfg(not(any(target_os = "macos", target_os = "linux")))]
fn rename_exchange(_first: &Path, _second: &Path) -> Result<()> {
    Err(CliError::new(
        "PATH_PROJECTION_PLATFORM_UNSUPPORTED",
        "Atomic PATH projection exchange is supported only on macOS and Linux.",
    ))
}

fn write_projection_transaction_create_new(
    path: &Path,
    transaction: &DurablePathProjectionTransaction,
) -> Result<()> {
    use std::io::Write;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)?;
    file.write_all(serde_json::to_vec_pretty(transaction)?.as_slice())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    sync_projection_parent(path)
}

fn write_projection_transaction_atomic(
    path: &Path,
    transaction: &DurablePathProjectionTransaction,
) -> Result<()> {
    use std::io::Write;
    let (temporary, mut file) = manifest::create_unique_temporary_file(path, "journal")?;
    file.write_all(serde_json::to_vec_pretty(transaction)?.as_slice())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(&temporary, path)?;
    sync_projection_parent(path)
}

fn remove_projection_transaction(path: &Path) -> Result<()> {
    match fs::remove_file(path) {
        Ok(()) => sync_projection_parent(path),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error.into()),
    }
}

fn sync_projection_parent(path: &Path) -> Result<()> {
    manifest::sync_parent_directory(path)
}

fn remove_internal_projection_path(
    path: &Path,
    expected: Option<ProjectionFileIdentity>,
    durability_failure_point: &str,
) -> Result<()> {
    let identity = match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return sync_projection_parent_after(path, durability_failure_point);
        }
        Err(error) => return Err(error.into()),
        Ok(_) => projection_file_identity(path)?,
    };
    if expected.is_some_and(|expected| expected != identity) {
        return Err(internal_projection_cleanup_conflict(path, None));
    }
    test_path_projection_barrier("before-control-internal-cleanup")?;
    let private_path = unique_internal_projection_path(path, "cleanup");
    rename_no_replace(path, &private_path)?;
    if projection_file_identity(&private_path).ok() != Some(identity) {
        let restore_error = rename_no_replace(&private_path, path)
            .and_then(|()| sync_projection_parent(path))
            .err();
        let mut error = internal_projection_cleanup_conflict(path, Some(&private_path));
        if let Some(restore_error) = restore_error {
            error.details.insert(
                "restoreError".to_string(),
                restore_error.to_string(),
            );
        }
        return Err(error);
    }
    fs::remove_file(&private_path)?;
    sync_projection_parent_after(path, durability_failure_point)
}

fn internal_projection_cleanup_conflict(path: &Path, private_path: Option<&Path>) -> CliError {
    let mut error = CliError::new(
        "PATH_PROJECTION_RECOVERY_CONFLICT",
        format!(
            "Preserved changed PATH projection transaction artifact {}.",
            path.display(),
        ),
    );
    error
        .details
        .insert("path".to_string(), path.display().to_string());
    if let Some(private_path) = private_path {
        error.details.insert(
            "privatePath".to_string(),
            private_path.display().to_string(),
        );
    }
    error
}

fn unique_internal_projection_path(path: &Path, purpose: &str) -> PathBuf {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};
    static UNIQUE_PATH_COUNTER: AtomicU64 = AtomicU64::new(0);
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let sequence = UNIQUE_PATH_COUNTER.fetch_add(1, Ordering::Relaxed);
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("kastctl");
    path.with_file_name(format!(
        ".{file_name}.kast-{purpose}-{}-{nonce}-{sequence}",
        std::process::id(),
    ))
}

fn new_projection_transaction_nonce() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};
    static TRANSACTION_NONCE_COUNTER: AtomicU64 = AtomicU64::new(0);
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let sequence = TRANSACTION_NONCE_COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{}-{nonce}-{sequence}", std::process::id())
}

fn valid_projection_transaction_nonce(nonce: &str) -> bool {
    let mut components = nonce.split('-');
    let valid = components
        .next()
        .is_some_and(|value| value.parse::<u32>().is_ok())
        && components
            .next()
            .is_some_and(|value| value.parse::<u128>().is_ok())
        && components
            .next()
            .is_some_and(|value| value.parse::<u64>().is_ok());
    valid && components.next().is_none()
}

fn internal_projection_path(control_path: &Path, operation: &str, nonce: &str) -> PathBuf {
    let file_name = control_path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("kastctl");
    control_path.with_file_name(format!("{file_name}.kast-{operation}-{nonce}"))
}

fn sync_projection_parent_after(path: &Path, durability_failure_point: &str) -> Result<()> {
    manifest::test_install_durability_failure(durability_failure_point)?;
    sync_projection_parent(path)
}

fn recover_path_projection_transaction(targets: &ActivationTargetPaths) -> Result<()> {
    let journal_path = targets
        .resolved
        .install_root
        .join(PATH_PROJECTION_TRANSACTION_FILE);
    let contents = match fs::read_to_string(&journal_path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let durable: DurablePathProjectionTransaction = serde_json::from_str(&contents).map_err(|error| {
        CliError::new(
            "PATH_PROJECTION_TRANSACTION_INVALID",
            format!(
                "Invalid PATH projection transaction at {}: {error}",
                journal_path.display(),
            ),
        )
    })?;
    validate_durable_projection_transaction(&durable, targets)?;
    let transaction = PathProjectionTransaction {
        journal_path,
        durable,
    };
    if projection_transaction_receipt_committed(&transaction.durable, targets) {
        transaction.commit()
    } else {
        transaction.rollback()
    }
}

fn validate_durable_projection_transaction(
    transaction: &DurablePathProjectionTransaction,
    targets: &ActivationTargetPaths,
) -> Result<()> {
    let expected_control = manifest::home_dir().join(".local/bin/kastctl");
    let expected_target = targets.resolved.active_binary.clone();
    let expected_receipt = targets
        .current_link
        .join(manifest::INSTALL_MANIFEST_FILE);
    let mutation_is_valid = valid_projection_transaction_nonce(&transaction.transaction_nonce)
        && match (&transaction.intended_profile, &transaction.mutation) {
            (
                manifest::SetupProfile::Development,
                DurablePathProjectionMutation::CreatePrepared { temporary_path },
            ) => {
                Path::new(temporary_path)
                    == internal_projection_path(
                        &expected_control,
                        "create",
                        &transaction.transaction_nonce,
                    )
            }
            (
                manifest::SetupProfile::Development,
                DurablePathProjectionMutation::CreateMaterialized {
                    temporary_path,
                    projected_identity,
                },
            ) => {
                projected_identity.kind == ProjectionFileKind::Symlink
                    && Path::new(temporary_path)
                        == internal_projection_path(
                            &expected_control,
                            "create",
                            &transaction.transaction_nonce,
                        )
            }
            (
                manifest::SetupProfile::Development,
                DurablePathProjectionMutation::ReplacePrepared {
                    temporary_path,
                    prior_target,
                    prior_identity,
                },
            ) => {
                prior_identity.kind == ProjectionFileKind::Symlink
                    && !prior_target.is_empty()
                    && config::normalize(PathBuf::from(prior_target))
                        != config::normalize(PathBuf::from(&transaction.control_target))
                    && Path::new(temporary_path)
                        == internal_projection_path(
                            &expected_control,
                            "replace",
                            &transaction.transaction_nonce,
                        )
            }
            (
                manifest::SetupProfile::Development,
                DurablePathProjectionMutation::ReplaceMaterialized {
                    temporary_path,
                    projected_identity,
                    prior_target,
                    prior_identity,
                },
            ) => {
                projected_identity.kind == ProjectionFileKind::Symlink
                    && prior_identity.kind == ProjectionFileKind::Symlink
                    && !prior_target.is_empty()
                    && config::normalize(PathBuf::from(prior_target))
                        != config::normalize(PathBuf::from(&transaction.control_target))
                    && Path::new(temporary_path)
                        == internal_projection_path(
                            &expected_control,
                            "replace",
                            &transaction.transaction_nonce,
                        )
            }
            (
                manifest::SetupProfile::Standard,
                DurablePathProjectionMutation::Remove {
                    quarantine_path,
                    prior_target,
                    prior_identity,
                },
            ) => {
                prior_identity.kind == ProjectionFileKind::Symlink
                    && !prior_target.is_empty()
                    && Path::new(quarantine_path)
                        == internal_projection_path(
                            &expected_control,
                            "remove",
                            &transaction.transaction_nonce,
                        )
            }
            _ => false,
        };
    if transaction.schema_version != PATH_PROJECTION_TRANSACTION_SCHEMA_VERSION
        || Path::new(&transaction.control_path) != expected_control
        || config::normalize(PathBuf::from(&transaction.control_target))
            != config::normalize(expected_target)
        || Path::new(&transaction.receipt_path) != expected_receipt
        || transaction.release_digest.is_empty()
        || !mutation_is_valid
    {
        return Err(CliError::new(
            "PATH_PROJECTION_TRANSACTION_INVALID",
            "PATH projection transaction does not match this Kast installation.",
        ));
    }
    Ok(())
}

fn projection_transaction_receipt_committed(
    transaction: &DurablePathProjectionTransaction,
    targets: &ActivationTargetPaths,
) -> bool {
    let Ok(receipt) = authority_manifest_from_file(
        &targets.current_link.join(manifest::INSTALL_MANIFEST_FILE),
    ) else {
        return false;
    };
    if receipt.schema_version != crate::protocol_schema_versions::INSTALL_RECEIPT_SCHEMA_VERSION
        || receipt.tool != "kast"
        || receipt.release_digest != transaction.release_digest
        || receipt.setup_profile != transaction.intended_profile
    {
        return false;
    }
    let matching = receipt
        .path_projections
        .iter()
        .filter(|projection| {
            projection.command == manifest::PathProjectionCommand::Kastctl
        })
        .collect::<Vec<_>>();
    if transaction.intended_profile.projects_control_command() {
        matching.len() == 1
            && Path::new(&matching[0].path) == Path::new(&transaction.control_path)
            && config::normalize(PathBuf::from(&matching[0].target))
                == config::normalize(PathBuf::from(&transaction.control_target))
    } else {
        matching.is_empty()
    }
}

impl SetupMode {
    fn from_force_flag(force: bool) -> Self {
        if force { Self::Force } else { Self::Reconcile }
    }

    fn is_force(self) -> bool {
        self == Self::Force
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupResult {
    #[serde(rename = "type")]
    pub result_type: &'static str,
    pub status: SetupStatus,
    pub release_digest: String,
    pub manifest_digest: String,
    pub kast_home: String,
    pub current: String,
    pub active_binary: String,
    pub developer_operations: DeveloperOperationsRoute,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backup: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub restart_requirement: Option<SetupRestartRequirement>,
    pub artifacts: Vec<SetupArtifact>,
    pub verified: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupRestartRequirement {
    pub code: &'static str,
    pub message: &'static str,
}

#[derive(Debug)]
struct RetiredPublicPluginRemoval {
    restart_requirement: Option<SetupRestartRequirement>,
}

#[derive(Debug)]
struct LegacyInstallationArchive {
    entries: Vec<LegacyInstallationArchiveEntry>,
}

impl LegacyInstallationArchive {
    fn backup_path(&self) -> Option<&Path> {
        self.entries.last().map(|entry| entry.backup.as_path())
    }

    fn restore(&self) -> Result<()> {
        let mut restorable = Vec::new();
        for entry in self.entries.iter().rev() {
            match fs::symlink_metadata(&entry.backup) {
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(error) => return Err(error.into()),
                Ok(_) => require_identity(
                    &entry.backup,
                    entry.identity,
                    "legacy archive",
                )?,
            }
            match fs::symlink_metadata(&entry.original) {
                Err(error) if error.kind() == io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
                Ok(_) => return Err(legacy_restore_conflict(entry)),
            }
            restorable.push(entry);
        }
        test_path_projection_barrier("before-legacy-restore-move")?;
        for entry in restorable {
            if let Some(parent) = entry.original.parent() {
                fs::create_dir_all(parent)?;
            }
            IdentityTransactionalMove::new(
                &entry.backup,
                &entry.original,
                entry.identity,
                "legacy archive selected for restoration",
            )
            .execute()
            .map_err(|error| {
                if fs::symlink_metadata(&entry.original).is_ok() {
                    let mut conflict = legacy_restore_conflict(entry);
                    conflict
                        .details
                        .insert("moveError".to_string(), error.to_string());
                    conflict
                } else {
                    error
                }
            })?;
            manifest::sync_parent_directory(&entry.backup)?;
            manifest::sync_parent_directory(&entry.original)?;
        }
        Ok(())
    }
}

#[derive(Debug)]
struct LegacyInstallationArchiveEntry {
    original: PathBuf,
    backup: PathBuf,
    identity: ProjectionFileIdentity,
}

fn legacy_restore_conflict(entry: &LegacyInstallationArchiveEntry) -> CliError {
    let mut error = CliError::new(
        "LEGACY_RESTORE_CONFLICT",
        format!(
            "Cannot restore archived Kast path {} because another path exists; preserved the archive at {}.",
            entry.original.display(),
            entry.backup.display(),
        ),
    );
    error.details.insert(
        "path".to_string(),
        entry.original.display().to_string(),
    );
    error.details.insert(
        "backupPath".to_string(),
        entry.backup.display().to_string(),
    );
    error
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupArtifact {
    pub role: String,
    pub path: String,
    pub sha256: String,
    pub verified: bool,
}

#[derive(Debug)]
struct ValidatedBundle {
    root: PathBuf,
    manifest: BundleManifest,
    version: BundleVersion,
    cli_relative: PathBuf,
    backend_install_relative: PathBuf,
    release_digest: String,
    manifest_digest: String,
}

#[derive(Debug)]
struct ActivationTargetPaths {
    resolved: manifest::ResolvedKastPaths,
    version_dir: PathBuf,
    current_link: PathBuf,
    previous_link: PathBuf,
    indexer_current_dir: PathBuf,
}
