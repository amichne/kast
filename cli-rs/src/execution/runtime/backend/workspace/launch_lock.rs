pub(crate) struct WorkspaceLaunchLock {
    _file: fs::File,
    storage_identity: crate::daemon::IndexerStorageIdentity,
}

impl WorkspaceLaunchLock {
    pub(crate) fn acquire_until(
        config: &KastConfig,
        workspace_root: &Path,
        deadline: RuntimeStartDeadline,
    ) -> Result<Self> {
        let storage_identity =
            crate::daemon::IndexerStorageIdentity::resolve(workspace_root, config)?;
        let lock_path = storage_identity.launch_lock_file();
        let mut options = fs::OpenOptions::new();
        options.create(true).truncate(false).read(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.custom_flags(libc::O_NOFOLLOW);
        }
        let file = options.open(&lock_path)?;
        loop {
            match workspace_launch_lock_attempt(&file).map_err(|error| {
                CliError::new(
                    "RUNTIME_LAUNCH_LOCK_ERROR",
                    format!(
                        "Cannot serialize runtime launch for {} with {}: {error}",
                        workspace_root.display(),
                        lock_path.display(),
                    ),
                )
            })? {
                WorkspaceLaunchLockAttempt::Acquired => break,
                WorkspaceLaunchLockAttempt::Contended if deadline.is_elapsed() => {
                    return Err(CliError::new(
                        "RUNTIME_LAUNCH_LOCK_TIMEOUT",
                        format!(
                            "Timed out waiting to serialize runtime launch for {} with {}.",
                            workspace_root.display(),
                            lock_path.display(),
                        ),
                    ));
                }
                WorkspaceLaunchLockAttempt::Contended => {
                    thread::sleep(deadline.remaining().min(Duration::from_millis(20)));
                }
            }
        }
        Ok(Self {
            _file: file,
            storage_identity,
        })
    }

    pub(crate) fn storage_identity(&self) -> &crate::daemon::IndexerStorageIdentity {
        &self.storage_identity
    }
}

enum WorkspaceLaunchLockAttempt {
    Acquired,
    Contended,
}

#[cfg(unix)]
fn workspace_launch_lock_attempt(file: &fs::File) -> std::io::Result<WorkspaceLaunchLockAttempt> {
    use std::os::fd::AsRawFd;

    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0 {
        Ok(WorkspaceLaunchLockAttempt::Acquired)
    } else {
        let error = std::io::Error::last_os_error();
        if error
            .raw_os_error()
            .is_some_and(|code| code == libc::EWOULDBLOCK || code == libc::EAGAIN)
        {
            Ok(WorkspaceLaunchLockAttempt::Contended)
        } else {
            Err(error)
        }
    }
}

#[cfg(not(unix))]
fn workspace_launch_lock_attempt(
    _file: &fs::File,
) -> std::io::Result<WorkspaceLaunchLockAttempt> {
    Ok(WorkspaceLaunchLockAttempt::Acquired)
}
