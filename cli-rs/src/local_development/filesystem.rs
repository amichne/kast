fn copy_directory_tree(source: &Path, target: &Path) -> Result<()> {
    if !source.is_dir() {
        return Err(CliError::new(
            "LOCAL_COMPONENT_MISSING",
            format!("Local component directory is missing: {}", source.display()),
        ));
    }
    fs::create_dir_all(target)?;
    let mut entries = fs::read_dir(source)?.collect::<std::io::Result<Vec<_>>>()?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        let source_path = entry.path();
        let target_path = target.join(entry.file_name());
        let file_type = entry.file_type()?;
        if file_type.is_dir() {
            copy_directory_tree(&source_path, &target_path)?;
        } else if file_type.is_file() {
            fs::copy(&source_path, &target_path)?;
            fs::set_permissions(&target_path, fs::metadata(&source_path)?.permissions())?;
        } else {
            return Err(CliError::new(
                "LOCAL_COMPONENT_ENTRY_UNSUPPORTED",
                format!(
                    "Local component refuses non-file, non-directory entry {}.",
                    source_path.display()
                ),
            ));
        }
    }
    Ok(())
}

fn tree_sha256(root: &Path) -> Result<Sha256Digest> {
    if root.is_file() {
        return Sha256Digest::try_from(crate::manifest::sha256_file(root)?);
    }
    if !root.is_dir() {
        return Err(CliError::new(
            "LOCAL_COMPONENT_MISSING",
            format!("Local component target is missing: {}", root.display()),
        ));
    }
    let mut paths = Vec::new();
    collect_tree_paths(root, root, &mut paths)?;
    paths.sort_by_key(|path| path_identity_bytes(path));
    let mut digest = Sha256::new();
    digest.update(b"kast-local-component-v1\0");
    digest.update((paths.len() as u64).to_be_bytes());
    for relative in paths {
        let identity = path_identity_bytes(&relative);
        update_framed_bytes(&mut digest, &identity);
        let path = root.join(&relative);
        let metadata = fs::symlink_metadata(&path)?;
        let mut entry_digest = Sha256::new();
        if metadata.is_dir() {
            entry_digest.update(b"directory\0");
        } else if metadata.is_file() {
            entry_digest.update(b"file\0");
            entry_digest.update([executable_marker(&metadata)]);
            entry_digest.update(metadata.len().to_be_bytes());
            let mut file = fs::File::open(&path)?;
            let mut buffer = [0_u8; 64 * 1024];
            let mut total = 0_u64;
            loop {
                let read = file.read(&mut buffer)?;
                if read == 0 {
                    break;
                }
                entry_digest.update(&buffer[..read]);
                total += read as u64;
            }
            if total != metadata.len() {
                return Err(CliError::new(
                    "LOCAL_COMPONENT_CHANGED",
                    format!("Component file length changed while hashing {}.", path.display()),
                ));
            }
        } else {
            return Err(CliError::new(
                "LOCAL_COMPONENT_ENTRY_UNSUPPORTED",
                format!("Unsupported installed component entry: {}", path.display()),
            ));
        }
        digest.update(entry_digest.finalize());
    }
    Sha256Digest::try_from(hex::encode(digest.finalize()))
}

fn collect_tree_paths(root: &Path, current: &Path, paths: &mut Vec<PathBuf>) -> Result<()> {
    let mut entries = fs::read_dir(current)?.collect::<std::io::Result<Vec<_>>>()?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        let path = entry.path();
        let relative = path
            .strip_prefix(root)
            .map_err(|error| {
                CliError::new(
                    "LOCAL_COMPONENT_PATH_INVALID",
                    format!("Component path escaped its root: {error}"),
                )
            })?
            .to_path_buf();
        paths.push(relative);
        if entry.file_type()?.is_dir() {
            collect_tree_paths(root, &path, paths)?;
        }
    }
    Ok(())
}

fn write_bytes(path: &Path, contents: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, contents)?;
    Ok(())
}

fn write_json_atomic<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension(format!("json.tmp-{}", std::process::id()));
    let result = (|| -> Result<()> {
        let mut output = fs::File::create(&temporary)?;
        output.write_all(&serde_json::to_vec_pretty(value)?)?;
        output.write_all(b"\n")?;
        output.sync_all()?;
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn replace_plain_file_atomically(path: &Path, contents: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension(format!("tmp-{}", std::process::id()));
    let result = (|| -> Result<()> {
        let mut output = fs::File::create(&temporary)?;
        output.write_all(contents)?;
        output.sync_all()?;
        fs::rename(&temporary, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

#[cfg(unix)]
fn replace_relative_symlink(path: &Path, target: &Path) -> Result<()> {
    use std::os::unix::fs::symlink;

    let parent = path.parent().ok_or_else(|| {
        CliError::new(
            "LOCAL_PREFIX_INVALID",
            format!("Local authority link has no parent: {}", path.display()),
        )
    })?;
    fs::create_dir_all(parent)?;
    let temporary = path.with_extension("next");
    if fs::symlink_metadata(&temporary).is_ok() {
        fs::remove_file(&temporary)?;
    }
    symlink(target, &temporary)?;
    fs::rename(&temporary, path)?;
    Ok(())
}

#[cfg(not(unix))]
fn replace_relative_symlink(_path: &Path, _target: &Path) -> Result<()> {
    Err(CliError::new(
        "LOCAL_DEVELOPMENT_UNSUPPORTED",
        "Atomic local-development generation links are not implemented on this platform.",
    ))
}

fn read_relative_symlink(path: &Path) -> Result<Option<PathBuf>> {
    match fs::read_link(path) {
        Ok(target) => Ok(Some(target)),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(CliError::new(
            "LOCAL_AUTHORITY_LINK_INVALID",
            format!("Could not read local authority link {}: {error}", path.display()),
        )),
    }
}
