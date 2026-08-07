fn read_descriptors(descriptor_directory: &Path) -> Result<Vec<ServerInstanceDescriptor>> {
    let path = descriptor_directory.join("daemons.json");
    Ok(read_descriptor_elements(&path)?
        .into_iter()
        .filter_map(|element| serde_json::from_value(element).ok())
        .collect())
}

const RETIRED_HEADLESS_BACKEND_NAME: &str = "headless";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RetiredDaemonState {
    Stale,
    OwnedLive,
}

pub(crate) fn retire_registered_legacy_headless_daemons(
    descriptor_directory: &Path,
) -> Result<()> {
    let path = descriptor_directory.join("daemons.json");
    let retired = read_retired_descriptors(&path)?
        .into_iter()
        .map(|descriptor| {
            let state = validate_retired_daemon(&descriptor)?;
            Ok((descriptor, state))
        })
        .collect::<Result<Vec<_>>>()?;

    for (descriptor, state) in &retired {
        if *state == RetiredDaemonState::OwnedLive {
            require_registered_descriptor(&path, descriptor)?;
            match validate_retired_daemon(descriptor)? {
                RetiredDaemonState::Stale => {}
                RetiredDaemonState::OwnedLive => terminate_owned_retired_daemon(descriptor)?,
            }
        }
    }
    let descriptors = retired
        .into_iter()
        .map(|(descriptor, _)| descriptor)
        .collect::<Vec<_>>();
    delete_descriptors(descriptor_directory, &descriptors)
}

fn read_retired_descriptors(path: &Path) -> Result<Vec<ServerInstanceDescriptor>> {
    with_descriptor_registry_lock(path, || {
        descriptor_entries(read_descriptor_elements(path)?)
    })
}

fn descriptor_entries(elements: Vec<Value>) -> Result<Vec<ServerInstanceDescriptor>> {
    elements
        .into_iter()
        .filter(|element| {
            element.get("backendName").and_then(Value::as_str)
                == Some(RETIRED_HEADLESS_BACKEND_NAME)
        })
        .map(|element| {
            serde_json::from_value(element).map_err(|error| {
                CliError::new(
                    "RUNTIME_DESCRIPTOR_REGISTRY_INVALID",
                    format!("A legacy Kast runtime descriptor is invalid: {error}"),
                )
            })
        })
        .collect()
}

fn validate_retired_daemon(descriptor: &ServerInstanceDescriptor) -> Result<RetiredDaemonState> {
    if !is_process_alive(descriptor.pid) {
        return Ok(RetiredDaemonState::Stale);
    }
    if descriptor.transport != "uds" {
        return Err(indexer_authority::runtime_identity_mismatch());
    }
    indexer_authority::validate_descriptor_owner(descriptor)?;
    let status = rpc::request_wait_for_close::<RuntimeStatusWireResponse>(
        Path::new(&descriptor.socket_path),
        "runtime/status",
        Value::Object(Default::default()),
        Duration::from_secs(2),
    )?
    .into_status()?;
    validate_runtime_status_identity(descriptor, &status)?;
    if descriptor.backend_version != status.backend_version
        || descriptor.schema_version != status.schema_version
    {
        return Err(indexer_authority::runtime_identity_mismatch());
    }
    Ok(RetiredDaemonState::OwnedLive)
}

fn require_registered_descriptor(
    path: &Path,
    expected: &ServerInstanceDescriptor,
) -> Result<()> {
    let registered = with_descriptor_registry_lock(path, || {
        descriptor_entries(read_descriptor_elements(path)?)
    })?;
    if registered.iter().any(|descriptor| descriptor == expected) {
        Ok(())
    } else {
        Err(CliError::new(
            "RUNTIME_IDENTITY_REPLACED",
            "The registered legacy Kast runtime changed during setup migration.",
        ))
    }
}

fn terminate_owned_retired_daemon(descriptor: &ServerInstanceDescriptor) -> Result<()> {
    indexer_authority::validate_descriptor_owner(descriptor)?;
    if !indexer_authority::descriptor_process_identity_is_live(descriptor) {
        return Ok(());
    }
    terminate_process(descriptor.pid, false);
    for _ in 0..20 {
        if !indexer_authority::descriptor_process_identity_is_live(descriptor) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(250));
    }
    indexer_authority::validate_descriptor_owner(descriptor)?;
    if !indexer_authority::descriptor_process_identity_is_live(descriptor) {
        return Ok(());
    }
    terminate_process(descriptor.pid, true);
    for _ in 0..20 {
        if !indexer_authority::descriptor_process_identity_is_live(descriptor) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(50));
    }
    Err(CliError::new(
        "SETUP_LEGACY_RUNTIME_STOP_FAILED",
        format!(
            "The owned legacy Kast runtime process {} did not stop.",
            descriptor.pid
        ),
    ))
}

fn delete_descriptor(
    descriptor_directory: &Path,
    descriptor: &ServerInstanceDescriptor,
) -> Result<()> {
    delete_descriptors(descriptor_directory, std::slice::from_ref(descriptor))
}

fn delete_descriptors(
    descriptor_directory: &Path,
    descriptors: &[ServerInstanceDescriptor],
) -> Result<()> {
    let path = descriptor_directory.join("daemons.json");
    with_descriptor_registry_lock(&path, || {
        let mut elements = read_descriptor_elements(&path)?;
        elements.retain(|element| {
            serde_json::from_value::<ServerInstanceDescriptor>(element.clone())
                .map_or(true, |candidate| !descriptors.contains(&candidate))
        });
        if elements.is_empty() {
            match fs::remove_file(&path) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => return Err(error.into()),
            }
            return Ok(());
        }
        write_descriptor_elements_atomic(&path, &elements)
    })
}

fn read_descriptor_elements(path: &Path) -> Result<Vec<Value>> {
    let contents = match fs::read_to_string(path) {
        Ok(contents) => contents,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
        Err(error) => return Err(error.into()),
    };
    let registry = serde_json::from_str(&contents).map_err(|error| {
        CliError::new(
            "RUNTIME_DESCRIPTOR_REGISTRY_INVALID",
            format!("Runtime descriptor registry is not valid JSON: {error}"),
        )
    })?;
    match registry {
        Value::Array(elements) => Ok(elements),
        _ => Err(CliError::new(
            "RUNTIME_DESCRIPTOR_REGISTRY_INVALID",
            format!("Runtime descriptor registry must be a JSON array: {}", path.display()),
        )),
    }
}

fn write_descriptor_elements_atomic(path: &Path, elements: &[Value]) -> Result<()> {
    let parent = path.parent().ok_or_else(|| {
        CliError::new(
            "RUNTIME_DESCRIPTOR_REGISTRY_INVALID",
            format!("Runtime descriptor registry has no parent: {}", path.display()),
        )
    })?;
    fs::create_dir_all(parent)?;
    let suffix = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let temporary = parent.join(format!(".daemons.json.{}-{suffix}.tmp", std::process::id()));
    fs::write(&temporary, serde_json::to_vec_pretty(elements)?)?;
    if let Err(error) = fs::rename(&temporary, path) {
        let _ = fs::remove_file(&temporary);
        return Err(error.into());
    }
    Ok(())
}

fn with_descriptor_registry_lock<T>(path: &Path, operation: impl FnOnce() -> Result<T>) -> Result<T> {
    use std::os::fd::AsRawFd;

    let parent = path.parent().ok_or_else(|| {
        CliError::new(
            "RUNTIME_DESCRIPTOR_REGISTRY_INVALID",
            format!("Runtime descriptor registry has no parent: {}", path.display()),
        )
    })?;
    fs::create_dir_all(parent)?;
    let mut lock_name = path.as_os_str().to_os_string();
    lock_name.push(".lock");
    let lock = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .write(true)
        .open(PathBuf::from(lock_name))?;
    set_descriptor_registry_lock(
        lock.as_raw_fd(),
        libc::c_int::from(libc::F_WRLCK),
        libc::F_SETLKW,
    )?;
    let result = operation();
    let unlock = set_descriptor_registry_lock(
        lock.as_raw_fd(),
        libc::c_int::from(libc::F_UNLCK),
        libc::F_SETLK,
    );
    match (result, unlock) {
        (Err(error), _) => Err(error),
        (Ok(_), Err(error)) => Err(error),
        (Ok(value), Ok(())) => Ok(value),
    }
}

fn set_descriptor_registry_lock(
    fd: std::os::fd::RawFd,
    lock_type: libc::c_int,
    command: libc::c_int,
) -> Result<()> {
    let mut lock = unsafe { std::mem::zeroed::<libc::flock>() };
    lock.l_type = libc::c_short::try_from(lock_type).map_err(|_| {
        CliError::new(
            "RUNTIME_DESCRIPTOR_LOCK_INVALID",
            format!("POSIX lock type {lock_type} does not fit in c_short"),
        )
    })?;
    lock.l_whence = libc::SEEK_SET as _;
    lock.l_start = 0;
    lock.l_len = 0;
    if unsafe { libc::fcntl(fd, command, &lock) } == -1 {
        Err(std::io::Error::last_os_error().into())
    } else {
        Ok(())
    }
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
