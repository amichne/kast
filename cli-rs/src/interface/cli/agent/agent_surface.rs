#[derive(Debug, Parser)]
#[command(
    name = "kast",
    version = version(),
    about = "Compiler-backed Kotlin knowledge and changes for coding agents.",
    disable_help_subcommand = true,
    after_help = "Developer operations: run `kast` to read `developerOperations`, then invoke `/kast:developer`."
)]
pub struct KastCli {
    /// Select compact TOON or JSON with the same canonical protocol schema.
    #[arg(long, value_enum, global = true, default_value_t = KastOutputFormat::Toon)]
    pub output: KastOutputFormat,
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
        /// Opaque continuation returned as `nextPage`.
        #[arg(long, value_name = "PAGE")]
        page: Option<WorkspaceFilesPublicPageToken>,
    },
    /// Find symbols and traverse compiler-backed relationships.
    Symbol(KastSymbolArgs),
    /// Traverse compiler-backed relationships from an exact selector.
    Relation(KastRelationArgs),
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
    /// Finish or roll back one interrupted semantic change.
    Recover {
        /// Opaque recovery identifier returned by `kast apply`.
        recovery_id: String,
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum KastOutputFormat {
    Json,
    Toon,
}

impl From<KastOutputFormat> for OutputFormat {
    fn from(value: KastOutputFormat) -> Self {
        match value {
            KastOutputFormat::Json => Self::Json,
            KastOutputFormat::Toon => Self::Toon,
        }
    }
}

#[derive(Debug, Args)]
#[command(
    args_conflicts_with_subcommands = true,
    subcommand_precedence_over_arg = true
)]
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
    /// Find incoming callers.
    Callers {
        symbol: String,
        #[arg(long, value_name = "PAGE")]
        page: Option<AgentRelationPageToken>,
    },
    /// Find outgoing callees.
    Callees {
        symbol: String,
        #[arg(long, value_name = "PAGE")]
        page: Option<AgentRelationPageToken>,
    },
    /// Find implementations.
    Implementations {
        symbol: String,
        #[arg(long, value_name = "PAGE")]
        page: Option<AgentRelationPageToken>,
    },
    /// Find direct and transitive supertypes.
    Supertypes {
        symbol: String,
        #[arg(long, value_name = "PAGE")]
        page: Option<AgentRelationPageToken>,
    },
    /// Find direct and transitive subtypes.
    Subtypes {
        symbol: String,
        #[arg(long, value_name = "PAGE")]
        page: Option<AgentRelationPageToken>,
    },
}

#[derive(Debug, Args)]
pub struct KastRelationArgs {
    #[command(subcommand)]
    pub command: KastRelationCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastRelationCommand {
    /// Find references to one exact compiler-backed symbol.
    References {
        #[arg(long)]
        selector: String,
        /// Opaque resumption value returned by this operation.
        #[arg(long)]
        continuation: Option<String>,
    },
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
    /// Enumerate graph nodes.
    Nodes {
        /// Opaque continuation returned as `nextPage`.
        #[arg(long, value_name = "PAGE")]
        page: Option<KastGraphNodesPageToken>,
    },
    /// Show adjacent nodes for one symbol.
    Neighbors { symbol: String },
    /// Report topology statistics.
    Topology(KastGraphProjectionArgs),
    /// Report deterministic graph communities.
    Communities(KastGraphProjectionArgs),
    /// Write an experimental reference-derived topology artifact.
    Derive(KastDerivedTopologyArgs),
    /// Report the bounded impact of one symbol.
    Impact {
        symbol: String,
        #[arg(long, value_name = "PAGE")]
        page: Option<AgentImpactPageToken>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KastGraphNodesPageToken {
    workspace_fingerprint: String,
    generation: u64,
    after_id: std::num::NonZeroU64,
}

impl KastGraphNodesPageToken {
    pub(crate) fn issue(
        workspace_fingerprint: String,
        generation: u64,
        after_id: u64,
    ) -> Option<Self> {
        Some(Self {
            workspace_fingerprint,
            generation,
            after_id: std::num::NonZeroU64::new(after_id)?,
        })
    }

    pub(crate) fn workspace_fingerprint(&self) -> &str {
        &self.workspace_fingerprint
    }

    pub(crate) fn generation(&self) -> u64 {
        self.generation
    }

    pub(crate) fn after_id(&self) -> u64 {
        self.after_id.get()
    }

    pub(crate) fn canonical(&self) -> String {
        format!(
            "kgn1.{}.{}.{}",
            self.workspace_fingerprint, self.generation, self.after_id
        )
    }
}

impl std::str::FromStr for KastGraphNodesPageToken {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.len() > 128 || !value.is_ascii() || value.chars().any(char::is_control) {
            return Err("graph page token is malformed".to_string());
        }
        let fields = value.split('.').collect::<Vec<_>>();
        if fields.len() != 4
            || fields[0] != "kgn1"
            || fields[1].len() != 24
            || !fields[1]
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err("graph page token is malformed".to_string());
        }
        let generation = fields[2]
            .parse::<u64>()
            .map_err(|_| "graph page token is malformed".to_string())?;
        let after_id = fields[3]
            .parse::<std::num::NonZeroU64>()
            .map_err(|_| "graph page token is malformed".to_string())?;
        if generation.to_string() != fields[2] || after_id.to_string() != fields[3] {
            return Err("graph page token is malformed".to_string());
        }
        Ok(Self {
            workspace_fingerprint: fields[1].to_string(),
            generation,
            after_id,
        })
    }
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
    /// Add a declaration at the bottom of one file from stdin.
    AddDeclaration { path: PathBuf },
    /// Replace one named declaration with content from stdin.
    Replace { symbol: String },
}
