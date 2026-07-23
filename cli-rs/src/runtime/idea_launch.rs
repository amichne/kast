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
    let explicit_app = (command != Path::new("idea"))
        .then(|| resolve_explicit_idea_app(command))
        .transpose()?;
    let running_host = match explicit_app.as_deref() {
        Some(app) => select_running_idea_host_for_app(&descriptors, app, running_idea_app)?,
        None => select_running_idea_host(&descriptors)?,
    };
    if let Some(host) = running_host {
        require_current_running_idea_plugin(&host)?;
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

    let app = explicit_app.map_or_else(resolve_supported_idea_app, Ok)?;
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
fn resolve_supported_idea_app() -> Result<PathBuf> {
    let mut candidates = idea_apps_in(Path::new("/Applications"), 1);
    if let Some(home) = std::env::var_os("HOME") {
        let home = PathBuf::from(home);
        candidates.extend(idea_apps_in(&home.join("Applications"), 1));
        candidates.extend(idea_apps_in(
            &home.join("Library/Application Support/JetBrains/Toolbox/apps"),
            7,
        ));
    }
    let mut installed = candidates
        .into_iter()
        .filter(|candidate| candidate.is_dir())
        .collect::<Vec<_>>();
    installed.sort();
    installed.dedup();
    select_supported_idea_app(installed)
}

#[cfg(target_os = "macos")]
fn select_supported_idea_app(installed: Vec<PathBuf>) -> Result<PathBuf> {
    let supported = installed
        .iter()
        .filter(|candidate| is_supported_idea_app(candidate))
        .cloned()
        .collect::<Vec<_>>();
    match supported.as_slice() {
        [app] => Ok(app.clone()),
        [] if !installed.is_empty() => Err(CliError::new(
            "IDEA_VERSION_UNSUPPORTED",
            "Kast requires IntelliJ IDEA 2026.2/build 262 or Android Studio 2026.1.2/build 261; no supported installed bundle was found.",
        )),
        [] => Err(CliError::new(
            "IDEA_HOST_NOT_FOUND",
            "Kast could not find IntelliJ IDEA 2026.2 or Android Studio 2026.1.2. Install one or set runtime.ideaLaunch.command to its exact application bundle.",
        )),
        _ => Err(CliError::new(
            "IDEA_HOST_AMBIGUOUS",
            "Multiple supported IntelliJ IDEA or Android Studio application bundles were found; set runtime.ideaLaunch.command to the exact bundle.",
        )),
    }
}

#[cfg(target_os = "macos")]
fn resolve_explicit_idea_app(command: &Path) -> Result<PathBuf> {
    let canonical = fs::canonicalize(command).map_err(|error| {
        CliError::new(
            "IDEA_HOST_NOT_FOUND",
            format!(
                "Configured IDEA application could not be resolved at {}: {error}",
                command.display()
            ),
        )
    })?;
    let app = canonical
        .ancestors()
        .find(|ancestor| ancestor.extension().is_some_and(|extension| extension == "app"))
        .map(Path::to_path_buf)
        .ok_or_else(|| {
            CliError::new(
                "IDEA_LAUNCH_CONFIG_INVALID",
                "runtime.ideaLaunch.command must resolve inside an IntelliJ IDEA or Android Studio .app bundle on macOS.",
            )
        })?;
    ensure_supported_idea_app(&app)?;
    Ok(app)
}

#[cfg(target_os = "macos")]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IdeaProductInfo {
    product_code: String,
    data_directory_name: String,
}

#[cfg(target_os = "macos")]
fn require_current_plugin_for_app(app: &Path, config: &KastConfig) -> Result<()> {
    let home = std::env::var_os("HOME").map(PathBuf::from).ok_or_else(|| {
        CliError::new(
            "IDEA_PLUGIN_UPDATE_REQUIRED",
            "HOME is unavailable, so Kast cannot verify the selected IDE plugin profile.",
        )
    })?;
    let installed_plugin = idea_plugin_directory_for_app(app, &home)?;
    let plugin_archive = config
        .paths
        .install_root
        .join("current/idea/kast.zip");
    if crate::install::idea_plugin_directory_matches_archive(
        &installed_plugin,
        &plugin_archive,
    )
    .unwrap_or(false)
    {
        return Ok(());
    }
    let mut error = CliError::new(
        "IDEA_PLUGIN_UPDATE_REQUIRED",
        format!(
            "The Kast plugin for {} is missing or differs from the active Kast release. Run `kast setup`; close and relaunch only this application if setup requests it.",
            app.display(),
        ),
    );
    error.details.insert(
        "ideaPluginDirectory".to_string(),
        installed_plugin.display().to_string(),
    );
    Err(error)
}

#[cfg(target_os = "macos")]
fn idea_plugin_directory_for_app(app: &Path, home: &Path) -> Result<PathBuf> {
    let product = fs::read_to_string(app.join("Contents/Resources/product-info.json"))
        .ok()
        .and_then(|raw| serde_json::from_str::<IdeaProductInfo>(&raw).ok())
        .ok_or_else(|| {
            CliError::new(
                "IDEA_PLUGIN_UPDATE_REQUIRED",
                format!(
                    "Cannot determine the plugin profile for {}. Run Kast setup for this application before opening the project.",
                    app.display(),
                ),
            )
        })?;
    let vendor = if product.product_code == "AI" {
        "Google"
    } else {
        "JetBrains"
    };
    Ok(home
        .join("Library/Application Support")
        .join(vendor)
        .join(&product.data_directory_name)
        .join("plugins/kast"))
}

#[cfg(target_os = "macos")]
fn idea_apps_in(root: &Path, depth: usize) -> Vec<PathBuf> {
    if depth == 0 {
        return vec![];
    }
    let Ok(entries) = fs::read_dir(root) else {
        return vec![];
    };
    entries
        .filter_map(std::result::Result::ok)
        .flat_map(|entry| {
            let path = entry.path();
            if path.extension().is_some_and(|extension| extension == "app")
                && path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| {
                        name.starts_with("IntelliJ IDEA") || name.starts_with("Android Studio")
                    })
            {
                vec![path]
            } else if entry.file_type().is_ok_and(|kind| kind.is_dir()) {
                idea_apps_in(&path, depth - 1)
            } else {
                vec![]
            }
        })
        .collect()
}

#[cfg(target_os = "macos")]
fn ensure_supported_idea_app(app: &Path) -> Result<()> {
    let build = idea_app_build(app);
    if build.as_ref().is_some_and(IdeaAppBuild::is_supported) {
        Ok(())
    } else {
            let mut error = CliError::new(
                "IDEA_VERSION_UNSUPPORTED",
                format!(
                    "Kast requires IntelliJ IDEA 2026.2/build 262 or Android Studio 2026.1.2/build 261; {} reports {}.",
                    app.display(),
                    build
                        .map(|build| format!("{}-{}", build.product_code, build.branch))
                        .unwrap_or_else(|| "an unknown build".to_string()),
                ),
            );
            error
                .details
                .insert("ideaApp".to_string(), app.display().to_string());
            Err(error)
    }
}

#[cfg(target_os = "macos")]
fn is_supported_idea_app(app: &Path) -> bool {
    idea_app_build(app)
        .as_ref()
        .is_some_and(IdeaAppBuild::is_supported)
}

#[cfg(target_os = "macos")]
#[derive(Debug)]
struct IdeaAppBuild {
    product_code: String,
    branch: String,
}

#[cfg(target_os = "macos")]
impl IdeaAppBuild {
    fn is_supported(&self) -> bool {
        matches!(
            (self.product_code.as_str(), self.branch.as_str()),
            ("AI", "261") | ("IC" | "IU", "262")
        )
    }
}

#[cfg(target_os = "macos")]
fn idea_app_build(app: &Path) -> Option<IdeaAppBuild> {
    fs::read_to_string(app.join("Contents/Resources/build.txt"))
        .ok()
        .and_then(|build| {
            let (product_code, version) = build.trim().split_once('-')?;
            let branch = version.split('.').next()?;
            Some(IdeaAppBuild {
                product_code: product_code.to_string(),
                branch: branch.to_string(),
            })
        })
}

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
fn require_current_running_idea_plugin(host: &ServerInstanceDescriptor) -> Result<()> {
    let cli_version = crate::cli::version();
    if host.backend_version == cli_version {
        return Ok(());
    }
    Err(CliError::new(
        "IDEA_PLUGIN_UPDATE_REQUIRED",
        format!(
            "The running IDEA process has Kast plugin {} but this CLI is {cli_version}. Run `kast setup`, restart that IDE only if requested, and retry.",
            host.backend_version,
        ),
    ))
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
    if !launch_config.enabled && !cfg!(target_os = "macos") {
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
