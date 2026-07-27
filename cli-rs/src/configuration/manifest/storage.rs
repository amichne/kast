pub(crate) fn write_manifest_atomic(path: &Path, manifest: &KastInstallManifest) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temp = path.with_extension(format!("json.tmp-{}", std::process::id()));
    let mut file = fs::File::create(&temp)?;
    file.write_all(serde_json::to_vec_pretty(manifest)?.as_slice())?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(&temp, path)?;
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
        .open(lock_path)?;
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
