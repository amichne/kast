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
    Setup(SetupArgs),
    /// Verify that Kast is ready for a task.
    Ready(ReadyArgs),
    /// Check the current workspace status.
    Status(RuntimeArgs),
    /// Developer and release-engineering commands.
    Developer(DeveloperArgs),
    /// Backward-compatible alias for `ready`. Used by kast-action v2.
    #[command(hide = true)]
    Doctor(ReadyArgs),
    /// Agent setup, readiness, LSP, and pipe-friendly semantic requests.
    Agent(AgentArgs),
}

#[derive(Debug, Args, Clone)]
pub struct SetupArgs {
    #[command(flatten)]
    pub runtime: RuntimeArgs,
    /// Packaged skill target root. Defaults to configured setup, then .agents/skills.
    #[arg(long = "skill-target-dir")]
    pub skill_target_dir: Option<PathBuf>,
    /// Repository context file to patch with Kast managed guidance.
    #[arg(long = "context-file")]
    pub context_files: Vec<PathBuf>,
    /// Additional AGENTS.md or AGENTS.local.md files to patch with Kast managed guidance.
    #[arg(long = "agents-md", hide = true)]
    pub agents_md: Vec<PathBuf>,
    /// Overwrite existing managed resources.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Do not add managed resource paths to Git info/exclude.
    #[arg(long)]
    pub no_auto_exclude_git: bool,
    /// Explain setup and runtime actions without writing files or starting a backend.
    #[arg(long)]
    pub dry_run: bool,
    /// Skip automatic IDE onboarding/opening steps.
    #[arg(long = "no-open-ide", alias = "no-onboard")]
    pub no_open_ide: bool,
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
