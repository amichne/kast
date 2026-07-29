#[derive(Debug, Parser)]
#[command(
    name = "kagent",
    version = version(),
    about = "Compiler-backed Kotlin knowledge and changes for coding agents.",
    disable_help_subcommand = true
)]
pub struct KAgentCli {
    #[command(subcommand)]
    pub command: Option<KAgentCommand>,
}

#[derive(Debug, Subcommand)]
pub enum KAgentCommand {
    /// Start or reuse the current workspace and wait for semantic evidence.
    Up,
    /// Refresh changed semantic evidence or externalize an eligible failure.
    Refresh(KAgentRefreshArgs),
    /// List Kotlin source and script files in the current workspace.
    Files {
        /// Optional path or name pattern.
        pattern: Option<String>,
    },
    /// Find symbols and traverse compiler-backed relationships.
    Symbol(KAgentSymbolArgs),
    /// Inspect persisted topology and graph statistics.
    Graph(KAgentGraphArgs),
    /// Check compiler diagnostics for changed or selected files.
    Check(KAgentPathsArgs),
    /// Create a validated semantic change plan.
    Change(KAgentChangeArgs),
    /// Apply one validated semantic change plan.
    Apply {
        /// Opaque plan identifier returned by `kagent change`.
        plan_id: String,
    },
}

#[derive(Debug, Args)]
#[command(args_conflicts_with_subcommands = true, subcommand_precedence_over_arg = true)]
pub struct KAgentRefreshArgs {
    #[command(subcommand)]
    pub command: Option<KAgentRefreshCommand>,
    /// Files to retry even when their content is unchanged.
    #[arg(value_name = "PATH")]
    pub paths: Vec<PathBuf>,
}

#[derive(Debug, Subcommand)]
pub enum KAgentRefreshCommand {
    /// Mark eligible, content-bound failures as external graph boundaries.
    External {
        /// Opaque failure identifiers returned by `kagent refresh`.
        #[arg(required = true)]
        failure_ids: Vec<String>,
    },
}

#[derive(Debug, Args)]
pub struct KAgentPathsArgs {
    /// Files to inspect; defaults to changed Kotlin files.
    #[arg(value_name = "PATH")]
    pub paths: Vec<PathBuf>,
}

#[derive(Debug, Args)]
pub struct KAgentSymbolArgs {
    #[command(subcommand)]
    pub command: KAgentSymbolCommand,
}

#[derive(Debug, Subcommand)]
pub enum KAgentSymbolCommand {
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
pub struct KAgentGraphArgs {
    #[command(subcommand)]
    pub command: Option<KAgentGraphCommand>,
}

#[derive(Debug, Subcommand)]
pub enum KAgentGraphCommand {
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
pub struct KAgentChangeArgs {
    #[command(subcommand)]
    pub command: KAgentChangeCommand,
}

#[derive(Debug, Subcommand)]
pub enum KAgentChangeCommand {
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
