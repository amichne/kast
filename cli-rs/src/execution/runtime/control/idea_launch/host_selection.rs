#[cfg(any(target_os = "macos", test))]
fn select_running_idea_host(
    descriptors: &[ServerInstanceDescriptor],
) -> Result<Option<ServerInstanceDescriptor>> {
    let mut by_pid = std::collections::BTreeMap::new();
    for descriptor in descriptors
        .iter()
        .filter(|descriptor| descriptor.backend_name == BackendName::Idea.canonical())
    {
        by_pid
            .entry(descriptor.pid)
            .or_insert_with(|| descriptor.clone());
    }
    match by_pid.len() {
        0 => Ok(None),
        1 => Ok(by_pid.into_values().next()),
        count => {
            let mut error = CliError::new(
                "IDEA_HOST_AMBIGUOUS",
                "More than one compatible IDEA process is running; set runtime.ideaLaunch.command to the intended application.",
            );
            error
                .details
                .insert("candidateCount".to_string(), count.to_string());
            Err(error)
        }
    }
}

#[cfg(target_os = "macos")]
fn select_running_idea_host_for_app(
    descriptors: &[ServerInstanceDescriptor],
    app: &Path,
    app_for_pid: impl Fn(u64) -> Option<PathBuf>,
) -> Result<Option<ServerInstanceDescriptor>> {
    let matching = descriptors
        .iter()
        .filter(|descriptor| descriptor.backend_name == BackendName::Idea.canonical())
        .filter(|descriptor| {
            app_for_pid(descriptor.pid)
                .as_deref()
                .is_some_and(|candidate| same_file_or_path(candidate, app))
        })
        .cloned()
        .collect::<Vec<_>>();
    select_running_idea_host(&matching)
}

#[cfg(target_os = "macos")]
fn running_idea_app(pid: u64) -> Option<PathBuf> {
    let output = Command::new("ps")
        .arg("-p")
        .arg(pid.to_string())
        .args(["-o", "comm="])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let executable = String::from_utf8(output.stdout).ok()?;
    let app = idea_app_bundle_for_executable(Path::new(executable.trim()))?;
    fs::canonicalize(&app).ok().or(Some(app))
}

#[cfg(target_os = "macos")]
fn require_compatible_running_idea_plugin(
    host: &ServerInstanceDescriptor,
    config: &KastConfig,
) -> Result<()> {
    self_mgmt::validate_macos_running_plugin_workspace(
        Path::new(&host.workspace_root),
        &host.backend_version,
        config.runtime.strict_plugin_matching,
    )
    .map_err(|error| {
        CliError::new(
            "IDEA_PLUGIN_UPDATE_REQUIRED",
            format!(
                "The running IDEA process does not advertise a compatible Kast plugin: {} Run `kast setup`, restart that IDE only if requested, and retry.",
                error.message,
            ),
        )
    })
}

#[cfg(target_os = "macos")]
fn idea_app_bundle_for_executable(executable: &Path) -> Option<PathBuf> {
    executable
        .ancestors()
        .find(|ancestor| ancestor.extension().is_some_and(|extension| extension == "app"))
        .map(Path::to_path_buf)
}

#[cfg(target_os = "macos")]
fn same_file_or_path(left: &Path, right: &Path) -> bool {
    fs::canonicalize(left).unwrap_or_else(|_| left.to_path_buf())
        == fs::canonicalize(right).unwrap_or_else(|_| right.to_path_buf())
}

#[cfg(target_os = "macos")]
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IdeaOpenProjectRequest {
    canonical_root: PathBuf,
    request_id: Uuid,
    target_pid: Option<u64>,
    target_product_code: Option<String>,
    expires_at_epoch_millis: u64,
}

#[cfg(target_os = "macos")]
struct WrittenIdeaOpenProjectRequest {
    canonical_root: PathBuf,
    request_id: Uuid,
    path: PathBuf,
}

#[cfg(target_os = "macos")]
fn write_open_project_request(
    runtime_dir: &Path,
    workspace_root: &Path,
    target_pid: Option<u64>,
    target_product_code: Option<&str>,
) -> Result<WrittenIdeaOpenProjectRequest> {
    let canonical_root = fs::canonicalize(workspace_root).map_err(|error| {
        CliError::new(
            "WORKSPACE_ROOT_INVALID",
            format!(
                "Cannot canonicalize workspace root {}: {error}",
                workspace_root.display()
            ),
        )
    })?;
    let request_id = Uuid::new_v4();
    let directory = runtime_dir.join("idea-open-requests");
    fs::create_dir_all(&directory)?;
    let path = directory.join(format!("{request_id}.json"));
    let temporary = directory.join(format!(".{request_id}-{}.tmp", std::process::id()));
    let request = IdeaOpenProjectRequest {
        canonical_root: canonical_root.clone(),
        request_id,
        target_pid,
        target_product_code: target_product_code.map(str::to_string),
        expires_at_epoch_millis: current_epoch_millis().saturating_add(120_000),
    };
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&temporary)?;
    serde_json::to_writer(&mut file, &request)?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(&temporary, &path)?;
    Ok(WrittenIdeaOpenProjectRequest {
        canonical_root,
        request_id,
        path,
    })
}

#[cfg(target_os = "macos")]
fn current_epoch_millis() -> u64 {
    u64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis(),
    )
    .unwrap_or(u64::MAX)
}

#[cfg(target_os = "macos")]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IdeaOpenProjectResponse {
    result: IdeaOpenProjectResult,
}

#[cfg(target_os = "macos")]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum IdeaOpenProjectResult {
    AlreadyOpen,
    OpenedNewProject,
}

#[cfg(target_os = "macos")]
fn map_open_project_rpc_error(error: CliError) -> CliError {
    match error.details.get("backendCode").map(String::as_str) {
        Some("IDEA_VERSION_UNSUPPORTED") => {
            CliError::new("IDEA_VERSION_UNSUPPORTED", error.message)
        }
        Some("IDEA_PLUGIN_UPDATE_REQUIRED") => {
            CliError::new("IDEA_PLUGIN_UPDATE_REQUIRED", error.message)
        }
        Some("IDEA_HOST_AMBIGUOUS") => CliError::new("IDEA_HOST_AMBIGUOUS", error.message),
        Some("IDEA_OPEN_REQUEST_REJECTED") => {
            CliError::new("IDEA_OPEN_REQUEST_REJECTED", error.message)
        }
        Some("IDEA_PROJECT_OPEN_FAILED") => {
            CliError::new("IDEA_PROJECT_OPEN_FAILED", error.message)
        }
        Some("RPC_ERROR") if error.message.contains("Unknown JSON-RPC method") => CliError::new(
            "IDEA_PLUGIN_UPDATE_REQUIRED",
            "The running IDEA plugin does not support Kast project opening. Run `kast setup`, restart that IDE only if requested, and retry.",
        ),
        _ => error,
    }
}

fn maybe_launch_idea_backend(
    workspace_root: &Path,
    config: &KastConfig,
    preference: RuntimeBackendPreference,
    accept_indexing: bool,
    ops: &dyn IdeaBackendLaunchOps,
) -> Result<Option<(RuntimeCandidateStatus, LaunchDisposition)>> {
    if preference.fixed_backend() != Some(BackendName::Idea) {
        return Ok(None);
    }
    let launch_config = &config.runtime.idea_launch;
    if !launch_config.enabled {
        return Ok(None);
    }
    if !config.backends.idea.enabled {
        return Err(CliError::new(
            "IDEA_BACKEND_DISABLED",
            "runtime.ideaLaunch is enabled, but backends.idea.enabled is false.",
        ));
    }
    if launch_config.command.as_os_str().is_empty() {
        return Err(CliError::new(
            "IDEA_LAUNCH_CONFIG_INVALID",
            "runtime.ideaLaunch.command must not be empty.",
        ));
    }
    let launch_disposition = ops.launch(&launch_config.command, workspace_root, config)?;
    ops.wait_for_servable(
        workspace_root,
        accept_indexing,
        launch_config.wait_timeout_millis.get(),
    )
    .map(|candidate| Some((candidate, launch_disposition)))
}
