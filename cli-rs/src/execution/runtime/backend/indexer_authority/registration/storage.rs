use super::*;
use serde::de::DeserializeOwned;
use std::io::{Read as _, Write as _};

pub(crate) struct InstallUseLock {
    _file: fs::File,
}

impl InstallUseLock {
    pub(crate) fn acquire() -> Result<Self> {
        let paths = crate::manifest::resolve_paths()?;
        fs::create_dir_all(&paths.locks_dir)?;
        let file = fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(paths.install_root.join("setup.lock"))?;
        lock_shared(&file)?;
        Ok(Self { _file: file })
    }
}

#[cfg(unix)]
fn lock_shared(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd as _;
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_SH) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn lock_shared(_file: &fs::File) -> Result<()> {
    Ok(())
}

pub(super) fn canonical_executable(value: &str) -> Result<String> {
    let path = Path::new(value);
    let resolved = if path.is_absolute() {
        fs::canonicalize(path)?
    } else {
        std::env::var_os("PATH")
            .map(|path| std::env::split_paths(&path).collect::<Vec<_>>())
            .unwrap_or_default()
            .into_iter()
            .map(|directory| directory.join(path))
            .find(|candidate| executable_file(candidate))
            .map(fs::canonicalize)
            .transpose()?
            .ok_or_else(|| registration_invalid("Indexer executable could not be resolved."))?
    };
    if !executable_file(&resolved) {
        return Err(registration_invalid(
            "Indexer executable is not an executable regular file.",
        ));
    }
    Ok(resolved.display().to_string())
}

pub(super) fn read_owned_json<T: DeserializeOwned>(path: &Path) -> Result<(T, Vec<u8>)> {
    let bytes = read_stable_file(path, true)?;
    let value = serde_json::from_slice(&bytes).map_err(|error| {
        registration_invalid(&format!("Runtime registration JSON is invalid: {error}"))
    })?;
    Ok((value, bytes))
}

pub(crate) fn sha256_stable_file(path: &Path, private: bool) -> Result<String> {
    Ok(crate::manifest::sha256_bytes(&read_stable_file(
        path, private,
    )?))
}

pub(super) fn write_atomic_json(path: &Path, value: &impl Serialize) -> Result<()> {
    let bytes = serde_json::to_vec_pretty(value)?;
    let parent = path
        .parent()
        .ok_or_else(|| registration_invalid("Runtime registration path has no parent."))?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".runtime-{}.tmp", Uuid::new_v4()));
    write_durable_file(&temporary, &bytes)?;
    fs::rename(&temporary, path)?;
    sync_parent(path)
}

pub(super) fn write_durable_file(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut options = fs::OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt as _;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    file.write_all(bytes)?;
    set_owner_only_file(path)?;
    file.sync_all()?;
    Ok(())
}

pub(super) fn require_owned_directory(path: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.file_type().is_dir() {
        return Err(registration_invalid(
            "Runtime registration root must be a directory.",
        ));
    }
    validate_directory_owner_and_mode(&metadata)
}

pub(super) fn sync_parent(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::File::open(parent)?.sync_all()?;
    }
    Ok(())
}

pub(crate) fn read_stable_file(path: &Path, private: bool) -> Result<Vec<u8>> {
    let mut options = fs::OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt as _;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    let mut file = options.open(path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            CliError::new(
                "RUNTIME_REGISTRATION_MISSING",
                format!("Missing runtime registration file {}.", path.display()),
            )
        } else {
            error.into()
        }
    })?;
    let metadata = file.metadata()?;
    if !metadata.file_type().is_file() {
        return Err(registration_invalid(
            "Runtime registration paths must be regular files.",
        ));
    }
    validate_file_owner_and_mode(&metadata, private)?;
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes)?;
    let confirmed = file.metadata()?;
    if !same_file_identity(&metadata, &confirmed) {
        return Err(registration_invalid(
            "Runtime registration file identity changed while it was read.",
        ));
    }
    Ok(bytes)
}

#[cfg(unix)]
fn executable_file(path: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt as _;
    fs::metadata(path).is_ok_and(|metadata| {
        metadata.file_type().is_file() && metadata.permissions().mode() & 0o111 != 0
    })
}

#[cfg(not(unix))]
fn executable_file(path: &Path) -> bool {
    path.is_file()
}

#[cfg(unix)]
pub(super) fn set_owner_only_file(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt as _;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    Ok(())
}

#[cfg(unix)]
pub(super) fn set_owner_only_directory(path: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt as _;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

#[cfg(unix)]
fn validate_file_owner_and_mode(metadata: &fs::Metadata, private: bool) -> Result<()> {
    use std::os::unix::fs::{MetadataExt as _, PermissionsExt as _};
    if metadata.uid() != effective_uid() as u32
        || (private && metadata.permissions().mode() & 0o077 != 0)
    {
        return Err(registration_invalid(
            "Runtime registration file ownership or permissions are unsafe.",
        ));
    }
    Ok(())
}

#[cfg(unix)]
fn same_file_identity(left: &fs::Metadata, right: &fs::Metadata) -> bool {
    use std::os::unix::fs::MetadataExt as _;
    left.dev() == right.dev()
        && left.ino() == right.ino()
        && left.len() == right.len()
        && left.mtime() == right.mtime()
        && left.mtime_nsec() == right.mtime_nsec()
}

#[cfg(unix)]
fn validate_directory_owner_and_mode(metadata: &fs::Metadata) -> Result<()> {
    use std::os::unix::fs::{MetadataExt as _, PermissionsExt as _};
    if metadata.uid() != effective_uid() as u32 || metadata.permissions().mode() & 0o077 != 0 {
        return Err(registration_invalid(
            "Runtime registration directory ownership or permissions are unsafe.",
        ));
    }
    Ok(())
}

#[cfg(not(unix))]
pub(super) fn set_owner_only_file(_path: &Path) -> Result<()> {
    Ok(())
}

#[cfg(not(unix))]
pub(super) fn set_owner_only_directory(_path: &Path) -> Result<()> {
    Ok(())
}

#[cfg(not(unix))]
fn validate_file_owner_and_mode(_metadata: &fs::Metadata, _private: bool) -> Result<()> {
    Ok(())
}

#[cfg(not(unix))]
fn same_file_identity(left: &fs::Metadata, right: &fs::Metadata) -> bool {
    left.len() == right.len()
}

#[cfg(not(unix))]
fn validate_directory_owner_and_mode(_metadata: &fs::Metadata) -> Result<()> {
    Ok(())
}
