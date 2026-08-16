use super::*;
use super::registration::{
    ServiceLaunchRegistration, ServiceManagerRegistration, ValidatedServiceRegistration,
};
use super::process::ManagedProcessIdentity;
use serde::{Deserialize, Serialize};
use std::process::Stdio;

#[cfg(target_os = "macos")]
#[path = "service_manager/launchd.rs"]
mod launchd;
#[cfg(not(target_os = "macos"))]
#[path = "service_manager/systemd.rs"]
mod systemd;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum ServiceManagerObservation {
    Absent,
    Registered,
    Running(u64),
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TestManagerState {
    pid: u64,
}

pub(super) fn registration_for(
    launch: &ServiceLaunchRegistration,
    directory: &Path,
    _launch_sha256: &str,
) -> Result<ServiceManagerRegistration> {
    if let Some(state_path) = test_manager_state_path() {
        return Ok(ServiceManagerRegistration::Test {
            state_path: state_path.display().to_string(),
            definition_path: directory.join("service.test.json").display().to_string(),
        });
    }
    #[cfg(target_os = "macos")]
    {
        launchd::registration_for(launch, directory)
    }
    #[cfg(not(target_os = "macos"))]
    {
        systemd::registration_for(launch, directory)
    }
}

pub(super) fn render_definition(
    launch: &ServiceLaunchRegistration,
    manager: &ServiceManagerRegistration,
    launch_sha256: &str,
) -> Result<String> {
    match manager {
        ServiceManagerRegistration::Test { .. } => serde_json::to_string_pretty(&serde_json::json!({
            "launcher": launch.launcher_path,
            "registration": manager.definition_path().parent().unwrap_or_else(|| Path::new(".")).join("launch.json"),
            "registrationSha256": launch_sha256,
        }))
        .map_err(Into::into),
        #[cfg(target_os = "macos")]
        ServiceManagerRegistration::Launchd { .. } => {
            launchd::render_definition(launch, manager, launch_sha256)
        }
        #[cfg(not(target_os = "macos"))]
        ServiceManagerRegistration::SystemdUser { .. } => {
            systemd::render_definition(launch, manager, launch_sha256)
        }
        _ => Err(manager_platform_mismatch()),
    }
}

pub(super) fn register(manager: &ServiceManagerRegistration) -> Result<()> {
    match manager {
        ServiceManagerRegistration::Test { .. } => Ok(()),
        #[cfg(target_os = "macos")]
        ServiceManagerRegistration::Launchd { .. } => launchd::register(manager),
        #[cfg(not(target_os = "macos"))]
        ServiceManagerRegistration::SystemdUser { .. } => systemd::register(manager),
        _ => Err(manager_platform_mismatch()),
    }
}

pub(super) fn start(
    registration: &ValidatedServiceRegistration,
) -> Result<ServiceManagerObservation> {
    match &registration.receipt.manager {
        ServiceManagerRegistration::Test { state_path, .. } => {
            start_test_manager(&registration.launch, Path::new(state_path))
        }
        #[cfg(target_os = "macos")]
        ServiceManagerRegistration::Launchd { .. } => {
            launchd::start(&registration.receipt.manager)
        }
        #[cfg(not(target_os = "macos"))]
        ServiceManagerRegistration::SystemdUser { .. } => {
            systemd::start(&registration.receipt.manager)
        }
        _ => Err(manager_platform_mismatch()),
    }
}

pub(super) fn inspect(manager: &ServiceManagerRegistration) -> Result<ServiceManagerObservation> {
    match manager {
        ServiceManagerRegistration::Test { state_path, .. } => inspect_test_manager(Path::new(state_path)),
        #[cfg(target_os = "macos")]
        ServiceManagerRegistration::Launchd { .. } => launchd::inspect(manager),
        #[cfg(not(target_os = "macos"))]
        ServiceManagerRegistration::SystemdUser { .. } => systemd::inspect(manager),
        _ => Err(manager_platform_mismatch()),
    }
}

pub(super) fn stop(
    manager: &ServiceManagerRegistration,
    expected_process: &ManagedProcessIdentity,
) -> Result<()> {
    match manager {
        ServiceManagerRegistration::Test { state_path, .. } => {
            stop_test_manager(Path::new(state_path), expected_process)
        }
        #[cfg(target_os = "macos")]
        ServiceManagerRegistration::Launchd { .. } => launchd::stop(manager),
        #[cfg(not(target_os = "macos"))]
        ServiceManagerRegistration::SystemdUser { .. } => systemd::stop(manager),
        _ => Err(manager_platform_mismatch()),
    }
}

pub(super) fn unregister(manager: &ServiceManagerRegistration) -> Result<()> {
    match manager {
        ServiceManagerRegistration::Test { state_path, .. } => {
            match fs::remove_file(state_path) {
                Ok(()) => Ok(()),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
                Err(error) => Err(error.into()),
            }
        }
        #[cfg(target_os = "macos")]
        ServiceManagerRegistration::Launchd { .. } => launchd::unregister(manager),
        #[cfg(not(target_os = "macos"))]
        ServiceManagerRegistration::SystemdUser { .. } => systemd::unregister(manager),
        _ => Err(manager_platform_mismatch()),
    }
}

fn start_test_manager(
    launch: &ServiceLaunchRegistration,
    state_path: &Path,
) -> Result<ServiceManagerObservation> {
    if let ServiceManagerObservation::Running(pid) = inspect_test_manager(state_path)? {
        return Ok(ServiceManagerObservation::Running(pid));
    }
    let log_path = Path::new(&launch.log_file);
    if let Some(parent) = log_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let log = fs::File::create(log_path)?;
    let stderr = log.try_clone()?;
    let mut command = Command::new(
        launch
            .command
            .first()
            .ok_or_else(|| manager_error("Registered indexer command is empty."))?,
    );
    command
        .args(&launch.command[1..])
        .current_dir(&launch.working_directory)
        .envs(&launch.environment)
        .stdin(Stdio::null())
        .stdout(Stdio::from(log))
        .stderr(Stdio::from(stderr));
    let child = command.spawn().map_err(|error| {
        manager_error(&format!("Test service manager could not start the indexer: {error}"))
    })?;
    let pid = u64::from(child.id());
    std::mem::forget(child);
    if let Some(parent) = state_path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(state_path, serde_json::to_vec(&TestManagerState { pid })?)?;
    Ok(ServiceManagerObservation::Running(pid))
}

fn inspect_test_manager(path: &Path) -> Result<ServiceManagerObservation> {
    let state: TestManagerState = match fs::read(path) {
        Ok(bytes) => serde_json::from_slice(&bytes)?,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(ServiceManagerObservation::Absent);
        }
        Err(error) => return Err(error.into()),
    };
    if super::process::process_is_alive(state.pid)? {
        Ok(ServiceManagerObservation::Running(state.pid))
    } else {
        Ok(ServiceManagerObservation::Registered)
    }
}

fn stop_test_manager(path: &Path, expected: &ManagedProcessIdentity) -> Result<()> {
    match inspect_test_manager(path)? {
        ServiceManagerObservation::Running(pid) if pid == expected.pid => {
            super::process::signal_process(expected, false)?;
            Ok(())
        }
        ServiceManagerObservation::Registered | ServiceManagerObservation::Absent => Ok(()),
        ServiceManagerObservation::Running(_) => Err(manager_error(
            "Test service manager process changed before stop.",
        )),
    }
}

fn test_manager_state_path() -> Option<PathBuf> {
    (std::env::var("KAST_TEST_ALLOW_RUNTIME_SERVICE_MANAGER").as_deref() == Ok("1"))
        .then(|| std::env::var_os("KAST_TEST_RUNTIME_SERVICE_MANAGER_STATE"))
        .flatten()
        .map(PathBuf::from)
}

fn manager_platform_mismatch() -> CliError {
    manager_error("Runtime service registration belongs to a different platform manager.")
}

pub(super) fn manager_error(message: &str) -> CliError {
    CliError::new("RUNTIME_SERVICE_MANAGER_UNAVAILABLE", message)
}

pub(super) fn command_output(command: &mut Command, action: &str) -> Result<std::process::Output> {
    let output = command.output().map_err(|error| {
        manager_error(&format!("Runtime service manager cannot {action}: {error}"))
    })?;
    if output.status.success() {
        Ok(output)
    } else {
        Err(manager_error(&format!(
            "Runtime service manager cannot {action}: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        )))
    }
}
