#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct DeveloperArgs {
    #[command(subcommand)]
    pub command: DeveloperCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum DeveloperCommand {
    /// Build and inspect isolated local-development authority.
    Local(LocalDevelopmentArgs),
    /// Manage backend runtime lifecycle.
    Runtime(RuntimeCommandArgs),
    /// Inspect local Kast state, catalogs, demos, and source-index metrics.
    Inspect(InspectArgs),
    /// Manage machine-local Kast integrations.
    Machine(MachineArgs),
    /// Build, activate, and validate release artifacts.
    Release(ReleaseArgs),
    /// Generate and run the Codex plugin contract.
    Codex(CodexArgs),
}

#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct InspectArgs {
    #[command(subcommand)]
    pub command: InspectCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum InspectCommand {
    /// Inspect manifest-backed Kast path resolution.
    Paths(PathsArgs),
    /// Query source-index metrics directly from SQLite.
    Metrics {
        #[command(subcommand)]
        command: MetricsCommand,
    },
    /// Retired developer-only demo; use the root `kast demo` command.
    #[command(hide = true)]
    Demo(RemovedDemoArgs),
    /// Validate catalog requests and checked-in sample payloads.
    Catalog(ValidateArgs),
}

#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct MachineArgs {
    #[command(subcommand)]
    pub command: MachineCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum MachineCommand {
    /// Report the installed machine bundle without changing it.
    Status,
    /// Atomically make this CLI, one IDEA plugin, and embedded resources machine-wide.
    Activate(MachineActivateArgs),
    /// Repair the selected closed-IDE plugin and global agent resources.
    Reconcile(MachineReconcileArgs),
    /// Configure developer-machine defaults to use the IDEA plugin backend.
    Defaults(DeveloperMachineDefaultsArgs),
    /// Install shell PATH and completion integration.
    Shell(ShellInstallArgs),
    /// Print shell completion scripts.
    #[command(hide = true)]
    Completion(CompletionArgs),
}

#[derive(Debug, Args, Clone)]
pub struct MachineActivateArgs {
    /// Exact Kast IDEA plugin ZIP to install beside this running CLI.
    #[arg(long = "idea-plugin")]
    pub idea_plugin: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct MachineReconcileArgs {
    /// Exact JetBrains profile plugins directory to reconcile.
    #[arg(long = "idea-plugins-dir")]
    pub idea_plugins_dir: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct ReleaseArgs {
    #[command(subcommand)]
    pub command: ReleaseCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum ReleaseCommand {
    /// Build distribution artifacts.
    Package(PackageArgs),
    /// Activate a released install artifact.
    Activate(ReleaseActivateArgs),
    /// Regenerate catalog-derived contract artifacts.
    Generate(GenerateArgs),
    /// Validate JSON-RPC request payloads or catalog samples.
    Validate(ValidateArgs),
}

#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct ReleaseActivateArgs {
    #[command(subcommand)]
    pub command: ReleaseActivateCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum ReleaseActivateCommand {
    /// Activate a portable Kast install bundle from its bundled manifest.
    Bundle(ActivateBundleArgs),
}
