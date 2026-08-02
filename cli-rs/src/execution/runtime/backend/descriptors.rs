fn read_descriptors(descriptor_directory: &Path) -> Result<Vec<ServerInstanceDescriptor>> {
    let path = descriptor_directory.join("daemons.json");
    parse_descriptor_registry_read(fs::read_to_string(path))
}

fn parse_descriptor_registry_read(
    read: std::io::Result<String>,
) -> Result<Vec<ServerInstanceDescriptor>> {
    match read {
        Ok(contents) => Ok(serde_json::from_str(&contents).unwrap_or_default()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(vec![]),
        Err(error) => Err(error.into()),
    }
}

fn delete_descriptor(
    descriptor_directory: &Path,
    descriptor: &ServerInstanceDescriptor,
) -> Result<()> {
    let path = descriptor_directory.join("daemons.json");
    let mut descriptors = read_descriptors(descriptor_directory)?;
    descriptors.retain(|candidate| candidate != descriptor);
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
        "{}:{}:{}:{}:{}",
        descriptor.workspace_root,
        descriptor.backend_name,
        descriptor.pid,
        descriptor.runtime_instance_id.as_deref().unwrap_or("legacy"),
        descriptor.process_start_epoch_millis.unwrap_or_default(),
    )
}

pub(crate) fn is_process_alive(pid: u64) -> bool {
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
