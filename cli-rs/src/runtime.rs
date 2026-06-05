use crate::SCHEMA_VERSION;
use crate::cli::{BackendName, DaemonStartArgs, RpcArgs, RuntimeArgs};
use crate::config::{self, KastConfig};
use crate::daemon;
use crate::error::{CliError, Result};
use crate::rpc;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ServerInstanceDescriptor {
    pub workspace_root: String,
    pub backend_name: String,
    pub backend_version: String,
    #[serde(default = "default_transport")]
    pub transport: String,
    pub socket_path: String,
    #[serde(default)]
    pub pid: u64,
    #[serde(default = "schema_version")]
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeStatusResponse {
    pub state: RuntimeState,
    pub healthy: bool,
    pub active: bool,
    pub indexing: bool,
    pub backend_name: String,
    pub backend_version: String,
    pub workspace_root: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub warnings: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub source_module_names: Vec<String>,
    #[serde(default, skip_serializing_if = "serde_json::Map::is_empty")]
    pub dependent_module_names_by_source_module_name: serde_json::Map<String, Value>,
    #[serde(default)]
    pub reference_index_ready: bool,
    #[serde(default = "schema_version")]
    pub schema_version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RuntimeState {
    Starting,
    Indexing,
    Ready,
    Degraded,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeCandidateStatus {
    pub descriptor_path: String,
    pub descriptor: ServerInstanceDescriptor,
    pub pid_alive: bool,
    pub reachable: bool,
    pub ready: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub runtime_status: Option<RuntimeStatusResponse>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_message: Option<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceStatusResult {
    pub workspace_root: String,
    pub descriptor_directory: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub selected: Option<RuntimeCandidateStatus>,
    pub candidates: Vec<RuntimeCandidateStatus>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceEnsureResult {
    pub workspace_root: String,
    pub descriptor_directory: String,
    pub started: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub log_file: Option<String>,
    pub selected: RuntimeCandidateStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    pub schema_version: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DaemonStopResult {
    pub workspace_root: String,
    pub stopped: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub descriptor_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pid: Option<u64>,
    pub forced: bool,
    pub schema_version: u32,
}

#[derive(Debug, Clone)]
struct RegisteredDescriptor {
    id: String,
    descriptor: ServerInstanceDescriptor,
}

struct WorkspaceInspection {
    descriptor_directory: PathBuf,
    candidates: Vec<RuntimeCandidateStatus>,
    selected: Option<RuntimeCandidateStatus>,
}

pub fn workspace_status(args: RuntimeArgs) -> Result<WorkspaceStatusResult> {
    let workspace_root = workspace_root(args.workspace_root.clone())?;
    let backend_name = resolve_runtime_backend(&workspace_root, args.backend_name)?;
    let inspection = inspect_workspace(&workspace_root, Some(backend_name), false)?;
    Ok(WorkspaceStatusResult {
        workspace_root: workspace_root.display().to_string(),
        descriptor_directory: inspection.descriptor_directory.display().to_string(),
        selected: inspection.selected,
        candidates: inspection.candidates,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn workspace_ensure(args: RuntimeArgs) -> Result<WorkspaceEnsureResult> {
    let workspace_root = workspace_root(args.workspace_root.clone())?;
    let config = KastConfig::load(&workspace_root)?;
    let backend_name = config::resolve_runtime_backend(&config, args.backend_name);
    let inspection =
        inspect_workspace_with_config(&workspace_root, &config, Some(backend_name), true)?;
    if let Some(selected) = select_servable(
        &inspection.candidates,
        Some(backend_name),
        args.accept_indexing.unwrap_or(false),
    ) {
        return Ok(WorkspaceEnsureResult {
            workspace_root: workspace_root.display().to_string(),
            descriptor_directory: inspection.descriptor_directory.display().to_string(),
            started: false,
            log_file: None,
            selected,
            note: None,
            schema_version: SCHEMA_VERSION,
        });
    }

    if backend_name == BackendName::Idea {
        return Err(CliError::new(
            "IDEA_NOT_RUNNING",
            format!(
                "No IDEA backend is available for {}. Open the project in IDEA with the Kast plugin installed.",
                workspace_root.display()
            ),
        ));
    }

    if args.no_auto_start.unwrap_or(false) {
        return Err(no_backend_error(&workspace_root, Some(backend_name)));
    }

    let launch_backend = backend_name;
    let runtime_libs_dir = config
        .backends
        .headless
        .runtime_libs_dir
        .clone()
        .filter(|path| path.is_dir())
        .ok_or_else(|| no_backend_error(&workspace_root, Some(launch_backend)))?;
    let log_file = daemon_log_file(&config, &workspace_root, launch_backend);
    let daemon_args = DaemonStartArgs {
        workspace_root: Some(workspace_root.clone()),
        backend_name: Some(launch_backend),
        runtime_libs_dir: Some(runtime_libs_dir),
        ..DaemonStartArgs::from(args.clone())
    };
    daemon::spawn_background(daemon_args, &log_file)?;
    let selected = wait_for_servable(
        &workspace_root,
        Some(launch_backend),
        args.accept_indexing.unwrap_or(false),
        args.wait_timeout_ms,
    )?;
    Ok(WorkspaceEnsureResult {
        workspace_root: workspace_root.display().to_string(),
        descriptor_directory: inspection.descriptor_directory.display().to_string(),
        started: true,
        log_file: Some(log_file.display().to_string()),
        selected,
        note: None,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn workspace_stop(args: RuntimeArgs) -> Result<DaemonStopResult> {
    let workspace_root = workspace_root(args.workspace_root.clone())?;
    let backend_name = resolve_runtime_backend(&workspace_root, args.backend_name)?;
    let inspection = inspect_workspace(&workspace_root, Some(backend_name), true)?;
    let Some(candidate) = inspection
        .candidates
        .into_iter()
        .find(|candidate| candidate.descriptor.backend_name == backend_name.canonical())
    else {
        return Ok(DaemonStopResult {
            workspace_root: workspace_root.display().to_string(),
            stopped: false,
            descriptor_path: None,
            pid: None,
            forced: false,
            schema_version: SCHEMA_VERSION,
        });
    };

    let mut forced = false;
    if candidate.descriptor.backend_name != "idea" && candidate.pid_alive {
        terminate_process(candidate.descriptor.pid, false);
        for _ in 0..20 {
            if !is_process_alive(candidate.descriptor.pid) {
                break;
            }
            thread::sleep(Duration::from_millis(250));
        }
        if is_process_alive(candidate.descriptor.pid) {
            terminate_process(candidate.descriptor.pid, true);
            forced = true;
        }
    }
    delete_descriptor(&inspection.descriptor_directory, &candidate.descriptor)?;
    Ok(DaemonStopResult {
        workspace_root: candidate.descriptor.workspace_root,
        stopped: true,
        descriptor_path: Some(candidate.descriptor_path),
        pid: Some(candidate.descriptor.pid),
        forced,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn rpc_passthrough(args: RpcArgs) -> Result<String> {
    let raw_request = match (args.request, args.request_file) {
        (Some(request), None) => request,
        (None, Some(path)) => fs::read_to_string(path)?.trim().to_string(),
        _ => {
            return Err(CliError::new(
                "CLI_USAGE",
                "rpc requires a JSON-RPC string argument or --request-file",
            ));
        }
    };
    if let Some(response) =
        crate::metrics::try_handle_raw_rpc(&raw_request, args.workspace_root.clone())?
    {
        return Ok(response);
    }
    if let Some(response) =
        crate::symbol_query::try_handle_raw_rpc(&raw_request, args.workspace_root.clone())?
    {
        return Ok(response);
    }
    let workspace_root = workspace_root(args.workspace_root)?;
    let ensure = workspace_ensure(RuntimeArgs {
        workspace_root: Some(workspace_root),
        backend_name: args.backend_name,
        idea_home: None,
        wait_timeout_ms: 60_000,
        accept_indexing: Some(true),
        no_auto_start: None,
        socket_path: None,
        module_name: None,
        source_roots: None,
        classpath: None,
        request_timeout_ms: None,
        max_results: None,
        max_concurrent_requests: None,
        profile: false,
        profile_modes: None,
        profile_duration: None,
        profile_otlp_endpoint: None,
    })?;
    rpc::raw(
        Path::new(&ensure.selected.descriptor.socket_path),
        &raw_request,
    )
}

pub fn capabilities(args: RuntimeArgs) -> Result<Value> {
    let ensure = workspace_ensure(args)?;
    ensure.selected.capabilities.ok_or_else(|| {
        CliError::new(
            "CAPABILITIES_UNAVAILABLE",
            "Runtime capabilities are unavailable",
        )
    })
}

fn resolve_runtime_backend(
    workspace_root: &Path,
    backend_name: Option<BackendName>,
) -> Result<BackendName> {
    let config = KastConfig::load(workspace_root)?;
    Ok(config::resolve_runtime_backend(&config, backend_name))
}

fn inspect_workspace(
    workspace_root: &Path,
    backend_name: Option<BackendName>,
    prune_stale_descriptors: bool,
) -> Result<WorkspaceInspection> {
    let config = KastConfig::load(workspace_root)?;
    inspect_workspace_with_config(
        workspace_root,
        &config,
        backend_name,
        prune_stale_descriptors,
    )
}

fn inspect_workspace_with_config(
    workspace_root: &Path,
    config: &KastConfig,
    backend_name: Option<BackendName>,
    prune_stale_descriptors: bool,
) -> Result<WorkspaceInspection> {
    let descriptor_directory = config.paths.descriptor_dir.clone();
    let registered = find_by_workspace_root(&descriptor_directory, workspace_root)?;
    let mut candidates = Vec::with_capacity(registered.len());
    for descriptor in registered {
        candidates.push(inspect_descriptor(
            &descriptor_directory,
            descriptor,
            prune_stale_descriptors,
        )?);
    }
    candidates.sort_by(|a, b| {
        b.ready
            .cmp(&a.ready)
            .then_with(|| a.descriptor_path.cmp(&b.descriptor_path))
    });
    let selected = select_status_candidate(&candidates, backend_name);
    Ok(WorkspaceInspection {
        descriptor_directory,
        candidates,
        selected,
    })
}

fn inspect_descriptor(
    descriptor_directory: &Path,
    registered: RegisteredDescriptor,
    prune_stale_descriptors: bool,
) -> Result<RuntimeCandidateStatus> {
    let pid_alive = is_process_alive(registered.descriptor.pid);
    if !pid_alive {
        if prune_stale_descriptors {
            delete_descriptor(descriptor_directory, &registered.descriptor)?;
        }
        return Ok(RuntimeCandidateStatus {
            descriptor_path: registered.id,
            descriptor: registered.descriptor.clone(),
            pid_alive: false,
            reachable: false,
            ready: false,
            runtime_status: None,
            capabilities: None,
            error_message: Some(format!(
                "Process {} is not alive",
                registered.descriptor.pid
            )),
            schema_version: SCHEMA_VERSION,
        });
    }

    let socket_path = Path::new(&registered.descriptor.socket_path);
    let status_result = rpc::request::<RuntimeStatusResponse>(
        socket_path,
        "runtime/status",
        Value::Object(Default::default()),
    );
    let (runtime_status, error_message) = match status_result {
        Ok(status) => (Some(status), None),
        Err(error) => (None, Some(error.message)),
    };
    let capabilities = if runtime_status.is_some() {
        rpc::request::<Value>(
            socket_path,
            "capabilities",
            Value::Object(Default::default()),
        )
        .ok()
    } else {
        None
    };
    let ready = runtime_status.as_ref().is_some_and(is_ready);
    Ok(RuntimeCandidateStatus {
        descriptor_path: registered.id,
        descriptor: registered.descriptor,
        pid_alive: true,
        reachable: runtime_status.is_some(),
        ready,
        runtime_status,
        capabilities,
        error_message,
        schema_version: SCHEMA_VERSION,
    })
}

fn wait_for_servable(
    workspace_root: &Path,
    backend_name: Option<BackendName>,
    accept_indexing: bool,
    wait_timeout_ms: u64,
) -> Result<RuntimeCandidateStatus> {
    let deadline = Instant::now() + Duration::from_millis(wait_timeout_ms);
    while Instant::now() < deadline {
        let inspection = inspect_workspace(workspace_root, backend_name, true)?;
        if let Some(selected) =
            select_servable(&inspection.candidates, backend_name, accept_indexing)
        {
            return Ok(selected);
        }
        thread::sleep(Duration::from_millis(250));
    }
    Err(CliError::new(
        "RUNTIME_TIMEOUT",
        format!(
            "Timed out waiting for {} runtime to become {} for {}",
            backend_name.map(BackendName::canonical).unwrap_or("any"),
            if accept_indexing { "servable" } else { "ready" },
            workspace_root.display()
        ),
    ))
}

fn select_servable(
    candidates: &[RuntimeCandidateStatus],
    backend_name: Option<BackendName>,
    accept_indexing: bool,
) -> Option<RuntimeCandidateStatus> {
    let mut matches: Vec<_> = candidates
        .iter()
        .filter(|candidate| {
            backend_name
                .is_none_or(|backend| candidate.descriptor.backend_name == backend.canonical())
        })
        .filter(|candidate| {
            if accept_indexing {
                candidate.runtime_status.as_ref().is_some_and(is_servable)
            } else {
                candidate.ready
            }
        })
        .cloned()
        .collect();
    matches.sort_by(|a, b| {
        (b.descriptor.backend_name == "idea")
            .cmp(&(a.descriptor.backend_name == "idea"))
            .then_with(|| {
                (b.descriptor.backend_name == "headless")
                    .cmp(&(a.descriptor.backend_name == "headless"))
            })
            .then_with(|| a.descriptor_path.cmp(&b.descriptor_path))
    });
    matches.into_iter().next()
}

fn select_status_candidate(
    candidates: &[RuntimeCandidateStatus],
    backend_name: Option<BackendName>,
) -> Option<RuntimeCandidateStatus> {
    let mut matches: Vec<_> = candidates
        .iter()
        .filter(|candidate| {
            backend_name
                .is_none_or(|backend| candidate.descriptor.backend_name == backend.canonical())
        })
        .cloned()
        .collect();
    matches.sort_by(|a, b| {
        b.ready
            .cmp(&a.ready)
            .then_with(|| {
                (b.descriptor.backend_name == "idea").cmp(&(a.descriptor.backend_name == "idea"))
            })
            .then_with(|| {
                (b.descriptor.backend_name == "headless")
                    .cmp(&(a.descriptor.backend_name == "headless"))
            })
            .then_with(|| a.descriptor_path.cmp(&b.descriptor_path))
    });
    matches.into_iter().next()
}

fn is_servable(status: &RuntimeStatusResponse) -> bool {
    matches!(status.state, RuntimeState::Ready | RuntimeState::Indexing)
        && status.healthy
        && status.active
}

fn is_ready(status: &RuntimeStatusResponse) -> bool {
    matches!(status.state, RuntimeState::Ready)
        && status.healthy
        && status.active
        && !status.indexing
}

fn find_by_workspace_root(
    descriptor_directory: &Path,
    workspace_root: &Path,
) -> Result<Vec<RegisteredDescriptor>> {
    let descriptors = read_descriptors(descriptor_directory)?;
    let normalized = config::normalize(workspace_root.to_path_buf());
    Ok(descriptors
        .into_iter()
        .filter(|descriptor| {
            config::normalize(PathBuf::from(&descriptor.workspace_root)) == normalized
        })
        .map(|descriptor| RegisteredDescriptor {
            id: descriptor_id(&descriptor),
            descriptor,
        })
        .collect())
}

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
    Ok(config::normalize(value.unwrap_or(env::current_dir()?)))
}

fn no_backend_error(workspace_root: &Path, backend_name: Option<BackendName>) -> CliError {
    let backend_name = backend_name.unwrap_or(BackendName::Headless);
    let install_command = format!("kast install {}", backend_name.canonical());
    let mut error = CliError::new(
        "NO_BACKEND_AVAILABLE",
        format!(
            "No {} backend is installed or running for {}. Install it with: {}. Then start with: kast up --backend={} --workspace-root={}",
            backend_name.canonical(),
            workspace_root.display(),
            install_command,
            backend_name.canonical(),
            workspace_root.display()
        ),
    );
    error
        .details
        .insert("installCommand".to_string(), install_command);
    error
}

fn daemon_log_file(
    config: &KastConfig,
    workspace_root: &Path,
    backend_name: BackendName,
) -> PathBuf {
    let workspace_name = workspace_root
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("workspace");
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    config.paths.logs_dir.join(format!(
        "{workspace_name}-{seconds}-{}-daemon.log",
        backend_name.canonical()
    ))
}

fn default_transport() -> String {
    "uds".to_string()
}

fn schema_version() -> u32 {
    SCHEMA_VERSION
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(name: &str, state: RuntimeState, indexing: bool) -> RuntimeCandidateStatus {
        RuntimeCandidateStatus {
            descriptor_path: format!("{name}:1"),
            descriptor: ServerInstanceDescriptor {
                workspace_root: "/tmp/ws".to_string(),
                backend_name: name.to_string(),
                backend_version: "test".to_string(),
                transport: "uds".to_string(),
                socket_path: "/tmp/kast.sock".to_string(),
                pid: 1,
                schema_version: SCHEMA_VERSION,
            },
            pid_alive: true,
            reachable: true,
            ready: state == RuntimeState::Ready && !indexing,
            runtime_status: Some(RuntimeStatusResponse {
                state,
                healthy: true,
                active: true,
                indexing,
                backend_name: name.to_string(),
                backend_version: "test".to_string(),
                workspace_root: "/tmp/ws".to_string(),
                message: None,
                warnings: vec![],
                source_module_names: vec![],
                dependent_module_names_by_source_module_name: Default::default(),
                reference_index_ready: false,
                schema_version: SCHEMA_VERSION,
            }),
            capabilities: None,
            error_message: None,
            schema_version: SCHEMA_VERSION,
        }
    }

    #[test]
    fn servable_selection_prefers_idea() {
        let candidates = vec![
            candidate("headless", RuntimeState::Ready, false),
            candidate("idea", RuntimeState::Ready, false),
        ];
        let selected = select_servable(&candidates, None, false).unwrap();
        assert_eq!(selected.descriptor.backend_name, "idea");
    }

    #[test]
    fn indexing_requires_accept_indexing() {
        let candidates = vec![candidate("headless", RuntimeState::Indexing, true)];
        assert!(select_servable(&candidates, None, false).is_none());
        assert!(select_servable(&candidates, None, true).is_some());
    }
}
