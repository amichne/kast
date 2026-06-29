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

fn link_active_headless_backend(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<()> {
    let stable_backend_dir = targets.version_dir.join("lib/backends/headless");
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
        let backend_dir = targets.version_dir.join(&bundle.backend_install_relative);
        copy_bundle_tree(&backend_dir, &current)?;
    }
    Ok(())
}

fn ensure_active_cli_path(
    bundle: &ValidatedBundle,
    targets: &ActivationTargetPaths,
) -> Result<PathBuf> {
    let active_binary = targets.version_dir.join(&bundle.cli_relative);
    if targets.resolved.shim_path == active_binary {
        let renamed = active_binary.with_file_name("kast-cli");
        manifest::remove_path(&renamed)?;
        fs::rename(&active_binary, &renamed)?;
        manifest::make_executable(&renamed)?;
        return Ok(renamed);
    }
    manifest::make_executable(&active_binary)?;
    Ok(active_binary)
}

fn write_headless_kast_shim(
    shim_path: &Path,
    active_binary: &Path,
    install_root: &Path,
    config_home: &Path,
    java_opts: &[String],
) -> Result<()> {
    if let Some(parent) = shim_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut content = String::new();
    content.push_str("#!/usr/bin/env bash\nset -euo pipefail\n\n");
    content.push_str(&format!(
        "export KAST_INSTALL_ROOT={}\n",
        shell_quote(&install_root.display().to_string())
    ));
    content.push_str(&format!(
        "export KAST_CONFIG_HOME={}\n\n",
        shell_quote(&config_home.display().to_string())
    ));
    for java_opt in java_opts {
        content.push_str(&format!(
            "case \" ${{JAVA_OPTS:-}} \" in\n  *\" {} \"*) ;;\n  *) export JAVA_OPTS=\"${{JAVA_OPTS:+${{JAVA_OPTS}} }}{}\" ;;\nesac\n",
            java_opt, java_opt
        ));
    }
    content.push('\n');
    content.push_str(&format!(
        "exec {} \"$@\"\n",
        shell_quote(&active_binary.display().to_string())
    ));
    fs::write(shim_path, content)?;
    manifest::make_executable(shim_path)
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
