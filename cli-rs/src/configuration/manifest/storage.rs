pub(crate) fn write_manifest_atomic(path: &Path, manifest: &KastInstallManifest) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    test_install_file_barrier("before-receipt-temporary-create", path)?;
    let (temp, mut file) = create_unique_temporary_file(path, "receipt")?;
    file.write_all(serde_json::to_vec_pretty(manifest)?.as_slice())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(&temp, path)?;
    test_install_durability_failure_at("after-receipt-rename-before-parent-sync", path)?;
    sync_parent_directory(path)
}

pub(crate) fn create_unique_temporary_file(
    path: &Path,
    purpose: &str,
) -> Result<(PathBuf, fs::File)> {
    use std::sync::atomic::{AtomicU64, Ordering};
    static TEMPORARY_FILE_COUNTER: AtomicU64 = AtomicU64::new(0);
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("kast");
    for _ in 0..16 {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or_default();
        let sequence = TEMPORARY_FILE_COUNTER.fetch_add(1, Ordering::Relaxed);
        let temporary = path.with_file_name(format!(
            ".{file_name}.kast-{purpose}-{}-{nonce}-{sequence}.tmp",
            std::process::id(),
        ));
        match OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&temporary)
        {
            Ok(file) => return Ok((temporary, file)),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error.into()),
        }
    }
    Err(CliError::new(
        "INSTALL_TEMPORARY_PATH_EXHAUSTED",
        format!(
            "Could not allocate a unique temporary path beside {}.",
            path.display(),
        ),
    ))
}

pub(crate) fn sync_parent_directory(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::File::open(parent)?.sync_all()?;
    }
    Ok(())
}

pub(crate) fn test_install_durability_failure(point: &str) -> Result<()> {
    if env::var("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION").as_deref() == Ok("1")
        && env::var("KAST_TEST_SETUP_DURABILITY_FAILURE_POINT").as_deref() == Ok(point)
    {
        let mut error = CliError::new(
            "SETUP_TEST_DURABILITY_FAILURE",
            format!("Injected setup durability failure at `{point}`."),
        );
        error
            .details
            .insert("durabilityPoint".to_string(), point.to_string());
        return Err(error);
    }
    Ok(())
}

fn test_install_durability_failure_at(point: &str, path: &Path) -> Result<()> {
    if let Some(expected_path) = env::var_os("KAST_TEST_SETUP_DURABILITY_FAILURE_PATH")
        && Path::new(&expected_path) != path
    {
        return Ok(());
    }
    test_install_durability_failure(point)
}

fn test_install_file_barrier(stage: &str, path: &Path) -> Result<()> {
    if env::var("KAST_TEST_ALLOW_SETUP_FAULT_INJECTION").as_deref() != Ok("1")
        || env::var("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_STAGE").as_deref() != Ok(stage)
    {
        return Ok(());
    }
    if let Some(expected_path) = env::var_os("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER_PATH")
        && Path::new(&expected_path) != path
    {
        return Ok(());
    }
    let Some(directory) = env::var_os("KAST_TEST_SETUP_PATH_PROJECTION_BARRIER") else {
        return Ok(());
    };
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

pub(crate) fn with_install_lock<T>(
    paths: &ResolvedKastPaths,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    fs::create_dir_all(&paths.locks_dir)?;
    let lock_path = paths.install_root.join("setup.lock");
    let lock_file = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(&lock_path)?;
    test_install_file_barrier("before-install-lock-acquire", &lock_path)?;
    lock_exclusive(&lock_file)?;
    let result = action();
    unlock(&lock_file)?;
    result
}

#[cfg(unix)]
fn lock_exclusive(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;
    let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(unix)]
fn unlock(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd;
    let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_UN) };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn lock_exclusive(_file: &fs::File) -> Result<()> {
    Ok(())
}

#[cfg(not(unix))]
fn unlock(_file: &fs::File) -> Result<()> {
    Ok(())
}

#[cfg(unix)]
pub(crate) fn make_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(path, permissions)?;
    Ok(())
}

#[cfg(not(unix))]
pub(crate) fn make_executable(_path: &Path) -> Result<()> {
    Ok(())
}

pub(crate) fn replace_symlink_or_copy(target: &Path, link: &Path) -> Result<()> {
    if let Some(parent) = link.parent() {
        fs::create_dir_all(parent)?;
    }
    #[cfg(unix)]
    {
        let temporary = link.with_extension(format!("tmp-{}", std::process::id()));
        remove_path(&temporary)?;
        if fs::symlink_metadata(link)
            .is_ok_and(|metadata| metadata.is_dir() && !metadata.file_type().is_symlink())
        {
            remove_path(link)?;
        }
        std::os::unix::fs::symlink(target, &temporary)?;
        fs::rename(&temporary, link)?;
        Ok(())
    }
    #[cfg(not(unix))]
    {
        remove_path(link)?;
        copy_dir(target, link)
    }
}

pub(crate) fn remove_path(path: &Path) -> Result<()> {
    let Ok(metadata) = fs::symlink_metadata(path) else {
        return Ok(());
    };
    if metadata.is_dir() && !metadata.file_type().is_symlink() {
        fs::remove_dir_all(path)?;
    } else {
        fs::remove_file(path)?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn copy_dir(source: &Path, target: &Path) -> Result<()> {
    fs::create_dir_all(target)?;
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let source_path = entry.path();
        let target_path = target.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir(&source_path, &target_path)?;
        } else {
            fs::copy(&source_path, &target_path)?;
        }
    }
    Ok(())
}

pub(crate) fn owned_paths(paths: &ResolvedKastPaths) -> Vec<String> {
    vec![
        paths.shim_path.clone(),
        paths.install_root.join("current"),
        paths.install_root.join("previous"),
        paths.install_root.join("releases"),
        paths.install_root.join("staging"),
        paths.runtime_dir.clone(),
        paths.locks_dir.clone(),
    ]
    .into_iter()
    .map(|path| path.display().to_string())
    .collect()
}

pub(crate) fn current_timestamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default();
    format!("unix:{seconds}")
}

pub(crate) fn sha256_bytes(bytes: &[u8]) -> String {
    let mut digest = Sha256::new();
    digest.update(bytes);
    hex::encode(digest.finalize())
}

pub(crate) fn sha256_file(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 1024 * 64];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(hex::encode(digest.finalize()))
}

fn tool_name() -> String {
    TOOL_NAME.to_string()
}

fn default_profile() -> String {
    DEFAULT_PROFILE.to_string()
}

fn schema_version() -> u32 {
    INSTALL_RECEIPT_SCHEMA_VERSION
}

pub fn home_dir() -> PathBuf {
    env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

fn env_path(name: &str) -> Option<PathBuf> {
    env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(normalize)
}

pub fn normalize(path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        path
    } else {
        env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    }
    .components()
    .collect()
}
