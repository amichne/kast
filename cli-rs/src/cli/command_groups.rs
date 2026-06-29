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
    /// Open the interactive source-index demo backed by source-index.db.
    Demo(DemoArgs),
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
    /// Install the Homebrew-managed IDEA plugin cask and link JetBrains profiles.
    Plugin(IdeaPluginInstallArgs),
    /// Install shell PATH and completion integration.
    Shell(ShellInstallArgs),
    /// Print shell completion scripts.
    #[command(hide = true)]
    Completion(CompletionArgs),
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
