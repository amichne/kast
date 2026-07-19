#[derive(Debug, Parser)]
#[command(
    name = "kast",
    version = version(),
    about = "Repo-local control plane for workspace daemons and Kotlin analysis requests.",
    disable_help_subcommand = true
)]
pub struct Cli {
    /// Select readable text or machine-readable JSON for operator command output.
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
    /// Print compact workspace context for agents and hooks.
    Context(RuntimeArgs),
    /// Set up Kast for this repository.
    #[cfg_attr(target_os = "macos", command(hide = true))]
    Setup(SetupArgs),
    /// Verify that Kast is ready for a task.
    Ready(ReadyArgs),
    /// Plan or apply safe repair of Kast install state.
    Repair(RepairArgs),
    /// Check the current workspace status.
    Status(RuntimeArgs),
    /// Inspect or manage the one developer-machine Kast installation.
    Machine(MachineArgs),
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
    /// Absolute workspace root for repository guidance setup.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Packaged skill target root. Defaults to configured setup, then .agents/skills.
    #[arg(long = "skill-target-dir")]
    pub skill_target_dir: Option<PathBuf>,
    /// Repository context file to patch with Kast managed guidance.
    #[arg(long = "context-file")]
    pub context_files: Vec<PathBuf>,
    /// Overwrite existing managed resources.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Do not add managed resource paths to Git info/exclude.
    #[arg(long)]
    pub no_auto_exclude_git: bool,
    /// Explain repository setup without writing files.
    #[arg(long)]
    pub dry_run: bool,
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

#[derive(Debug, Args, Clone)]
pub struct RepairArgs {
    #[command(flatten)]
    pub runtime: RuntimeArgs,
    /// Task surface to repair toward.
    #[arg(long = "for", value_enum, default_value = "agent")]
    pub target: ReadyTarget,
    /// Apply the planned install-state repairs.
    #[arg(long)]
    pub apply: bool,
    /// JetBrains config root containing IDE profile directories to audit.
    #[arg(long)]
    pub jetbrains_config_root: Option<PathBuf>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ReadyTarget {
    Agent,
    Kotlin,
    Release,
    Machine,
}
