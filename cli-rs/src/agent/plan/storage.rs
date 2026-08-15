struct PlanPaths {
    directory: PathBuf,
    plan: PathBuf,
    content: PathBuf,
    recovery: PathBuf,
    lock: PathBuf,
}

impl PlanPaths {
    fn new(plan_id: Uuid) -> Self {
        let directory = manifest::default_install_root().join("state/agent-plans");
        let id = plan_id.hyphenated();
        Self {
            plan: directory.join(format!("{id}.json")),
            content: directory.join(format!("{id}.content")),
            recovery: directory.join(format!("{id}.recovery.json")),
            lock: directory.join(format!("{id}.lock")),
            directory,
        }
    }
}

struct PlanOperationLock {
    file: File,
}

impl PlanOperationLock {
    fn acquire(path: &Path) -> Result<Self> {
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600).custom_flags(libc::O_NOFOLLOW);
        }
        let file = options.open(path)?;
        if !file.metadata()?.is_file() {
            return Err(CliError::new(
                "KAST_PLAN_LOCK_INVALID",
                "The per-plan mutation lock is not a regular file.",
            ));
        }
        set_mode(path, 0o600)?;
        file.sync_all()?;
        sync_directory(
            path.parent()
                .expect("private plan locks always have a parent directory"),
        )?;
        #[cfg(unix)]
        {
            use std::os::fd::AsRawFd;
            if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
                let error = std::io::Error::last_os_error();
                if error.kind() == std::io::ErrorKind::WouldBlock {
                    return Err(CliError::new(
                        "KAST_PLAN_BUSY",
                        "Another apply or recover process owns this plan's exclusive lock.",
                    ));
                }
                return Err(error.into());
            }
        }
        Ok(Self { file })
    }
}

impl Drop for PlanOperationLock {
    fn drop(&mut self) {
        #[cfg(unix)]
        {
            use std::os::fd::AsRawFd;
            let _ = unsafe { libc::flock(self.file.as_raw_fd(), libc::LOCK_UN) };
        }
    }
}

fn canonical_workspace_root() -> Result<PathBuf> {
    let root = config::resolve_workspace_root(None)?;
    root.canonicalize().map_err(|error| {
        CliError::new(
            "WORKSPACE_ROOT_UNAVAILABLE",
            format!(
                "The workspace root {} could not be canonicalized: {error}",
                root.display()
            ),
        )
    })
}

fn validate_plan(plan: &StoredPlan, expected_id: Uuid) -> Result<()> {
    if plan.schema_version != PLAN_SCHEMA_VERSION {
        return Err(CliError::new(
            "KAST_PLAN_VERSION_UNSUPPORTED",
            format!(
                "Plan {} uses unsupported private schema version {}.",
                plan.plan_id, plan.schema_version
            ),
        ));
    }
    if plan.plan_id != expected_id {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The stored change plan identity does not match its requested id.",
        ));
    }
    if plan.operation.requires_content() != plan.content_sha256.is_some() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            "The stored change plan has inconsistent content evidence.",
        ));
    }
    plan.operation.validate().map_err(|message| {
        CliError::new(
            "KAST_PLAN_INVALID",
            format!("The stored change authority is invalid: {message}"),
        )
    })?;
    plan.operation
        .validate_content_sha256(plan.content_sha256.as_deref())
        .map_err(|message| {
            CliError::new(
                "KAST_PLAN_INVALID",
                format!("The stored content authority is invalid: {message}"),
            )
        })?;
    if let StoredPlanState::Terminal { receipt } = &plan.state {
        receipt.validate_for(plan)?;
    }
    Ok(())
}

fn read_plan(path: &Path, plan_id: Uuid) -> Result<StoredPlan> {
    let bytes = read_private_file(path, "KAST_PLAN_UNAVAILABLE")?;
    let plan: StoredPlan = serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "KAST_PLAN_INVALID",
            format!("The stored change plan is malformed: {error}"),
        )
    })?;
    validate_plan(&plan, plan_id)?;
    Ok(plan)
}

fn read_stdin() -> Result<Vec<u8>> {
    let mut stdin = std::io::stdin();
    if stdin.is_terminal() {
        return Err(CliError::new(
            "CLI_USAGE",
            "Pipe the Kotlin content to stdin.",
        ));
    }
    let mut content = Vec::new();
    stdin.read_to_end(&mut content)?;
    if content.is_empty() {
        return Err(CliError::new(
            "CLI_USAGE",
            "Piped Kotlin content must not be empty.",
        ));
    }
    Ok(content)
}

include!("parts/storage/private_directory.rs");

fn write_plan(path: &Path, plan: &StoredPlan) -> Result<()> {
    let mut encoded = serde_json::to_vec(plan)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    if let Err(error) = rename_private_file(&temporary, path) {
        remove_if_exists(&temporary);
        return Err(error);
    }
    Ok(())
}

fn replace_plan(path: &Path, plan: &StoredPlan) -> Result<()> {
    let mut encoded = serde_json::to_vec(plan)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    if let Err(error) = fs::rename(&temporary, path).map_err(CliError::from) {
        remove_if_exists(&temporary);
        return Err(error);
    }
    sync_directory(
        path.parent()
            .expect("private plan files always have a parent directory"),
    )
}

fn write_recovery(path: &Path, journal: &RecoveryJournal) -> Result<()> {
    let mut encoded = serde_json::to_vec(journal)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    if let Err(error) = rename_private_file(&temporary, path) {
        remove_if_exists(&temporary);
        return Err(error);
    }
    Ok(())
}

fn replace_recovery(path: &Path, journal: &RecoveryJournal) -> Result<()> {
    let mut encoded = serde_json::to_vec(journal)?;
    encoded.push(b'\n');
    let temporary = path.with_extension(format!("json.tmp-{}", Uuid::new_v4()));
    write_private_file(&temporary, &encoded)?;
    if let Err(error) = fs::rename(&temporary, path).map_err(CliError::from) {
        remove_if_exists(&temporary);
        return Err(error);
    }
    sync_directory(
        path.parent()
            .expect("private recovery files always have a parent directory"),
    )
}

fn read_recovery(path: &Path, recovery_id: Uuid, plan: &StoredPlan) -> Result<RecoveryJournal> {
    let bytes = read_private_file(path, "KAST_RECOVERY_UNAVAILABLE")?;
    let journal: RecoveryJournal = serde_json::from_slice(&bytes).map_err(|error| {
        CliError::new(
            "KAST_RECOVERY_INVALID",
            format!("The stored recovery journal is malformed: {error}"),
        )
    })?;
    journal.validate(recovery_id, plan).map_err(|error| {
        CliError::new(
            "KAST_RECOVERY_INVALID",
            format!("The stored recovery journal failed validation: {}", error.message),
        )
    })?;
    Ok(journal)
}

fn write_private_file(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut file = options.open(path)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        set_mode(path, 0o600)
    })();
    if result.is_err() {
        remove_if_exists(path);
    }
    result
}

fn rename_private_file(from: &Path, to: &Path) -> Result<()> {
    match fs::symlink_metadata(to) {
        Ok(_) => {
            return Err(CliError::new(
                "KAST_PLAN_ALREADY_EXISTS",
                format!(
                    "Refusing to overwrite private plan data at {}.",
                    to.display()
                ),
            ));
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    fs::rename(from, to)?;
    if MutationFailurePoint::RecoveryJournalDirectorySync.active()
        && to
            .to_string_lossy()
            .ends_with(".recovery.json")
    {
        return Err(CliError::new(
            "KAST_TEST_RECOVERY_JOURNAL_DIRECTORY_SYNC_FAILED",
            "Recovery journal directory sync failed at the deterministic post-rename test seam.",
        ));
    }
    sync_directory(
        to.parent()
            .expect("private plan files always have a parent directory"),
    )
}

fn read_private_file(path: &Path, missing_code: &'static str) -> Result<Vec<u8>> {
    let metadata = fs::symlink_metadata(path).map_err(|error| {
        CliError::new(
            missing_code,
            format!(
                "Private plan data {} is unavailable: {error}",
                path.display()
            ),
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.is_file() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            format!(
                "Private plan data {} is not a regular file.",
                path.display()
            ),
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o077 != 0 {
            return Err(CliError::new(
                "KAST_PLAN_PERMISSIONS_INVALID",
                format!(
                    "Private plan data {} is readable outside its owner.",
                    path.display()
                ),
            ));
        }
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW);
    }
    let mut file = options.open(path)?;
    let opened = file.metadata()?;
    if !opened.is_file() {
        return Err(CliError::new(
            "KAST_PLAN_INVALID",
            format!(
                "Private plan data {} changed while opening.",
                path.display()
            ),
        ));
    }
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)?;
    Ok(bytes)
}

fn set_mode(path: &Path, mode: u32) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
    }
    #[cfg(not(unix))]
    let _ = (path, mode);
    Ok(())
}

fn sync_directory(path: &Path) -> Result<()> {
    File::open(path)?.sync_all()?;
    Ok(())
}

include!("parts/storage/projection.rs");

#[cfg(test)]
include!("parts/storage/private_directory_test.rs");

fn remove_if_exists(path: &Path) {
    let _ = fs::remove_file(path);
}
