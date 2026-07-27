struct ScratchDir {
    path: PathBuf,
}

impl ScratchDir {
    fn new(label: &str) -> Result<Self> {
        let suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_nanos())
            .unwrap_or_default();
        let path = env::temp_dir().join(format!("{label}-{}-{suffix}", std::process::id()));
        manifest::remove_path(&path)?;
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

fn safe_relative_path(path: &Path, field: &str) -> Result<PathBuf> {
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::Normal(value) => normalized.push(value),
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(CliError::new(
                    "BUNDLE_PATH_UNSAFE",
                    format!(
                        "Bundle {field} path `{}` must be relative and must not contain `..`.",
                        path.display()
                    ),
                ));
            }
        }
    }
    if normalized.as_os_str().is_empty() {
        return Err(CliError::new(
            "BUNDLE_PATH_UNSAFE",
            format!("Bundle {field} path must not be empty."),
        ));
    }
    Ok(normalized)
}

fn bundle_manifest_path(value: &str, field: &str) -> Result<PathBuf> {
    safe_relative_path(Path::new(value.trim()), field)
}

fn require_file(path: &Path, label: &str) -> Result<()> {
    if path.is_file() {
        Ok(())
    } else {
        Err(CliError::new(
            "BUNDLE_SHAPE_INVALID",
            format!("Missing {label}: {}", path.display()),
        ))
    }
}

fn require_directory(path: &Path, label: &str) -> Result<()> {
    if path.is_dir() {
        Ok(())
    } else {
        Err(CliError::new(
            "BUNDLE_SHAPE_INVALID",
            format!("Missing {label}: {}", path.display()),
        ))
    }
}

fn require_executable(path: &Path, label: &str) -> Result<()> {
    require_file(path, label)?;
    if is_executable(path)? {
        Ok(())
    } else {
        Err(CliError::new(
            "BUNDLE_SHAPE_INVALID",
            format!("{label} is not executable: {}", path.display()),
        ))
    }
}

#[cfg(unix)]
fn is_executable(path: &Path) -> Result<bool> {
    use std::os::unix::fs::PermissionsExt;
    Ok(fs::metadata(path)?.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn is_executable(path: &Path) -> Result<bool> {
    Ok(path.is_file())
}

fn copy_bundle_tree(source: &Path, target: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    if metadata.file_type().is_symlink() {
        return Err(CliError::new(
            "BUNDLE_SOURCE_UNSAFE",
            format!(
                "Bundle source must not contain symlinks: {}",
                source.display()
            ),
        ));
    }
    if metadata.is_dir() {
        fs::create_dir_all(target)?;
        let mut entries = fs::read_dir(source)?.collect::<std::result::Result<Vec<_>, _>>()?;
        entries.sort_by_key(|entry| entry.path());
        for entry in entries {
            copy_bundle_tree(&entry.path(), &target.join(entry.file_name()))?;
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

fn directory_sha256(root: &Path) -> Result<String> {
    fn collect(root: &Path, directory: &Path, files: &mut Vec<PathBuf>) -> Result<()> {
        for entry in fs::read_dir(directory)? {
            let entry = entry?;
            let metadata = fs::symlink_metadata(entry.path())?;
            if metadata.file_type().is_symlink() {
                return Err(CliError::new(
                    "BUNDLE_SOURCE_UNSAFE",
                    format!("Bundle source contains a symlink: {}", entry.path().display()),
                ));
            }
            if metadata.is_dir() {
                collect(root, &entry.path(), files)?;
            } else if metadata.is_file() {
                files.push(
                    entry
                        .path()
                        .strip_prefix(root)
                        .expect("bundle child")
                        .to_path_buf(),
                );
            }
        }
        Ok(())
    }

    let mut files = Vec::new();
    collect(root, root, &mut files)?;
    files.sort();
    let mut digest = Sha256::new();
    for relative in files {
        digest.update(relative.to_string_lossy().as_bytes());
        digest.update(b"\n");
        digest.update(manifest::sha256_file(&root.join(&relative))?.as_bytes());
        digest.update(b"\n");
    }
    Ok(hex::encode(digest.finalize()))
}

fn link_active_headless_backend(
    bundle: &ValidatedBundle,
    release_root: &Path,
) -> Result<()> {
    let stable_backend_dir = release_root.join("lib/backends/headless");
    fs::create_dir_all(&stable_backend_dir)?;
    let current = stable_backend_dir.join("current");
    manifest::remove_path(&current)?;
    let backend_name = bundle.backend_install_relative.file_name().ok_or_else(|| {
        CliError::new(
            "BUNDLE_MANIFEST_INVALID",
            "Bundle backend installDir must include a final path component.",
        )
    })?;
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(Path::new("..").join(backend_name), &current)?;
    }
    #[cfg(not(unix))]
    {
        let backend_dir = release_root.join(&bundle.backend_install_relative);
        copy_bundle_tree(&backend_dir, &current)?;
    }
    Ok(())
}

fn write_headless_config(config_file: &Path) -> Result<()> {
    if let Some(parent) = config_file.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(
        config_file,
        r#"[server]
maxResults = 500
requestTimeoutMillis = 30000
maxConcurrentRequests = 4

[runtime]
defaultBackend = "headless"

[backends.headless]
enabled = true
"#,
    )?;
    Ok(())
}

fn env_path(name: &str) -> Option<PathBuf> {
    env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(config::normalize)
}
