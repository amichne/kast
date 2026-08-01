trait IdeaBackendLaunchOps {
    fn launch(
        &self,
        command: &Path,
        workspace_root: &Path,
        config: &KastConfig,
    ) -> Result<LaunchDisposition>;

    fn wait_for_servable(
        &self,
        workspace_root: &Path,
        accept_indexing: bool,
        wait_timeout_ms: u64,
    ) -> Result<RuntimeCandidateStatus>;
}

struct SystemIdeaBackendLaunchOps;

impl IdeaBackendLaunchOps for SystemIdeaBackendLaunchOps {
    fn launch(
        &self,
        command: &Path,
        workspace_root: &Path,
        config: &KastConfig,
    ) -> Result<LaunchDisposition> {
        #[cfg(target_os = "macos")]
        return open_macos_idea_project(command, workspace_root, config);
        #[cfg(not(target_os = "macos"))]
        let _ = config;
        #[cfg(not(target_os = "macos"))]
        let launch_error = match Command::new(command).arg(workspace_root).spawn() {
            Ok(_) => return Ok(LaunchDisposition::LaunchedIdea),
            Err(error) => error,
        };
        #[cfg(not(target_os = "macos"))]
        let mut error = CliError::new(
            "IDEA_LAUNCH_FAILED",
            format!(
                "Failed to launch IDEA with `{}` for {}: {error}",
                command.display(),
                workspace_root.display(),
                error = launch_error
            ),
        );
        #[cfg(not(target_os = "macos"))]
        error
            .details
            .insert("command".to_string(), command.display().to_string());
        #[cfg(not(target_os = "macos"))]
        error.details.insert(
            "workspaceRoot".to_string(),
            workspace_root.display().to_string(),
        );
        #[cfg(not(target_os = "macos"))]
        Err(error)
    }

    fn wait_for_servable(
        &self,
        workspace_root: &Path,
        accept_indexing: bool,
        wait_timeout_ms: u64,
    ) -> Result<RuntimeCandidateStatus> {
        wait_for_servable(
            workspace_root,
            Some(BackendName::Idea),
            accept_indexing,
            wait_timeout_ms,
        )
    }
}

#[cfg(target_os = "macos")]
fn macos_open_arguments(app: &Path) -> [std::ffi::OsString; 4] {
    [
        "-j".into(),
        "-g".into(),
        "-a".into(),
        app.as_os_str().to_os_string(),
    ]
}

#[cfg(target_os = "macos")]
fn open_macos_idea_project(
    command: &Path,
    workspace_root: &Path,
    config: &KastConfig,
) -> Result<LaunchDisposition> {
    let descriptors = read_descriptors(&config.paths.descriptor_dir)?
        .into_iter()
        .filter(|descriptor| is_process_alive(descriptor.pid))
        .filter(|descriptor| Path::new(&descriptor.socket_path).exists())
        .collect::<Vec<_>>();
    let app = select_macos_idea_app_for_workspace(
        workspace_root,
        &descriptors,
        command,
        installed_idea_apps(),
        running_idea_app,
    )?;
    let running_host =
        select_running_idea_host_for_app(&descriptors, &app, running_idea_app)?;
    if let Some(host) = running_host {
        require_compatible_running_idea_plugin(&host, config)?;
        let request =
            write_open_project_request(
                &config.paths.runtime_dir,
                workspace_root,
                Some(host.pid),
                None,
            )?;
        let result = rpc::request::<IdeaOpenProjectResponse>(
            Path::new(&host.socket_path),
            "runtime/open-project",
            serde_json::json!({
                "canonicalRoot": request.canonical_root,
                "requestId": request.request_id,
            }),
        )
        .map_err(|error| {
            let _ = fs::remove_file(&request.path);
            map_open_project_rpc_error(error)
        })?;
        return match result.result {
            IdeaOpenProjectResult::AlreadyOpen => Ok(LaunchDisposition::ReusedOpenProject),
            IdeaOpenProjectResult::OpenedNewProject => {
                Ok(LaunchDisposition::OpenedInRunningIdea)
            }
        };
    }

    require_current_plugin_for_app(&app, config)?;
    let product_code = idea_app_build(&app)
        .expect("supported IDEA app must retain its parsed build")
        .product_code;
    let request = write_open_project_request(
        &config.paths.runtime_dir,
        workspace_root,
        None,
        Some(&product_code),
    )?;
    let output = Command::new("open")
        .args(macos_open_arguments(&app))
        .arg(workspace_root)
        .output()
        .map_err(|error| {
            let _ = fs::remove_file(&request.path);
            CliError::new(
                "IDEA_LAUNCH_FAILED",
                format!("Failed to launch {}: {error}", app.display()),
            )
        })?;
    if !output.status.success() {
        let _ = fs::remove_file(&request.path);
        return Err(CliError::new(
            "IDEA_LAUNCH_FAILED",
            format!(
                "Failed to background-open {} with {}",
                workspace_root.display(),
                app.display(),
            ),
        ));
    }
    Ok(LaunchDisposition::LaunchedIdea)
}

#[cfg(target_os = "macos")]
pub(crate) fn resolve_installed_idea_sidecar_app(
    workspace_root: &Path,
    config: &KastConfig,
) -> Result<PathBuf> {
    let descriptors = read_descriptors(&config.paths.descriptor_dir)?
        .into_iter()
        .filter(|descriptor| is_process_alive(descriptor.pid))
        .filter(|descriptor| Path::new(&descriptor.socket_path).exists())
        .collect::<Vec<_>>();
    select_macos_idea_app_for_workspace(
        workspace_root,
        &descriptors,
        &config.runtime.idea_launch.command,
        installed_idea_apps(),
        running_idea_app,
    )
}

#[cfg(target_os = "macos")]
fn select_macos_idea_app_for_workspace(
    workspace_root: &Path,
    descriptors: &[ServerInstanceDescriptor],
    configured_command: &Path,
    installed: Vec<PathBuf>,
    app_for_pid: impl Fn(u64) -> Option<PathBuf>,
) -> Result<PathBuf> {
    let normalized_workspace = config::normalize(workspace_root.to_path_buf());
    let mut owner_apps = Vec::<PathBuf>::new();
    for app in descriptors
        .iter()
        .filter(|descriptor| descriptor.backend_name == BackendName::Idea.canonical())
        .filter(|descriptor| descriptor_matches_workspace(descriptor, &normalized_workspace))
        .filter_map(|descriptor| app_for_pid(descriptor.pid))
    {
        if !owner_apps
            .iter()
            .any(|existing| same_file_or_path(existing, &app))
        {
            owner_apps.push(app);
        }
    }
    match owner_apps.as_slice() {
        [owner] => {
            ensure_supported_idea_app(owner)?;
            return Ok(owner.clone());
        }
        [] => {}
        owners => {
            let mut error = CliError::new(
                "IDEA_HOST_AMBIGUOUS",
                "More than one supported IDEA application owns this exact workspace root.",
            );
            error
                .details
                .insert("candidateCount".to_string(), owners.len().to_string());
            error.details.insert(
                "workspaceRoot".to_string(),
                normalized_workspace.display().to_string(),
            );
            return Err(error);
        }
    }

    if configured_command != Path::new("idea") {
        return resolve_explicit_idea_app(configured_command);
    }
    select_supported_idea_app(installed)
}

include!("idea_launch/installed_hosts.rs");

include!("idea_launch/host_selection.rs");
