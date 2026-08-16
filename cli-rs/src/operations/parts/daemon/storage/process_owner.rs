fn require_no_legacy_indexer(workspace_root: &Path) -> Result<()> {
    for pid in process_ids(workspace_root)? {
        if pid != std::process::id() && legacy_indexer_process_for_pid(pid, workspace_root)? {
            return Err(storage_in_use(workspace_root, Some(pid)));
        }
    }
    Ok(())
}

pub(crate) fn require_admitted_storage_owner(
    identity: &IndexerStorageIdentity,
    pid: u32,
) -> Result<()> {
    let arguments = process_arguments(pid).map_err(|error| {
        storage_owner_unverified(
            identity,
            pid,
            format!("cannot inspect exact process arguments: {error}"),
        )
    })?;
    let exact_workspace = format!("--workspace-root={}", identity.workspace_root().display());
    let exact_storage = format!("--indexer-storage-root={}", identity.storage_root().display());
    let has_indexer_marker = arguments.iter().any(|argument| {
        argument == INDEXER_STARTER_COMMAND || argument == INDEXER_MAIN_CLASS
    });
    let has_lease_descriptor = arguments.iter().any(|argument| {
        argument
            .strip_prefix("--storage-lease-fd=")
            .is_some_and(|value| value.parse::<u32>().is_ok())
    });
    if !has_indexer_marker
        || !has_lease_descriptor
        || !arguments.contains(&exact_workspace)
        || !arguments.contains(&exact_storage)
    {
        return Err(storage_owner_unverified(
            identity,
            pid,
            "process arguments do not carry the admitted storage identity and lease descriptor",
        ));
    }
    let lease = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(identity.storage_lease_file())
        .map_err(|error| {
            storage_owner_unverified(identity, pid, format!("cannot open storage lease: {error}"))
        })?;
    match storage_lock_owner(&lease) {
        Ok(Some(owner)) if owner == pid => Ok(()),
        Ok(Some(owner)) => Err(storage_owner_unverified(
            identity,
            pid,
            format!("the canonical storage lease belongs to process {owner}"),
        )),
        Ok(None) => Err(storage_owner_unverified(
            identity,
            pid,
            "the canonical storage lease is not held cross-process",
        )),
        Err(error) => Err(storage_owner_unverified(
            identity,
            pid,
            format!("cannot inspect canonical storage lease: {error}"),
        )),
    }
}

#[cfg(unix)]
fn storage_lock_owner(file: &fs::File) -> std::io::Result<Option<u32>> {
    use std::os::fd::AsRawFd;

    let mut query = unsafe { std::mem::zeroed::<libc::flock>() };
    query.l_type = libc::F_WRLCK as _;
    query.l_whence = libc::SEEK_SET as _;
    if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETLK, &mut query) } == -1 {
        return Err(std::io::Error::last_os_error());
    }
    if query.l_type == libc::F_UNLCK as libc::c_short {
        Ok(None)
    } else if query.l_type == libc::F_WRLCK as libc::c_short && query.l_pid > 0 {
        Ok(u32::try_from(query.l_pid).ok())
    } else {
        Ok(None)
    }
}

#[cfg(not(unix))]
fn storage_lock_owner(_file: &fs::File) -> std::io::Result<Option<u32>> {
    Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "POSIX storage owner inspection is unavailable",
    ))
}

fn storage_owner_unverified(
    identity: &IndexerStorageIdentity,
    pid: u32,
    reason: impl Into<String>,
) -> CliError {
    CliError::new(
        "INDEXER_STORAGE_OWNER_UNVERIFIED",
        format!(
            "Process {pid} is not a verified storage owner for {}: {}.",
            identity.workspace_root().display(),
            reason.into(),
        ),
    )
}

fn storage_in_use(workspace_root: &Path, pid: Option<u32>) -> CliError {
    let owner = pid.map_or_else(
        || "another live indexer".to_string(),
        |value| format!("legacy indexer process {value}"),
    );
    CliError::new(
        "INDEXER_STORAGE_IN_USE",
        format!(
            "Kast indexer storage for {} is held by {owner}. Stop that exact-root indexer before retrying.",
            workspace_root.display(),
        ),
    )
}

fn legacy_indexer_process_for_pid(pid: u32, workspace_root: &Path) -> Result<bool> {
    let arguments = match process_arguments(pid) {
        Ok(arguments) => arguments,
        Err(error) if process_vanished_or_is_inaccessible(&error) => {
            return Ok(false);
        }
        Err(error) => {
            return Err(CliError::new(
                "INDEXER_STORAGE_LEASE_ERROR",
                format!("Cannot inspect process {pid} for an indexer storage owner: {error}"),
            ));
        }
    };
    let exact_root = format!("--workspace-root={}", workspace_root.display());
    let indexer_marker = arguments.iter().any(|argument| {
        argument == INDEXER_STARTER_COMMAND || argument == INDEXER_MAIN_CLASS
    });
    Ok(indexer_marker && arguments.iter().any(|argument| argument == &exact_root))
}

fn process_vanished_or_is_inaccessible(error: &std::io::Error) -> bool {
    matches!(
        error.kind(),
        std::io::ErrorKind::NotFound | std::io::ErrorKind::PermissionDenied
    ) || error
        .raw_os_error()
        .is_some_and(|code| {
            matches!(code, libc::ESRCH | libc::EPERM | libc::EACCES | libc::EINVAL)
        })
}

#[cfg(target_os = "linux")]
fn process_ids(_workspace_root: &Path) -> Result<Vec<u32>> {
    Ok(fs::read_dir("/proc")?
        .filter_map(std::result::Result::ok)
        .filter_map(|entry| entry.file_name().to_string_lossy().parse().ok())
        .collect())
}

#[cfg(target_os = "linux")]
fn process_arguments(pid: u32) -> std::io::Result<Vec<String>> {
    Ok(fs::read(format!("/proc/{pid}/cmdline"))?
        .split(|byte| *byte == 0)
        .filter(|value| !value.is_empty())
        .map(|value| String::from_utf8_lossy(value).into_owned())
        .collect())
}

#[cfg(target_os = "macos")]
fn process_ids(workspace_root: &Path) -> Result<Vec<u32>> {
    let output = Command::new("ps")
        .args(["-axww", "-o", "pid=,uid=,command="])
        .output()?;
    if !output.status.success() {
        return Err(CliError::new(
            "INDEXER_STORAGE_LEASE_ERROR",
            "Cannot enumerate processes before indexer storage admission.",
        ));
    }
    let effective_uid = u64::from(unsafe { libc::geteuid() });
    Ok(macos_process_candidates(
        &String::from_utf8_lossy(&output.stdout),
        effective_uid,
        workspace_root,
    ))
}

#[cfg(target_os = "macos")]
fn macos_process_candidates(output: &str, effective_uid: u64, workspace_root: &Path) -> Vec<u32> {
    let exact_root = format!("--workspace-root={}", workspace_root.display());
    output
        .lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let pid = fields.next()?.parse().ok()?;
            let uid: u64 = fields.next()?.parse().ok()?;
            let possible_indexer = line.contains(INDEXER_STARTER_COMMAND)
                || line.contains(INDEXER_MAIN_CLASS);
            (uid == effective_uid && possible_indexer && line.contains(&exact_root)).then_some(pid)
        })
        .collect()
}

#[cfg(target_os = "macos")]
fn process_arguments(pid: u32) -> std::io::Result<Vec<String>> {
    let mut mib = [libc::CTL_KERN, libc::KERN_PROCARGS2, pid as libc::c_int];
    let mut size = 0usize;
    if unsafe {
        libc::sysctl(
            mib.as_mut_ptr(),
            mib.len() as _,
            std::ptr::null_mut(),
            &mut size,
            std::ptr::null_mut(),
            0,
        )
    } == -1
    {
        return Err(std::io::Error::last_os_error());
    }
    let mut buffer = vec![0u8; size];
    if unsafe {
        libc::sysctl(
            mib.as_mut_ptr(),
            mib.len() as _,
            buffer.as_mut_ptr().cast(),
            &mut size,
            std::ptr::null_mut(),
            0,
        )
    } == -1
    {
        return Err(std::io::Error::last_os_error());
    }
    buffer.truncate(size);
    parse_macos_process_arguments(&buffer)
}

#[cfg(target_os = "macos")]
fn parse_macos_process_arguments(buffer: &[u8]) -> std::io::Result<Vec<String>> {
    let count_bytes: [u8; 4] = buffer
        .get(..4)
        .and_then(|value| value.try_into().ok())
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidData, "missing argc"))?;
    let argument_count = usize::try_from(i32::from_ne_bytes(count_bytes))
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidData, "invalid argc"))?;
    let mut cursor = 4;
    while cursor < buffer.len() && buffer[cursor] != 0 {
        cursor += 1;
    }
    while cursor < buffer.len() && buffer[cursor] == 0 {
        cursor += 1;
    }
    let mut arguments = Vec::with_capacity(argument_count);
    while cursor < buffer.len() && arguments.len() < argument_count {
        let end = buffer[cursor..]
            .iter()
            .position(|byte| *byte == 0)
            .map(|offset| cursor + offset)
            .unwrap_or(buffer.len());
        arguments.push(String::from_utf8_lossy(&buffer[cursor..end]).into_owned());
        cursor = end.saturating_add(1);
    }
    Ok(arguments)
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
fn process_ids(_workspace_root: &Path) -> Result<Vec<u32>> {
    Ok(Vec::new())
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
fn process_arguments(_pid: u32) -> std::io::Result<Vec<String>> {
    Ok(Vec::new())
}
