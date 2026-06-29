pub trait InstallReporter {
    fn idea_plugin_step_started(&mut self, _message: &str) -> Result<()> {
        Ok(())
    }
    fn idea_plugin_step_finished(&mut self, _message: &str) -> Result<()> {
        Ok(())
    }
    fn idea_plugin_step_failed(&mut self, _message: &str) -> Result<()> {
        Ok(())
    }
    fn idea_plugin_plan(&mut self, _plan: &IdeaPluginDownloadPlan) -> Result<()> {
        Ok(())
    }
    fn idea_plugin_download_progress(&mut self, _downloaded_bytes: u64) -> Result<()> {
        Ok(())
    }
    fn idea_plugin_download_finished(&mut self, _downloaded_bytes: u64) -> Result<()> {
        Ok(())
    }
}

pub struct NoopInstallReporter;

impl InstallReporter for NoopInstallReporter {}

pub struct HumanInstallReporter {
    active_spinner: Option<ProgressBar>,
}

impl HumanInstallReporter {
    pub fn new() -> Self {
        Self {
            active_spinner: None,
        }
    }

    fn start_spinner(&mut self, message: &str) {
        self.clear_spinner();
        if progress_draw_target_is_visible() {
            let spinner =
                ProgressBar::with_draw_target(None, ProgressDrawTarget::stderr_with_hz(12));
            spinner.set_style(spinner_style());
            spinner.set_message(message.to_string());
            spinner.enable_steady_tick(Duration::from_millis(80));
            self.active_spinner = Some(spinner);
        } else {
            eprintln!("-> {message}");
        }
    }

    fn finish_spinner(&mut self, message: &str) {
        match self.active_spinner.take() {
            Some(spinner) => spinner.finish_with_message(format!("✓ {message}")),
            None => eprintln!("✓ {message}"),
        }
    }

    fn fail_spinner(&mut self, message: &str) {
        match self.active_spinner.take() {
            Some(spinner) => spinner.abandon_with_message(format!("! {message}")),
            None => eprintln!("! {message}"),
        }
    }

    fn clear_spinner(&mut self) {
        if let Some(spinner) = self.active_spinner.take() {
            spinner.finish_and_clear();
        }
    }
}

impl Drop for HumanInstallReporter {
    fn drop(&mut self) {
        self.clear_spinner();
    }
}

#[derive(Debug, Clone)]
pub struct IdeaPluginDownloadPlan {
    pub cask_token: String,
    pub plugin_version: String,
    pub download_cache: PathBuf,
    pub plugin_directories: Vec<PathBuf>,
}

fn format_bytes(bytes: u64) -> String {
    const KIB: u64 = 1024;
    const MIB: u64 = KIB * 1024;
    if bytes >= MIB {
        format!("{:.1} MiB", bytes as f64 / MIB as f64)
    } else if bytes >= KIB {
        format!("{:.1} KiB", bytes as f64 / KIB as f64)
    } else {
        format!("{bytes} B")
    }
}

fn progress_draw_target_is_visible() -> bool {
    io::stderr().is_terminal()
        && !env::var("TERM").is_ok_and(|terminal| terminal.eq_ignore_ascii_case("dumb"))
}

fn spinner_style() -> ProgressStyle {
    ProgressStyle::with_template("{spinner} {msg}")
        .expect("static spinner template should be valid")
        .tick_strings(&["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"])
}

impl InstallReporter for HumanInstallReporter {
    fn idea_plugin_step_started(&mut self, message: &str) -> Result<()> {
        self.start_spinner(message);
        Ok(())
    }

    fn idea_plugin_step_finished(&mut self, message: &str) -> Result<()> {
        self.finish_spinner(message);
        Ok(())
    }

    fn idea_plugin_step_failed(&mut self, message: &str) -> Result<()> {
        self.fail_spinner(message);
        Ok(())
    }

    fn idea_plugin_plan(&mut self, plan: &IdeaPluginDownloadPlan) -> Result<()> {
        self.clear_spinner();
        eprintln!();
        eprintln!("Kast IDEA plugin install");
        eprintln!("  cask token:      {}", plan.cask_token);
        eprintln!("  plugin version:  {}", plan.plugin_version);
        eprintln!("  download cache:  {}", plan.download_cache.display());
        if !plan.plugin_directories.is_empty() {
            eprintln!("  destinations:");
            for directory in &plan.plugin_directories {
                eprintln!("    - {}", directory.display());
            }
        }
        Ok(())
    }

    fn idea_plugin_download_progress(&mut self, downloaded_bytes: u64) -> Result<()> {
        if let Some(spinner) = &self.active_spinner {
            spinner.set_message(format!(
                "Fetching Homebrew cask ({})",
                format_bytes(downloaded_bytes)
            ));
        }
        Ok(())
    }

    fn idea_plugin_download_finished(&mut self, downloaded_bytes: u64) -> Result<()> {
        self.finish_spinner(&format!(
            "Fetched Homebrew cask ({})",
            format_bytes(downloaded_bytes)
        ));
        Ok(())
    }
}
