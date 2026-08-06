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
        let is_executable_file = fs::metadata(path).is_ok_and(|metadata| metadata.is_file())
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
