#[derive(Debug, Parser)]
#[command(
    name = "kast",
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
    /// Install or refresh one verified Kast release.
    Setup(SetupArgs),
    /// Verify that Kast is ready for a task.
    Ready(ReadyArgs),
    /// Check the current workspace status.
    Status(RuntimeArgs),
    /// Explore a guided semantic story from this Kotlin repository.
    Demo(PublicDemoArgs),
    /// Developer and release-engineering commands.
    Developer(DeveloperArgs),
    /// Backward-compatible alias for `ready`. Used by kast-action v2.
    #[command(hide = true)]
    Doctor(DoctorArgs),
    /// Agent setup, readiness, LSP, and pipe-friendly semantic requests.
    Agent(AgentArgs),
}

#[derive(Debug, Args, Clone)]
pub struct SetupArgs {
    /// Extracted bundle directory or bundle .tar.gz archive.
    #[arg(
        long,
        required_unless_present = "idea_plugin",
        conflicts_with = "idea_plugin"
    )]
    pub source: Option<PathBuf>,
    /// IDEA plugin ZIP to install with the running native CLI.
    #[arg(long, required_unless_present = "source", conflicts_with = "source")]
    pub idea_plugin: Option<PathBuf>,
    /// IntelliJ IDEA or Android Studio plugins directory.
    #[arg(long, requires = "idea_plugin")]
    pub idea_plugins_dir: Option<PathBuf>,
    /// TOML defaults selected by the interactive macOS installer.
    #[arg(long, requires = "idea_plugin")]
    pub config_defaults: Option<PathBuf>,
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
