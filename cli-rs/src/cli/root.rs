#[derive(Debug, Parser)]
#[command(
    name = "kast",
    version = version(),
    about = "Repo-local control plane for workspace daemons and Kotlin analysis requests.",
    disable_help_subcommand = true
)]
pub struct Cli {
    /// Select readable text or machine-readable JSON for operator command output.
    #[arg(long, value_enum, global = true, default_value = "human")]
    pub output: OutputFormat,
    #[command(subcommand)]
    pub command: Option<Command>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum OutputFormat {
    Human,
    Json,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Browse the command tree and scoped help pages.
    Help {
        #[arg(trailing_var_arg = true)]
        topic: Vec<String>,
    },
    /// Print the packaged CLI version.
    Version,
    /// Raw JSON-RPC transport escape hatch.
    #[command(hide = true)]
    Rpc(RpcArgs),
    /// Verify that Kast is ready for a task.
    Ready(ReadyArgs),
    /// Backward-compatible alias for `ready`. Used by kast-action v2.
    #[command(hide = true)]
    Doctor(ReadyArgs),
    /// Agent setup, readiness, LSP, and pipe-friendly semantic requests.
    Agent(AgentArgs),
    /// Manage backend runtime lifecycle.
    Runtime(RuntimeCommandArgs),
    /// Inspect local Kast state, catalogs, demos, and source-index metrics.
    Inspect(InspectArgs),
    /// Manage machine-local Kast integrations.
    Machine(MachineArgs),
    /// Build, activate, and validate release artifacts.
    Release(ReleaseArgs),
}

#[derive(Debug, Args, Clone)]
pub struct ReadyArgs {
    /// Task surface to verify.
    #[arg(long = "for", value_enum, default_value = "agent")]
    pub target: ReadyTarget,
    /// Apply safe install-state repairs before checking readiness.
    #[arg(long)]
    pub fix: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ReadyTarget {
    Agent,
    Kotlin,
    Release,
    Machine,
}
