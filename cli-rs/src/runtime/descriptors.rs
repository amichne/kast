fn read_descriptors(descriptor_directory: &Path) -> Result<Vec<ServerInstanceDescriptor>> {
    let path = descriptor_directory.join("daemons.json");
    if !path.is_file() {
        return Ok(vec![]);
    }
    Ok(serde_json::from_str(&fs::read_to_string(path)?).unwrap_or_default())
}

fn delete_descriptor(
    descriptor_directory: &Path,
    descriptor: &ServerInstanceDescriptor,
) -> Result<()> {
    let path = descriptor_directory.join("daemons.json");
    let mut descriptors = read_descriptors(descriptor_directory)?;
    let id = descriptor_id(descriptor);
    descriptors.retain(|candidate| descriptor_id(candidate) != id);
    if descriptors.is_empty() {
        if path.exists() {
            fs::remove_file(path)?;
        }
        return Ok(());
    }
    fs::create_dir_all(descriptor_directory)?;
    fs::write(path, serde_json::to_string_pretty(&descriptors)?)?;
    Ok(())
}

fn descriptor_id(descriptor: &ServerInstanceDescriptor) -> String {
    format!(
        "{}:{}:{}",
        descriptor.workspace_root, descriptor.backend_name, descriptor.pid
    )
}

fn is_process_alive(pid: u64) -> bool {
    if pid == 0 || pid > i32::MAX as u64 {
        return false;
    }
    let result = unsafe { libc::kill(pid as libc::pid_t, 0) };
    if result == 0 {
        return true;
    }
    std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

fn terminate_process(pid: u64, force: bool) {
    if pid == 0 || pid > i32::MAX as u64 {
        return;
    }
    let signal = if force { libc::SIGKILL } else { libc::SIGTERM };
    unsafe {
        libc::kill(pid as libc::pid_t, signal);
    }
}

fn workspace_root(value: Option<PathBuf>) -> Result<PathBuf> {
    config::resolve_workspace_root(value)
}

fn shell_single_quoted_path(path: &Path) -> String {
    format!(
        "'{}'",
        path.display().to_string().replace('\'', "'\"'\"'")
    )
}

fn no_backend_error(workspace_root: &Path, backend_name: Option<BackendName>) -> CliError {
    let backend_name = backend_name.unwrap_or(BackendName::Headless);
    let local_entrypoint = (backend_name == BackendName::Headless)
        .then(crate::local_development::active_local_development_receipt)
        .transpose()
        .ok()
        .flatten()
        .flatten()
        .map(|receipt| receipt.entrypoint.effective_target);
    let mut error = match backend_name {
        BackendName::Headless if let Some(entrypoint) = &local_entrypoint => CliError::new(
            "NO_BACKEND_AVAILABLE",
            format!(
                "The local-development headless backend is installed but not running for {}. Start the receipt-owned backend with: {} developer runtime up --workspace-root {} --backend=headless",
                workspace_root.display(),
                shell_single_quoted_path(entrypoint),
                shell_single_quoted_path(workspace_root),
            ),
        ),
        BackendName::Headless => CliError::new(
            "NO_BACKEND_AVAILABLE",
            format!(
                "No headless backend is installed or running for {}. Headless operation is supported through the Linux headless tarball. Install and extract that distribution, then start with: kast developer runtime up --backend=headless",
                workspace_root.display()
            ),
        ),
        BackendName::Idea => CliError::new(
            "NO_BACKEND_AVAILABLE",
            format!(
                "No idea backend is installed or running for {}. Install or update the signed Kast plugin through JetBrains, open the project in IDEA or Android Studio, then start with: kast developer runtime up --backend=idea",
                workspace_root.display()
            ),
        ),
    };
    match backend_name {
        BackendName::Headless => {
            if let Some(entrypoint) = local_entrypoint {
                error.details.insert(
                    "authority".to_string(),
                    "local-development".to_string(),
                );
                error.details.insert(
                    "startCommand".to_string(),
                    format!(
                        "{} developer runtime up --workspace-root {} --backend=headless",
                        shell_single_quoted_path(&entrypoint),
                        shell_single_quoted_path(workspace_root),
                    ),
                );
            } else {
                error.details.insert(
                    "supportedDistribution".to_string(),
                    "linux-headless-tarball".to_string(),
                );
                error.details.insert(
                    "installHint".to_string(),
                    "Install and extract the Linux headless tarball; standalone headless backend installation is not a supported distribution path.".to_string(),
                );
            }
        }
        BackendName::Idea => {
            error.details.insert(
                "installHint".to_string(),
                "Install or update the signed plugin through JetBrains, reopen this exact project, and refresh workspace metadata.".to_string(),
            );
        }
    }
    error
}
