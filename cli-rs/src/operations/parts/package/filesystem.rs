fn copy_dir_recursive(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() {
        return Err(CliError::new(
            "PACKAGE_ARCHIVE_INVALID",
            format!(
                "Archive content must not contain symlinks: {}",
                source.display()
            ),
        ));
    }
    if metadata.is_dir() {
        fs::create_dir_all(target)?;
        let mut entries = fs::read_dir(source)?.collect::<std::result::Result<Vec<_>, _>>()?;
        entries.sort_by_key(|entry| entry.path());
        for entry in entries {
            copy_dir_recursive(&entry.path(), &target.join(entry.file_name()))?;
        }
    } else if metadata.is_file() {
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(source, target)?;
        fs::set_permissions(target, metadata.permissions())?;
    }
    Ok(())
}

fn file_sha256(path: &Path) -> Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 8192];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn path_sha256(path: &Path) -> Result<String> {
    if path.is_file() {
        return file_sha256(path);
    }
    require_directory(path, "artifact directory")?;
    let mut files = Vec::new();
    fn collect(root: &Path, directory: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
        for entry in fs::read_dir(directory)? {
            let entry = entry?;
            let metadata = fs::symlink_metadata(entry.path())?;
            if metadata.file_type().is_symlink() {
                return Err(CliError::new(
                    "PACKAGE_ARCHIVE_INVALID",
                    format!("Artifact contains a symlink: {}", entry.path().display()),
                ));
            }
            if metadata.is_dir() {
                collect(root, &entry.path(), files)?;
            } else if metadata.is_file() {
                files.push(
                    entry
                        .path()
                        .strip_prefix(root)
                        .expect("artifact child")
                        .to_path_buf(),
                );
            }
        }
        Ok(())
    }
    collect(path, path, &mut files)?;
    files.sort();
    let mut digest = Sha256::new();
    for relative in files {
        digest.update(relative.to_string_lossy().as_bytes());
        digest.update(b"\n");
        digest.update(file_sha256(&path.join(&relative))?.as_bytes());
        digest.update(b"\n");
    }
    Ok(hex::encode(digest.finalize()))
}

fn build_commit(repo_root: &Path) -> String {
    crate::git::ReadOnlyGitCommand::from_command(ProcessCommand::new("git"))
        .arg("-C")
        .arg(repo_root)
        .args(["rev-parse", "HEAD"])
        .output()
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "unknown".to_string())
}

fn require_file(path: &Path, label: &str) -> Result<()> {
    if path.is_file() {
        Ok(())
    } else {
        Err(CliError::new(
            "PACKAGE_INPUT_MISSING",
            format!("Missing {label}: {}", path.display()),
        ))
    }
}

fn require_directory(path: &Path, label: &str) -> Result<()> {
    if path.is_dir() {
        Ok(())
    } else {
        Err(CliError::new(
            "PACKAGE_INPUT_MISSING",
            format!("Missing {label}: {}", path.display()),
        ))
    }
}

fn remove_if_exists(path: &Path) -> Result<()> {
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

struct ScratchDir {
    path: PathBuf,
}

impl ScratchDir {
    fn new(label: &str) -> Result<Self> {
        let suffix = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or_default();
        let path = env::temp_dir().join(format!("{label}-{}-{suffix}", std::process::id()));
        remove_if_exists(&path)?;
        fs::create_dir_all(&path)?;
        Ok(Self { path })
    }

    fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for ScratchDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

#[cfg(unix)]
fn make_executable(path: &Path) -> Result<()> {
    set_mode(path, 0o755)
}

#[cfg(not(unix))]
fn make_executable(_path: &Path) -> Result<()> {
    Ok(())
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    let mut permissions = fs::metadata(path)?.permissions();
    permissions.set_mode(mode);
    fs::set_permissions(path, permissions)?;
    Ok(())
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) -> Result<()> {
    Ok(())
}

fn zip_entry_is_symlink(mode: Option<u32>) -> bool {
    mode.is_some_and(|mode| mode & 0o170000 == 0o120000)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_tar_gz_uses_fast_compression() {
        let temp = tempfile::tempdir().expect("tempdir");
        let source = temp.path().join("source");
        let output = temp.path().join("bundle.tar.gz");
        fs::create_dir(&source).expect("source directory");
        fs::write(source.join("payload"), vec![0_u8; 1024]).expect("payload");

        write_tar_gz(&source, "bundle", &output).expect("bundle archive");

        assert_eq!(fs::read(output).expect("gzip archive")[8], 4);
    }
}
