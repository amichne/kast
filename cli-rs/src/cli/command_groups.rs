#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct DeveloperArgs {
    #[command(subcommand)]
    pub command: DeveloperCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum DeveloperCommand {
    /// Manage backend runtime lifecycle.
    Runtime(RuntimeCommandArgs),
    /// Inspect local Kast state, catalogs, demos, and source-index metrics.
    Inspect(InspectArgs),
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
pub struct ReleaseArgs {
    #[command(subcommand)]
    pub command: ReleaseCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum ReleaseCommand {
    /// Build distribution artifacts.
    Package(PackageArgs),
    /// Regenerate catalog-derived contract artifacts.
    Generate(GenerateArgs),
    /// Validate JSON-RPC request payloads or catalog samples.
    Validate(ValidateArgs),
}
