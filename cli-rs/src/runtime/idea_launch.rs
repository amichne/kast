trait IdeaBackendLaunchOps {
    fn plugin_installed(&self) -> Result<bool>;

    fn launch(&self, command: &Path, workspace_root: &Path) -> Result<()>;

    fn wait_for_servable(
        &self,
        workspace_root: &Path,
        accept_indexing: bool,
        wait_timeout_ms: u64,
    ) -> Result<RuntimeCandidateStatus>;
}

struct SystemIdeaBackendLaunchOps;

impl IdeaBackendLaunchOps for SystemIdeaBackendLaunchOps {
    fn plugin_installed(&self) -> Result<bool> {
        install::kast_idea_plugin_installed()
    }

    fn launch(&self, command: &Path, workspace_root: &Path) -> Result<()> {
        let launch_error = match Command::new(command).arg(workspace_root).spawn() {
            Ok(_) => return Ok(()),
            Err(error) => error,
        };
        if launch_error.kind() == std::io::ErrorKind::NotFound
            && command == Path::new("idea")
            && launch_default_jetbrains_app(workspace_root)
        {
            return Ok(());
        }
        let mut error = CliError::new(
            "IDEA_LAUNCH_FAILED",
            format!(
                "Failed to launch IDEA with `{}` for {}: {error}",
                command.display(),
                workspace_root.display(),
                error = launch_error
            ),
        );
        error
            .details
            .insert("command".to_string(), command.display().to_string());
        error.details.insert(
            "workspaceRoot".to_string(),
            workspace_root.display().to_string(),
        );
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
fn launch_default_jetbrains_app(workspace_root: &Path) -> bool {
    let Ok(Some(app_name)) = install::latest_jetbrains_ide_app_name() else {
        return false;
    };
    let output = Command::new("open")
        .args(["-g", "-a", &app_name])
        .arg(workspace_root)
        .output();
    let Ok(output) = output else {
        return false;
    };
    output.status.success()
}

#[cfg(not(target_os = "macos"))]
fn launch_default_jetbrains_app(_workspace_root: &Path) -> bool {
    false
}

fn maybe_launch_idea_backend(
    workspace_root: &Path,
    config: &KastConfig,
    preference: RuntimeBackendPreference,
    accept_indexing: bool,
    ops: &dyn IdeaBackendLaunchOps,
) -> Result<Option<RuntimeCandidateStatus>> {
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
    if launch_config.require_installed_plugin && !ops.plugin_installed()? {
        let install_command = "kast machine plugin".to_string();
        let mut error = CliError::new(
            "IDEA_PLUGIN_NOT_INSTALLED",
            format!(
                "Cannot launch IDEA for {} because no JetBrains profile with the Kast plugin was found. Install it with: {install_command}",
                workspace_root.display()
            ),
        );
        error
            .details
            .insert("installCommand".to_string(), install_command);
        return Err(error);
    }
    ops.launch(&launch_config.command, workspace_root)?;
    ops.wait_for_servable(
        workspace_root,
        accept_indexing,
        launch_config.wait_timeout_millis.get(),
    )
    .map(Some)
}
