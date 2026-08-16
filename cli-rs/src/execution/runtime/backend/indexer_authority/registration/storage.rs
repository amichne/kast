use super::*;
use serde::de::DeserializeOwned;
use std::io::Write as _;

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
            .map(|candidate| fs::canonicalize(candidate))
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
    let metadata = owned_file_metadata(path)?;
    if !metadata.file_type().is_file() {
        return Err(registration_invalid(
            "Runtime registration paths must be regular files.",
        ));
    }
    validate_file_owner_and_mode(&metadata)?;
    let bytes = fs::read(path)?;
    let value = serde_json::from_slice(&bytes).map_err(|error| {
        registration_invalid(&format!("Runtime registration JSON is invalid: {error}"))
    })?;
    Ok((value, bytes))
}

pub(super) fn write_atomic_json(path: &Path, value: &impl Serialize) -> Result<()> {
    let bytes = serde_json::to_vec_pretty(value)?;
    let parent = path.parent().ok_or_else(|| {
        registration_invalid("Runtime registration path has no parent.")
    })?;
    fs::create_dir_all(parent)?;
    let temporary = parent.join(format!(".runtime-{}.tmp", Uuid::new_v4()));
    write_durable_file(&temporary, &bytes)?;
    fs::rename(&temporary, path)?;
    sync_parent(path)
}

pub(super) fn write_durable_file(path: &Path, bytes: &[u8]) -> Result<()> {
    let mut file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(path)?;
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

fn owned_file_metadata(path: &Path) -> Result<fs::Metadata> {
    fs::symlink_metadata(path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            CliError::new(
                "RUNTIME_REGISTRATION_MISSING",
                format!("Missing runtime registration file {}.", path.display()),
            )
        } else {
            error.into()
        }
    })
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
fn validate_file_owner_and_mode(metadata: &fs::Metadata) -> Result<()> {
    use std::os::unix::fs::{MetadataExt as _, PermissionsExt as _};
    if metadata.uid() != effective_uid() as u32 || metadata.permissions().mode() & 0o077 != 0 {
        return Err(registration_invalid(
            "Runtime registration file ownership or permissions are unsafe.",
        ));
    }
    Ok(())
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
fn validate_file_owner_and_mode(_metadata: &fs::Metadata) -> Result<()> {
    Ok(())
}

#[cfg(not(unix))]
fn validate_directory_owner_and_mode(_metadata: &fs::Metadata) -> Result<()> {
    Ok(())
}
