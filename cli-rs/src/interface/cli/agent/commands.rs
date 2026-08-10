#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct AgentArgs {
    #[command(subcommand)]
    pub command: Option<AgentCommand>,
}

#[derive(Debug, Subcommand, Clone)]
pub enum AgentCommand {
    /// Verify backend health, runtime state, and capabilities.
    Verify(AgentVerifyArgs),
    /// Discover Kotlin source and script files with typed workspace evidence.
    WorkspaceFiles(AgentWorkspaceFilesArgs),
    /// Refresh compiler-backed graph facts or query persisted native topology.
    Graph(AgentNativeGraphArgs),
    /// Answer one bounded repository question from persisted compiler evidence.
    Repository(AgentRepositoryArgs),
    /// Query and resolve a symbol identity.
    Symbol(AgentSymbolArgs),
    /// Find bounded references to one compiler-anchored declaration.
    References(AgentReferencesArgs),
    /// Find bounded incoming callers of one compiler-anchored function.
    Callers(AgentCallsArgs),
    /// Find bounded outgoing callees of one compiler-anchored function.
    Callees(AgentCallsArgs),
    /// Find bounded implementations of one compiler-anchored type.
    Implementations(AgentImplementationsArgs),
    /// Navigate a bounded type hierarchy from one compiler-anchored type.
    Hierarchy(AgentHierarchyArgs),
    /// Query source-index impact for a fully-qualified symbol.
    Impact(AgentImpactArgs),
    /// Refresh touched files and run diagnostics.
    Diagnostics(AgentDiagnosticsArgs),
    /// Rename a compiler-resolved symbol by identity.
    Rename(AgentRenameArgs),
    /// Create a new Kotlin file from content.
    AddFile(AgentAddFileArgs),
    /// Add a declaration inside a file or named scope.
    AddDeclaration(AgentScopedMutationArgs),
    /// Add implementation content inside a file or named scope.
    AddImplementation(AgentScopedMutationArgs),
    /// Add a statement inside a named executable scope.
    AddStatement(AgentStatementMutationArgs),
    /// Replace a named declaration by symbol identity.
    ReplaceDeclaration(AgentReplaceDeclarationArgs),
}

#[derive(Debug, Args, Clone, Default)]
pub struct AgentRuntimeArgs {
    /// Absolute workspace root for semantic commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct AgentWorkspaceLeaseId(String);

impl AgentWorkspaceLeaseId {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for AgentWorkspaceLeaseId {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.is_empty()
            || value.trim() != value
            || value.chars().any(char::is_whitespace)
            || value.chars().any(char::is_control)
        {
            return Err("workspace lease ids must be non-blank opaque tokens".to_string());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone)]
pub(crate) struct AgentLeaseAcquireArgs {
    pub(crate) workspace_root: PathBuf,
    pub(crate) wait_timeout_ms: u64,
}

#[derive(Debug, Clone)]
pub(crate) struct AgentLeaseAccessArgs {
    pub(crate) lease_id: AgentWorkspaceLeaseId,
    pub(crate) workspace_root: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct AgentVerifyArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub view: AgentVerifyViewArgs,
}

#[derive(Debug, Args, Clone)]
#[command(after_help = "Examples:\n  kast agent graph --workspace-root \"$PWD\" --operation summary\n  kast agent graph --workspace-root \"$PWD\" --operation nodes --limit 50\n  kast agent graph --workspace-root \"$PWD\" --operation refresh --file-path src/main/kotlin/App.kt")]
pub struct AgentNativeGraphArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Read a specific source-index database instead of the workspace default.
    #[arg(long)]
    pub database: Option<PathBuf>,
    /// Canonical graph or SQL-derived quotient to query.
    #[arg(long, value_enum)]
    pub scope: Option<NativeGraphScope>,
    /// Native graph operation to execute.
    #[arg(long, value_enum, default_value_t = NativeGraphOperation::Summary)]
    pub operation: NativeGraphOperation,
    /// Kotlin file to refresh through the compiler-backed graph. Repeat for multiple files.
    #[arg(long = "file-path")]
    pub file_paths: Vec<String>,
    /// Removed Kotlin file to delete from the persisted graph. Repeat for multiple files.
    #[arg(long = "removed-file-path")]
    pub removed_file_paths: Vec<String>,
    /// Add files owned by a model-proven Gradle module. Repeat to select multiple modules.
    #[arg(long = "module")]
    pub modules: Vec<WorkspaceModuleSelector>,
    /// Add files from a model-proven Gradle source set. Repeat to select multiple source sets.
    #[arg(long = "source-set")]
    pub source_sets: Vec<WorkspaceSourceSetName>,
    /// Remove persisted graph files outside the selected refresh scope.
    #[arg(long)]
    pub exclusive: bool,
    /// Canonical symbol key used by the neighbors operation.
    #[arg(long)]
    pub symbol: Option<String>,
    /// Fail closed unless the source-index generation matches this value.
    #[arg(long)]
    pub generation: Option<u64>,
    /// Last numeric symbol id returned by a preceding nodes query.
    #[arg(long)]
    pub after_id: Option<u64>,
    /// Maximum symbols returned by generation-pinned keyset enumeration.
    #[arg(long, value_parser = clap::value_parser!(u16).range(1..=500))]
    pub limit: Option<u16>,
    /// Resolution used by deterministic weighted Leiden clustering.
    #[arg(long)]
    pub resolution: Option<f64>,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NativeGraphScope {
    Symbol,
    File,
    Package,
    Module,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NativeGraphOperation {
    Refresh,
    Summary,
    Nodes,
    Neighbors,
    Topology,
    Communities,
}

#[derive(Debug, Args, Clone)]
#[command(
    after_help = "Examples:\n  kast agent repository --question \"Resolve SemanticGraphSha256.parse exactly.\" --intent resolve\n  kast agent repository --question \"Show callers of parse.\" --intent incoming-impact --relation calls --max-depth 2\n  kast agent repository --question \"Which modules form call cycles?\" --intent architecture --projection runtime-calls --metric scc --verbose"
)]
pub struct AgentRepositoryArgs {
    /// Absolute workspace root for the persisted repository index.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Repository question or regex pattern selected by --query-syntax.
    #[arg(long, value_parser = parse_non_blank_repository_value)]
    pub question: String,
    /// Interpret --question as natural language or a Rust regex.
    #[arg(long, value_enum, default_value_t = AgentRepositoryQuerySyntax::NaturalLanguage)]
    pub query_syntax: AgentRepositoryQuerySyntax,
    /// Bounded repository operation selected for this question.
    #[arg(long, value_enum)]
    pub intent: AgentRepositoryIntent,
    /// Exact canonical identity returned by a preceding repository result.
    #[arg(long, value_parser = parse_non_blank_repository_value)]
    pub canonical_key: Option<String>,
    /// Version-1 retrieval-only label index at a workspace-relative path.
    #[arg(long)]
    pub label_index: Option<AgentRepositoryLabelIndexPath>,
    /// Language scope. Repository intelligence currently supports Kotlin.
    #[arg(long, value_enum, default_value_t = AgentRepositoryLanguage::Kotlin)]
    pub language: AgentRepositoryLanguage,
    /// Gradle module name used to constrain compiler evidence.
    #[arg(long, value_parser = parse_non_blank_repository_value)]
    pub module: Option<String>,
    /// Gradle source-set name used to constrain compiler evidence.
    #[arg(long, value_parser = parse_non_blank_repository_value)]
    pub source_set: Option<String>,
    /// Allowed compiler relation. Repeat or comma-separate values.
    #[arg(long = "relation", value_enum, value_delimiter = ',')]
    pub relations: Vec<AgentRepositoryRelation>,
    /// Relationship traversal direction.
    #[arg(long, value_enum)]
    pub direction: Option<AgentRepositoryDirection>,
    /// Scope-specific traversal ceiling, bounded by --depth.
    #[arg(long, value_parser = clap::value_parser!(u8).range(0..=6))]
    pub max_depth: Option<u8>,
    /// Architecture projection used to derive findings.
    #[arg(long, value_enum)]
    pub projection: Option<AgentRepositoryProjection>,
    /// Architecture metric used to rank findings.
    #[arg(long, value_enum)]
    pub metric: Option<AgentRepositoryMetric>,
    /// Repository-context source kind. Repeat or comma-separate values.
    #[arg(long = "source", value_enum, value_delimiter = ',')]
    pub sources: Vec<AgentRepositorySource>,
    /// Maximum traversal depth.
    #[arg(long, default_value_t = 2, value_parser = clap::value_parser!(u8).range(0..=6))]
    pub depth: u8,
    /// Maximum result records; compact views request at most ten.
    #[arg(long, default_value_t = 10, value_parser = clap::value_parser!(u16).range(1..=500))]
    pub results: u16,
    /// Maximum source occurrences returned per relationship.
    #[arg(long, default_value_t = 2, value_parser = clap::value_parser!(u8).range(1..=50))]
    pub evidence: u8,
    /// Opaque query-bound continuation from a preceding truncated traversal.
    #[arg(long)]
    pub continuation: Option<AgentRepositoryContinuation>,
    /// Opaque query-bound evidence continuation from a preceding relationship.
    #[arg(long = "evidence-continuation")]
    pub evidence_continuation: Option<AgentRepositoryContinuation>,
    #[command(flatten)]
    pub view: AgentRepositoryViewArgs,
}

fn parse_non_blank_repository_value(value: &str) -> Result<String, String> {
    if value.trim().is_empty() || value.trim() != value || value.chars().any(char::is_control) {
        return Err("repository values must be non-blank and contain no control characters".into());
    }
    Ok(value.to_string())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AgentRepositoryContinuation(String);

impl AgentRepositoryContinuation {
    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::str::FromStr for AgentRepositoryContinuation {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.is_empty()
            || value.len() > 16_384
            || !value.is_ascii()
            || value.chars().any(char::is_control)
            || value.chars().any(char::is_whitespace)
        {
            return Err("repository continuations must be bounded opaque ASCII tokens".into());
        }
        Ok(Self(value.to_string()))
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AgentRepositoryIntent {
    Resolve,
    Path,
    IncomingImpact,
    OutgoingImpact,
    Architecture,
    ContextRelationship,
}

#[derive(Debug, Clone, Copy, Default, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AgentRepositoryQuerySyntax {
    #[default]
    NaturalLanguage,
    Regex,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AgentRepositoryLanguage {
    Kotlin,
}

impl std::fmt::Display for AgentRepositoryLanguage {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("kotlin")
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AgentRepositoryRelation {
    Calls,
    CaseOf,
    Contains,
    Delegates,
    ExpectActual,
    Implements,
    Inherits,
    Method,
    Overrides,
    References,
    SealedMember,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AgentRepositoryDirection {
    Incoming,
    Outgoing,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AgentRepositoryProjection {
    RuntimeCalls,
    SymbolReferences,
    TypeDependencies,
    InterfaceImplementation,
    ModuleDependencies,
    ContainmentOwnership,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum AgentRepositoryMetric {
    Scc,
    Communities,
    Bridges,
    PublicApiConsumers,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AgentRepositorySource {
    Markdown,
    Gradle,
    Schema,
    Workflow,
    Rust,
}
