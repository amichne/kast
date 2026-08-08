#[derive(Debug, Args)]
pub struct KastWorkspaceArgs {
    #[command(subcommand)]
    pub command: KastWorkspaceCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastWorkspaceCommand {
    /// Start or reuse the exact workspace and wait for semantic evidence.
    Ensure,
    /// Refresh changed semantic evidence or selected Kotlin files.
    Refresh {
        /// Files to retry even when their content is unchanged.
        #[arg(long = "file", value_name = "PATH")]
        files: Vec<PathBuf>,
    },
    /// Mark eligible content-bound failures as external graph boundaries.
    Externalize {
        /// Opaque failure identifiers returned by workspace refresh.
        #[arg(long = "failure-id", required = true)]
        failure_ids: Vec<String>,
    },
}

#[derive(Debug, Args)]
pub struct KastFileArgs {
    #[command(subcommand)]
    pub command: KastFileCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastFileCommand {
    /// List Kotlin source and script files in the current workspace.
    List {
        /// Optional workspace-relative path or name pattern.
        #[arg(long = "match")]
        pattern: Option<String>,
        /// Opaque resumption value returned by this operation.
        #[arg(long)]
        continuation: Option<WorkspaceFilesPublicPageToken>,
    },
}

#[derive(Debug, Args)]
pub struct KastDiagnosticArgs {
    #[command(subcommand)]
    pub command: KastDiagnosticCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastDiagnosticCommand {
    /// Check changed or explicitly selected Kotlin files.
    Check {
        #[arg(long = "file", value_name = "PATH")]
        files: Vec<PathBuf>,
    },
}

#[derive(Debug, Args)]
pub struct KastSymbolArgs {
    #[command(subcommand)]
    pub command: KastSymbolCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastSymbolCommand {
    /// Find symbols by untrusted name, signature, or fully-qualified text.
    Search {
        #[arg(long)]
        query: String,
    },
    /// Resolve query text to one exact compiler-backed symbol selector.
    Resolve {
        #[arg(long)]
        query: String,
    },
    /// Show one symbol selected by an opaque Kast-issued selector.
    Show {
        #[arg(long)]
        selector: String,
    },
}

#[derive(Debug, Args)]
pub struct KastExactRelationArgs {
    /// Opaque root-bound, generation-bound selector issued by Kast.
    #[arg(long)]
    pub selector: String,
    /// Opaque resumption value returned by this exact operation.
    #[arg(long)]
    pub continuation: Option<String>,
}

#[derive(Debug, Args)]
pub struct KastRelationArgs {
    #[command(subcommand)]
    pub command: KastRelationCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastRelationCommand {
    /// Find source references to one exact symbol.
    References(KastExactRelationArgs),
    /// Traverse incoming or outgoing compiler-backed calls.
    Calls(KastRelationCallsArgs),
    /// Find implementations of one exact declaration.
    Implementations(KastExactRelationArgs),
    /// Traverse exact type hierarchy relations.
    Hierarchy(KastRelationHierarchyArgs),
}

#[derive(Debug, Args)]
pub struct KastRelationCallsArgs {
    #[command(subcommand)]
    pub command: KastRelationCallsCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastRelationCallsCommand {
    /// Find incoming callers.
    Incoming(KastExactRelationArgs),
    /// Find outgoing callees.
    Outgoing(KastExactRelationArgs),
}

#[derive(Debug, Args)]
pub struct KastRelationHierarchyArgs {
    #[command(subcommand)]
    pub command: KastRelationHierarchyCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastRelationHierarchyCommand {
    /// Find direct and transitive supertypes.
    Supertypes(KastExactRelationArgs),
    /// Find direct and transitive subtypes.
    Subtypes(KastExactRelationArgs),
}

#[derive(Debug, Args)]
pub struct KastGraphArgs {
    #[command(subcommand)]
    pub command: Option<KastGraphCommand>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum KastGraphScope {
    Symbol,
    Package,
    Module,
}

impl From<KastGraphScope> for NativeGraphScope {
    fn from(scope: KastGraphScope) -> Self {
        match scope {
            KastGraphScope::Symbol => Self::Symbol,
            KastGraphScope::Package => Self::Package,
            KastGraphScope::Module => Self::Module,
        }
    }
}

#[derive(Debug, Args)]
pub struct KastGraphProjectionArgs {
    /// Read-only topology projection.
    #[arg(long, value_enum, default_value_t = KastGraphScope::Symbol)]
    pub scope: KastGraphScope,
}

#[derive(Debug, Args)]
#[command(
    after_help = "Examples:\n  kast graph derive --experimental-derived-topology --out .kast/topology.json\n  kast graph derive --experimental-derived-topology --out .kast/current.json --prior .kast/previous.json"
)]
pub struct KastDerivedTopologyArgs {
    /// Acknowledge that this artifact contains experimental statistical facts.
    #[arg(long, required = true, action = clap::ArgAction::SetTrue)]
    pub experimental_derived_topology: bool,
    /// New workspace-relative JSON artifact path.
    #[arg(long, value_name = "PATH")]
    pub out: PathBuf,
    /// Optional earlier workspace-relative artifact used for lineage.
    #[arg(long, value_name = "PATH")]
    pub prior: Option<PathBuf>,
}

#[derive(Debug, Subcommand)]
pub enum KastGraphCommand {
    /// Summarize graph coverage and size.
    Summary(KastGraphProjectionArgs),
    /// Enumerate graph nodes and issue a selector for every returned node.
    Nodes {
        #[arg(long)]
        continuation: Option<String>,
    },
    /// Show adjacent nodes for one exact graph node selector.
    Neighbors {
        #[arg(long)]
        node_selector: String,
    },
    /// Report topology statistics.
    Topology(KastGraphProjectionArgs),
    /// Report deterministic graph communities.
    Communities(KastGraphProjectionArgs),
    /// Write an experimental reference-derived topology artifact.
    Derive(KastDerivedTopologyArgs),
    /// Report bounded impact from one exact symbol selector.
    Impact(KastExactRelationArgs),
}

#[derive(Debug, Args)]
pub struct KastChangeArgs {
    #[command(subcommand)]
    pub command: KastChangeCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastChangeCommand {
    /// Create a validated semantic change plan.
    Plan(KastChangePlanArgs),
    /// Apply one validated plan.
    Apply {
        #[arg(long)]
        plan_id: String,
    },
    /// Finish or roll back one interrupted semantic change.
    Recover {
        #[arg(long)]
        recovery_id: String,
    },
}

#[derive(Debug, Args)]
pub struct KastChangePlanArgs {
    #[command(subcommand)]
    pub command: KastChangePlanCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastChangePlanCommand {
    /// Rename one exact compiler-resolved symbol.
    Rename {
        #[arg(long)]
        selector: String,
        #[arg(long)]
        name: String,
    },
    /// Create a Kotlin file from stdin.
    AddFile {
        #[arg(long)]
        file: PathBuf,
    },
    /// Add a declaration at the bottom of one file from stdin.
    AddDeclaration {
        #[arg(long)]
        file: PathBuf,
    },
    /// Replace one exact named declaration with content from stdin.
    Replace {
        #[arg(long)]
        selector: String,
    },
}
