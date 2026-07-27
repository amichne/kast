#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("repository_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentRepositoryViewArgs {
    /// Preserve the complete canonical repository result.
    #[arg(long)]
    pub verbose: bool,
    /// Preserve complete evidence needed to explain the answer.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected repository result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentRepositoryField>,
    /// Return repository cardinalities without result records.
    #[arg(long)]
    pub count: bool,
}

impl AgentRepositoryViewArgs {
    pub(crate) fn detailed(&self) -> bool {
        self.verbose || self.explain
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentRepositoryField {
    Summary,
    Coverage,
    Identities,
    Relationships,
    Paths,
    Findings,
    Context,
    Continuation,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("verify_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentVerifyViewArgs {
    /// Preserve the complete validated command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed evidence used to explain the result.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected verification fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentVerifyField>,
    /// Return verification counts without capability inventories.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentVerifyField {
    Health,
    Runtime,
    Capabilities,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("workspace_files_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentWorkspaceFilesViewArgs {
    /// Preserve the complete validated workspace-file evidence.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed classification and coverage evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected workspace-file fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentWorkspaceFilesField>,
    /// Return typed cardinalities without file records.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentWorkspaceFilesField {
    Path,
    Module,
    SourceSet,
    Kind,
    Package,
    Index,
    Drift,
    Dirty,
    Evidence,
}

impl AgentWorkspaceFilesField {
    pub(crate) fn canonical(self) -> &'static str {
        match self {
            Self::Path => "path",
            Self::Module => "module",
            Self::SourceSet => "source-set",
            Self::Kind => "kind",
            Self::Package => "package",
            Self::Index => "index",
            Self::Drift => "drift",
            Self::Dirty => "dirty",
            Self::Evidence => "evidence",
        }
    }
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("symbol_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentSymbolViewArgs {
    /// Preserve the complete validated command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include ranking, member, and next-request evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected symbol result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentSymbolField>,
    /// Return only result, candidate, and relationship counts.
    #[arg(long)]
    pub count: bool,
}

impl AgentSymbolViewArgs {
    pub fn detailed(&self) -> bool {
        self.verbose || self.explain
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentSymbolField {
    Identity,
    SelectorHandle,
    Location,
    Mode,
    Outcome,
    Source,
    Ambiguity,
    Relationships,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("impact_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentImpactViewArgs {
    /// Preserve the complete validated metrics command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed source-index impact evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected impact result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentImpactField>,
    /// Return impact cardinality without impact nodes.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentImpactField {
    Query,
    Summary,
    Nodes,
    Confidence,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("diagnostics_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentDiagnosticsViewArgs {
    /// Preserve the complete validated command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed diagnostic step evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected diagnostics result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentDiagnosticsField>,
    /// Return semantic and diagnostic counts without diagnostic records.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentDiagnosticsField {
    Analysis,
    Diagnostics,
    SeverityCounts,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("mutation_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentMutationViewArgs {
    /// Preserve the complete validated command envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed mutation lifecycle evidence.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected mutation result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentMutationField>,
    /// Return mutation state and aggregate counts only.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentMutationField {
    Outcome,
    Deduplicated,
    Edits,
    Files,
    Diagnostics,
}

#[derive(Debug, Args, Clone, Default)]
#[command(group(
    clap::ArgGroup::new("relation_result_view")
        .multiple(false)
        .args(["verbose", "explain", "fields", "count"])
))]
pub struct AgentRelationViewArgs {
    /// Preserve the complete validated relationship envelope.
    #[arg(long)]
    pub verbose: bool,
    /// Include detailed evidence for the bounded relationship page.
    #[arg(long)]
    pub explain: bool,
    /// Return only selected relationship result fields.
    #[arg(long, value_enum, value_delimiter = ',', num_args = 1..)]
    pub fields: Vec<AgentRelationField>,
    /// Return relationship cardinality, coverage, limitations, and page evidence only.
    #[arg(long)]
    pub count: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentRelationField {
    Subject,
    Relation,
    Records,
    Page,
    Coverage,
    Limitations,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

impl AgentSymbolKind {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Class => "class",
            Self::Interface => "interface",
            Self::Object => "object",
            Self::Function => "function",
            Self::Property => "property",
            Self::Parameter => "parameter",
            Self::Unknown => "unknown",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentPlacementAnchor {
    BodyStart,
    BodyEnd,
    FileTop,
    FileBottom,
    AfterImports,
}

impl AgentPlacementAnchor {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::BodyStart => "body-start",
            Self::BodyEnd => "body-end",
            Self::FileTop => "file-top",
            Self::FileBottom => "file-bottom",
            Self::AfterImports => "after-imports",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentStatementAnchor {
    BodyEnd,
}

impl AgentStatementAnchor {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::BodyEnd => "body-end",
        }
    }
}
