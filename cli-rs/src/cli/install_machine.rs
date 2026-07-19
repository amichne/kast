#[derive(Debug, Args, Clone)]
pub struct PathsArgs {
    /// Absolute workspace root for workspace-local config inspection.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Show the IDEA host path view.
    #[arg(long)]
    pub idea: bool,
}

#[derive(Debug, Args, Clone)]
pub struct InstallArgs {
    #[command(subcommand)]
    pub command: InstallCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum InstallCommand {
    /// Activate a portable Kast install bundle from its bundled manifest.
    #[command(name = "activate-bundle")]
    ActivateBundle(ActivateBundleArgs),
    /// Install shell PATH and completion integration.
    Shell(ShellInstallArgs),
    /// Print shell completion scripts.
    #[command(hide = true)]
    Completion(CompletionArgs),
}

#[derive(Debug, Args, Clone)]
pub struct ActivateBundleArgs {
    /// Extracted bundle directory or bundle .tar.gz archive.
    #[arg(long)]
    pub source: PathBuf,
    /// Managed install root. Defaults to KAST_INSTALL_ROOT or ~/.local/share/kast.
    #[arg(long)]
    pub install_root: Option<PathBuf>,
    /// Directory for the kast shim. Defaults to ~/.local/bin.
    #[arg(long)]
    pub bin_dir: Option<PathBuf>,
    /// Kast config home. Defaults to KAST_CONFIG_HOME or ~/.config/kast.
    #[arg(long)]
    pub config_home: Option<PathBuf>,
    /// Validate the bundle and current install without changing files.
    #[arg(long)]
    pub verify_only: bool,
}

#[derive(Debug, Args, Clone)]
pub struct InstallRepairArgs {
    /// Apply the planned repairs. Without this flag, no files are changed.
    #[arg(long)]
    pub apply: bool,
    /// JetBrains config root containing IDE profile directories to audit.
    #[arg(long)]
    pub jetbrains_config_root: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
pub struct CompletionArgs {
    /// Shell to generate completion code for.
    #[arg(value_enum)]
    pub shell: ShellKind,
    /// Command name to embed in completion output. Defaults to kast.
    #[arg(long)]
    pub command_name: Option<String>,
}

#[derive(Debug, Args, Clone)]
pub struct ShellInstallArgs {
    /// Shell to install integration for. Defaults to the current SHELL.
    #[arg(long, value_enum)]
    pub shell: Option<ShellKind>,
    /// Shell profile file to patch. Defaults to ~/.zshrc or ~/.bashrc.
    #[arg(long)]
    pub profile: Option<PathBuf>,
    /// Managed source file to write. Defaults to <KAST_CONFIG_HOME>/shell/<command>.<shell>.
    #[arg(long)]
    pub source_file: Option<PathBuf>,
    /// Command name to add completions for. Defaults to this executable's file name.
    #[arg(long)]
    pub command_name: Option<String>,
    /// Print the planned integration without writing files.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args, Clone)]
pub struct DeveloperMachineDefaultsArgs {
    /// Print the planned developer-machine config without writing it.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args, Clone)]
pub struct ResourceInstallArgs {
    /// Target root directory.
    #[arg(long)]
    pub target_dir: Option<PathBuf>,
    /// Directory name for the installed resource. Defaults to kast.
    #[arg(long)]
    pub name: Option<String>,
    /// Override the packaged resource source directory. Developer/test use only.
    #[arg(long)]
    pub source_dir: Option<PathBuf>,
    /// Overwrite existing managed resources.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Do not add managed resource paths to Git info/exclude.
    #[arg(long)]
    pub no_auto_exclude_git: bool,
}
