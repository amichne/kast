#[derive(Debug, Parser)]
#[command(
    name = "kast",
    version = version(),
    about = "Compiler-backed Kotlin knowledge and changes for coding agents.",
    disable_help_subcommand = true
)]
pub struct KastCli {
    #[command(subcommand)]
    pub command: Option<KastCommand>,
}

#[derive(Debug, Subcommand)]
pub enum KastCommand {
    #[command(name = "__internal", hide = true)]
    Internal(KastInternalArgs),
    /// Start or reuse the current workspace and wait for semantic evidence.
    Up,
    /// Refresh changed semantic evidence or externalize an eligible failure.
    Refresh(KastRefreshArgs),
    /// List Kotlin source and script files in the current workspace.
    Files {
        /// Optional path or name pattern.
        pattern: Option<String>,
    },
    /// Find symbols and traverse compiler-backed relationships.
    Symbol(KastSymbolArgs),
    /// Inspect persisted topology and graph statistics.
    Graph(KastGraphArgs),
    /// Check compiler diagnostics for changed or selected files.
    Check(KastPathsArgs),
    /// Create a validated semantic change plan.
    Change(KastChangeArgs),
    /// Apply one validated semantic change plan.
    Apply {
        /// Opaque plan identifier returned by `kast change`.
        plan_id: String,
    },
}

#[derive(Debug, Args)]
pub struct KastInternalArgs {
    #[command(subcommand)]
    pub command: KastInternalCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastInternalCommand {
    Resources(KastResourcesArgs),
}

#[derive(Debug, Args)]
pub struct KastResourcesArgs {
    #[command(subcommand)]
    pub command: KastResourcesCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastResourcesCommand {
    Install {
        #[arg(
            long = "harness",
            required = true,
            value_enum,
            action = clap::ArgAction::Append
        )]
        harnesses: Vec<KastHarness>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, ValueEnum)]
pub enum KastHarness {
    Codex,
    Claude,
    Copilot,
}

pub type KAgentHarness = KastHarness;

#[derive(Debug, Args)]
#[command(args_conflicts_with_subcommands = true, subcommand_precedence_over_arg = true)]
pub struct KastRefreshArgs {
    #[command(subcommand)]
    pub command: Option<KastRefreshCommand>,
    /// Files to retry even when their content is unchanged.
    #[arg(value_name = "PATH")]
    pub paths: Vec<PathBuf>,
}

#[derive(Debug, Subcommand)]
pub enum KastRefreshCommand {
    /// Mark eligible, content-bound failures as external graph boundaries.
    External {
        /// Opaque failure identifiers returned by `kast refresh`.
        #[arg(required = true)]
        failure_ids: Vec<String>,
    },
}

#[derive(Debug, Args)]
pub struct KastPathsArgs {
    /// Files to inspect; defaults to changed Kotlin files.
    #[arg(value_name = "PATH")]
    pub paths: Vec<PathBuf>,
}

#[derive(Debug, Args)]
pub struct KastSymbolArgs {
    #[command(subcommand)]
    pub command: KastSymbolCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastSymbolCommand {
    /// Find symbols by name, signature, or fully-qualified name.
    Find { query: String },
    /// Show one symbol selected by query or opaque identifier.
    Show { symbol: String },
    /// Find references to one symbol.
    Refs { symbol: String },
    /// Find incoming callers.
    Callers { symbol: String },
    /// Find outgoing callees.
    Callees { symbol: String },
    /// Find implementations.
    Implementations { symbol: String },
    /// Find direct and transitive supertypes.
    Supertypes { symbol: String },
    /// Find direct and transitive subtypes.
    Subtypes { symbol: String },
}

#[derive(Debug, Args)]
pub struct KastGraphArgs {
    #[command(subcommand)]
    pub command: Option<KastGraphCommand>,
}

#[derive(Debug, Subcommand)]
pub enum KastGraphCommand {
    /// Summarize graph coverage and size.
    Summary,
    /// Enumerate graph nodes.
    Nodes,
    /// Show adjacent nodes for one symbol.
    Neighbors { symbol: String },
    /// Find a bounded path between two symbols.
    Path { from: String, to: String },
    /// Report topology statistics.
    Topology,
    /// Report deterministic graph communities.
    Communities,
    /// Report strongly connected cycles.
    Cycles,
    /// Report bridge nodes and edges.
    Bridges,
    /// Report the bounded impact of one symbol.
    Impact { symbol: String },
}

#[derive(Debug, Args)]
pub struct KastChangeArgs {
    #[command(subcommand)]
    pub command: KastChangeCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastChangeCommand {
    /// Rename one compiler-resolved symbol.
    Rename { symbol: String, new_name: String },
    /// Create a Kotlin file from stdin.
    AddFile { path: PathBuf },
    /// Add a declaration from stdin.
    AddDeclaration { path: PathBuf, scope: Option<String> },
    /// Add implementation content from stdin.
    AddImplementation { path: PathBuf, scope: Option<String> },
    /// Add a statement from stdin inside a named executable scope.
    AddStatement { path: PathBuf, scope: String },
    /// Replace one named declaration with content from stdin.
    Replace { symbol: String },
}
