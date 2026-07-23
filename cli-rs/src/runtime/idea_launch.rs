trait IdeaBackendLaunchOps {
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
    fn launch(&self, _command: &Path, workspace_root: &Path) -> Result<()> {
        #[cfg(target_os = "macos")]
        let selected_command = macos_intellij_bin()?;
        #[cfg(target_os = "macos")]
        let command = selected_command.as_path();
        #[cfg(not(target_os = "macos"))]
        let command = _command;
        let launch_error = match Command::new(command).arg(workspace_root).spawn() {
            Ok(_) => return Ok(()),
            Err(error) => error,
        };
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
fn macos_intellij_bin() -> Result<PathBuf> {
    use std::os::unix::fs::PermissionsExt;

    let Some(value) =
        std::env::var_os("KAST_INTELLIJ_BIN").filter(|value| !value.is_empty())
    else {
        return Err(CliError::new(
            "KAST_INTELLIJ_BIN_REQUIRED",
            "macOS IDEA autolaunch requires KAST_INTELLIJ_BIN. Set it to the exact IntelliJ executable, for example `/Applications/IntelliJ IDEA.app/Contents/MacOS/idea`.",
        ));
    };
    let path = PathBuf::from(value);
    let valid = path.is_absolute()
        && fs::metadata(&path).is_ok_and(|metadata| {
            metadata.is_file() && metadata.permissions().mode() & 0o111 != 0
        });
    if !valid {
        let mut error = CliError::new(
            "KAST_INTELLIJ_BIN_INVALID",
            "KAST_INTELLIJ_BIN must be an absolute path to an executable IntelliJ binary.",
        );
        error
            .details
            .insert("KAST_INTELLIJ_BIN".to_string(), path.display().to_string());
        return Err(error);
    }
    Ok(path)
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
    ops.launch(&launch_config.command, workspace_root)?;
    ops.wait_for_servable(
        workspace_root,
        accept_indexing,
        launch_config.wait_timeout_millis.get(),
    )
    .map(Some)
}
