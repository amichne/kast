struct WorkspaceLaunchLock {
    _file: fs::File,
}

impl WorkspaceLaunchLock {
    fn acquire(config: &KastConfig, workspace_root: &Path) -> Result<Self> {
        let lock_directory = config.paths.runtime_dir.join("workspace-launch-locks");
        fs::create_dir_all(&lock_directory)?;
        let lock_path =
            lock_directory.join(format!("{}.lock", config::workspace_hash(workspace_root),));
        let file = fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(&lock_path)?;
        workspace_launch_lock(&file).map_err(|error| {
            CliError::new(
                "RUNTIME_LAUNCH_LOCK_ERROR",
                format!(
                    "Cannot serialize runtime launch for {} with {}: {}",
                    workspace_root.display(),
                    lock_path.display(),
                    error.message,
                ),
            )
        })?;
        Ok(Self { _file: file })
    }
}

#[cfg(unix)]
fn workspace_launch_lock(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;

    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn workspace_launch_lock(_file: &fs::File) -> Result<()> {
    Ok(())
}
