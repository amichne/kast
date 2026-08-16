#[derive(Debug)]
struct IndexerStorageAvailabilityProbe {
    file: fs::File,
    lease_file: PathBuf,
    parent_lock_held: bool,
}

impl IndexerStorageAvailabilityProbe {
    fn acquire(layout: &IndexerProjectLayout) -> Result<Self> {
        let file = fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(&layout.storage_lease_file)?;
        try_storage_lock(&file).map_err(|error| {
            if error
                .raw_os_error()
                .is_some_and(|code| code == libc::EACCES || code == libc::EAGAIN)
            {
                storage_in_use(layout.identity.workspace_root(), None)
            } else {
                CliError::new(
                    "INDEXER_STORAGE_LEASE_ERROR",
                    format!(
                        "Cannot inspect Kast indexer storage lease {}: {error}",
                        layout.storage_lease_file.display(),
                    ),
                )
            }
        })?;
        Ok(Self {
            file,
            lease_file: layout.storage_lease_file.clone(),
            parent_lock_held: true,
        })
    }

    #[cfg(unix)]
    fn arm_child_process(&mut self, process: &mut Command) -> Result<()> {
        use std::os::fd::AsRawFd;
        use std::os::unix::process::CommandExt;

        let file_descriptor = self.file.as_raw_fd();
        release_storage_lock(&self.file).map_err(|error| {
            CliError::new(
                "INDEXER_STORAGE_LEASE_ERROR",
                format!(
                    "Cannot transfer Kast indexer storage lease {} to the JVM: {error}",
                    self.lease_file.display(),
                ),
            )
        })?;
        self.parent_lock_held = false;
        // POSIX record locks are process-owned and survive exec. The child
        // acquires this same-file lock after fork and before Java can initialize
        // any IDEA path. The inherited descriptor keeps that lock for the JVM.
        unsafe {
            process.pre_exec(move || prepare_inherited_storage_lock(file_descriptor));
        }
        process.arg(format!("--storage-lease-fd={file_descriptor}"));
        Ok(())
    }

    #[cfg(not(unix))]
    fn arm_child_process(&mut self, _process: &mut Command) -> Result<()> {
        Err(CliError::new(
            "INDEXER_STORAGE_HANDOFF_UNSUPPORTED",
            "This platform cannot transfer the required indexer storage lease into the JVM.",
        ))
    }
}

#[cfg(unix)]
fn prepare_inherited_storage_lock(file_descriptor: std::os::fd::RawFd) -> std::io::Result<()> {
    if unsafe { libc::setsid() } == -1 {
        return Err(std::io::Error::last_os_error());
    }
    let descriptor_flags = unsafe { libc::fcntl(file_descriptor, libc::F_GETFD) };
    if descriptor_flags == -1
        || unsafe {
            libc::fcntl(
                file_descriptor,
                libc::F_SETFD,
                descriptor_flags & !libc::FD_CLOEXEC,
            )
        } == -1
    {
        return Err(std::io::Error::last_os_error());
    }
    set_storage_lock_fd(file_descriptor, libc::F_WRLCK.into(), libc::F_SETLK)
}

impl Drop for IndexerStorageAvailabilityProbe {
    fn drop(&mut self) {
        if self.parent_lock_held {
            let _ = release_storage_lock(&self.file);
        }
    }
}

#[cfg(unix)]
fn try_storage_lock(file: &fs::File) -> std::io::Result<()> {
    set_storage_lock(file, libc::F_WRLCK.into(), libc::F_SETLK)
}

#[cfg(unix)]
fn release_storage_lock(file: &fs::File) -> std::io::Result<()> {
    set_storage_lock(file, libc::F_UNLCK.into(), libc::F_SETLK)
}

#[cfg(unix)]
fn set_storage_lock(
    file: &fs::File,
    lock_type: libc::c_int,
    command: libc::c_int,
) -> std::io::Result<()> {
    use std::os::fd::AsRawFd;

    set_storage_lock_fd(file.as_raw_fd(), lock_type, command)
}

#[cfg(unix)]
fn set_storage_lock_fd(
    file_descriptor: std::os::fd::RawFd,
    lock_type: libc::c_int,
    command: libc::c_int,
) -> std::io::Result<()> {
    let mut lock = unsafe { std::mem::zeroed::<libc::flock>() };
    lock.l_type = lock_type as _;
    lock.l_whence = libc::SEEK_SET as _;
    if unsafe { libc::fcntl(file_descriptor, command, &lock) } == -1 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

#[cfg(not(unix))]
fn try_storage_lock(_file: &fs::File) -> std::io::Result<()> {
    Ok(())
}

#[cfg(not(unix))]
fn release_storage_lock(_file: &fs::File) -> std::io::Result<()> {
    Ok(())
}
