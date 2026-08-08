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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KastGraphNodesPageToken {
    workspace_fingerprint: String,
    generation: u64,
    after_id: std::num::NonZeroU64,
    returned: std::num::NonZeroU64,
}

impl KastGraphNodesPageToken {
    pub(crate) fn issue(
        workspace_fingerprint: String,
        generation: u64,
        after_id: u64,
        returned: u64,
    ) -> Option<Self> {
        Some(Self {
            workspace_fingerprint,
            generation,
            after_id: std::num::NonZeroU64::new(after_id)?,
            returned: std::num::NonZeroU64::new(returned)?,
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

    pub(crate) fn returned(&self) -> u64 {
        self.returned.get()
    }

    pub(crate) fn canonical(&self) -> String {
        format!(
            "kgn2.{}.{}.{}.{}",
            self.workspace_fingerprint, self.generation, self.after_id, self.returned
        )
    }
}

impl std::str::FromStr for KastGraphNodesPageToken {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.len() > 128 || !value.is_ascii() || value.chars().any(char::is_control) {
            return Err("graph continuation is malformed".to_string());
        }
        let fields = value.split('.').collect::<Vec<_>>();
        if fields.len() != 5
            || fields[0] != "kgn2"
            || fields[1].len() != 24
            || !fields[1]
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        {
            return Err("graph continuation is malformed".to_string());
        }
        let generation = fields[2]
            .parse::<u64>()
            .map_err(|_| "graph continuation is malformed".to_string())?;
        let after_id = fields[3]
            .parse::<std::num::NonZeroU64>()
            .map_err(|_| "graph continuation is malformed".to_string())?;
        let returned = fields[4]
            .parse::<std::num::NonZeroU64>()
            .map_err(|_| "graph continuation is malformed".to_string())?;
        if generation.to_string() != fields[2]
            || after_id.to_string() != fields[3]
            || returned.to_string() != fields[4]
        {
            return Err("graph continuation is malformed".to_string());
        }
        Ok(Self {
            workspace_fingerprint: fields[1].to_string(),
            generation,
            after_id,
            returned,
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
