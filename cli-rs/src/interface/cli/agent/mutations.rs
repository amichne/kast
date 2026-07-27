#[derive(Debug, Args, Clone)]
pub struct AgentImpactArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Maximum source-impact traversal depth.
    #[arg(long, default_value_t)]
    pub depth: AgentImpactDepth,
    /// Maximum source-index impact nodes to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding impact page.
    #[arg(long)]
    pub page_token: Option<AgentImpactPageToken>,
    #[command(flatten)]
    pub view: AgentImpactViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentDiagnosticsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Absolute or workspace-root-relative Kotlin file to analyze. Repeat for multiple files.
    #[arg(long = "file-path", required = true)]
    pub file_paths: Vec<String>,
    #[arg(long)]
    pub skip_refresh: bool,
    /// Maximum diagnostics for detailed views; compact output is capped at eight records.
    #[arg(long, default_value_t = 500, value_parser = clap::value_parser!(u32).range(1..=500))]
    pub limit: u32,
    /// Opaque continuation token from a preceding diagnostics result.
    #[arg(long)]
    pub page_token: Option<String>,
    #[command(flatten)]
    pub view: AgentDiagnosticsViewArgs,
}

#[derive(Debug, Args, Clone, Default)]
pub struct AgentMutationApplyArgs {
    /// Apply the mutation. Without this flag, Kast only reports the planned request.
    #[arg(long)]
    pub apply: bool,
    /// Stable caller-owned key used to retry and recover this applied mutation.
    #[arg(long)]
    pub idempotency_key: Option<String>,
    #[command(flatten)]
    pub view: AgentMutationViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRenameArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Existing declaration identity to rename.
    #[arg(
        long,
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub symbol: Option<String>,
    /// Opaque exact selector returned by compiler-backed symbol resolution.
    #[arg(long = "selector-handle", conflicts_with = "symbol")]
    pub selector_handle: Option<AgentSelectorHandle>,
    #[arg(long)]
    pub new_name: String,
    #[arg(long, value_enum, conflicts_with = "selector_handle")]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long, conflicts_with = "selector_handle")]
    pub file_hint: Option<String>,
    #[arg(long, conflicts_with = "selector_handle")]
    pub containing_type: Option<String>,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentAddFileArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Absolute or workspace-root-relative path of the Kotlin file to create.
    #[arg(long)]
    pub file_path: String,
    /// File containing the complete content to write.
    #[arg(long)]
    pub content_file: PathBuf,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentScopedMutationArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Named declaration scope that receives the content.
    #[arg(long)]
    pub inside_scope: Option<String>,
    /// Absolute or workspace-root-relative file scope that receives the content.
    #[arg(long)]
    pub inside_file: Option<String>,
    /// Placement anchor inside the selected scope.
    #[arg(long)]
    pub at: Option<AgentPlacementAnchor>,
    /// Insert after this named symbol.
    #[arg(long)]
    pub after_symbol: Option<String>,
    /// Insert before this named symbol.
    #[arg(long)]
    pub before_symbol: Option<String>,
    /// File containing the declaration or implementation content.
    #[arg(long)]
    pub content_file: PathBuf,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentStatementMutationArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Named function or accessor scope that receives the statement.
    #[arg(long)]
    pub inside_scope: String,
    /// Placement anchor inside the selected executable body.
    #[arg(long)]
    pub at: AgentStatementAnchor,
    /// File containing the statement content.
    #[arg(long)]
    pub content_file: PathBuf,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentReplaceDeclarationArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Existing declaration identity to replace.
    #[arg(
        long,
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub symbol: Option<String>,
    /// Opaque exact selector returned by compiler-backed symbol resolution.
    #[arg(long = "selector-handle", conflicts_with = "symbol")]
    pub selector_handle: Option<AgentSelectorHandle>,
    /// File containing the replacement declaration content.
    #[arg(long)]
    pub content_file: PathBuf,
    #[arg(long, value_enum, conflicts_with = "selector_handle")]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long, conflicts_with = "selector_handle")]
    pub file_hint: Option<String>,
    #[arg(long, conflicts_with = "selector_handle")]
    pub containing_type: Option<String>,
    #[command(flatten)]
    pub mutation: AgentMutationApplyArgs,
}
