#[derive(Debug, Parser)]
#[command(
    name = "kastctl",
    version = version(),
    about = "Repo-local control plane for workspace daemons and Kotlin analysis requests.",
    disable_help_subcommand = true
)]
pub struct Cli {
    /// Select readable text, TOON, or deprecated JSON compatibility output.
    #[arg(long, value_enum, global = true)]
    pub output: Option<OutputFormat>,
    #[command(subcommand)]
    pub command: Option<Command>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum OutputFormat {
    Human,
    Json,
    Toon,
}

impl OutputFormat {
    pub fn is_structured(self) -> bool {
        matches!(self, Self::Json | Self::Toon)
    }
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
    /// Print compact workspace context for agents.
    Context(RuntimeArgs),
    /// Inspect or update workspace-scoped Kast configuration.
    Config(ConfigArgs),
    /// Install or refresh one verified Kast release.
    Setup(SetupArgs),
    /// Verify that Kast is ready for a task.
    Ready(ReadyArgs),
    /// Start or resume the workspace backend and indexing.
    Start(RuntimeArgs),
    /// Check the current workspace status.
    Status(RuntimeArgs),
    /// Stop indexing and the workspace backend.
    Stop(RuntimeArgs),
    /// Explore a guided semantic story from this Kotlin repository.
    Demo(PublicDemoArgs),
    /// Send one JSON-RPC request through Kast's canonical machine surface.
    Rpc(RpcArgs),
    /// Developer and release-engineering commands.
    Developer(DeveloperArgs),
    /// Backward-compatible alias for `ready`.
    #[command(hide = true)]
    Doctor(DoctorArgs),
    /// Agent setup, readiness, and pipe-friendly semantic requests.
    Agent(AgentArgs),
}

#[derive(Debug, Args, Clone)]
pub struct RpcArgs {
    /// Absolute workspace root for local database or backend requests.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Pin backend-routed requests to one runtime.
    #[arg(long = "backend", value_enum)]
    pub backend_name: Option<BackendName>,
    /// Complete JSON-RPC request object.
    #[arg(long)]
    pub request: String,
}

#[derive(Debug, Args, Clone)]
pub struct SetupArgs {
    /// Remove prior Kast-owned state before installing the supplied release.
    #[arg(long)]
    pub force: bool,
    /// Extracted bundle directory or bundle .tar.gz archive.
    #[arg(long)]
    pub source: PathBuf,
}

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
pub struct ReadyArgs {
    #[command(flatten)]
    pub runtime: RuntimeArgs,
    /// Task surface to verify.
    #[arg(long = "for", value_enum, default_value = "agent")]
    pub target: ReadyTarget,
}

#[derive(Debug, Args, Clone)]
pub struct DoctorArgs {
    #[command(flatten)]
    pub runtime: RuntimeArgs,
    /// Task surface to verify. The compatibility alias preserves machine-install semantics.
    #[arg(long = "for", value_enum, default_value = "machine")]
    pub target: ReadyTarget,
}

impl From<DoctorArgs> for ReadyArgs {
    fn from(args: DoctorArgs) -> Self {
        Self {
            runtime: args.runtime,
            target: args.target,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ReadyTarget {
    Agent,
    Kotlin,
    Release,
    Machine,
}
