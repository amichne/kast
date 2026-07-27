#[derive(Debug, Args, Clone)]
pub struct AgentSymbolArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Symbol query text. Use this for lookup; mutating commands use --symbol <fq-name>.
    #[arg(long)]
    pub query: String,
    /// Exact identity lookup by default; use discovery for fuzzy candidates.
    #[arg(long, value_enum, default_value_t)]
    pub mode: AgentSymbolMode,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long)]
    pub containing_type: Option<String>,
    /// Maximum discovery candidates.
    #[arg(long, default_value_t = 10)]
    pub limit: u32,
    #[command(flatten)]
    pub view: AgentSymbolViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentExactSymbolSelectorArgs {
    /// Fully-qualified compiler symbol identity.
    #[arg(long)]
    pub symbol: CanonicalSymbolName,
    /// Absolute or workspace-root-relative declaration file returned by exact lookup.
    #[arg(long = "declaration-file")]
    pub declaration_file: WorkspaceDeclarationFile,
    /// Non-negative declaration start offset returned by exact lookup.
    #[arg(long = "declaration-start-offset")]
    pub declaration_start_offset: DeclarationStartOffset,
    /// Optional hard assertion for the declaration kind.
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    /// Optional hard assertion for the containing type.
    #[arg(long = "containing-type")]
    pub containing_type: Option<CanonicalSymbolName>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentReusableSymbolSelectorArgs {
    /// Fully-qualified compiler symbol identity for an explicit selector.
    #[arg(
        long,
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub symbol: Option<CanonicalSymbolName>,
    /// Declaration file returned by exact lookup for an explicit selector.
    #[arg(
        long = "declaration-file",
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub declaration_file: Option<WorkspaceDeclarationFile>,
    /// Declaration start offset returned by exact lookup for an explicit selector.
    #[arg(
        long = "declaration-start-offset",
        required_unless_present = "selector_handle",
        conflicts_with = "selector_handle"
    )]
    pub declaration_start_offset: Option<DeclarationStartOffset>,
    /// Optional hard assertion for the declaration kind.
    #[arg(long, value_enum, conflicts_with = "selector_handle")]
    pub kind: Option<AgentSymbolKind>,
    /// Optional hard assertion for the containing type.
    #[arg(long = "containing-type", conflicts_with = "selector_handle")]
    pub containing_type: Option<CanonicalSymbolName>,
    /// Opaque exact selector returned by compiler-backed symbol resolution.
    #[arg(long = "selector-handle")]
    pub selector_handle: Option<AgentSelectorHandle>,
}

#[derive(Debug, Clone)]
pub(crate) enum AgentReusableSymbolSelector {
    Explicit(AgentExactSymbolSelectorArgs),
    Handle(AgentSelectorHandle),
}

impl AgentReusableSymbolSelectorArgs {
    pub(crate) fn into_selector(self) -> Result<AgentReusableSymbolSelector, String> {
        match (
            self.symbol,
            self.declaration_file,
            self.declaration_start_offset,
            self.kind,
            self.containing_type,
            self.selector_handle,
        ) {
            (None, None, None, None, None, Some(handle)) => {
                Ok(AgentReusableSymbolSelector::Handle(handle))
            }
            (
                Some(symbol),
                Some(declaration_file),
                Some(declaration_start_offset),
                kind,
                containing_type,
                None,
            ) => Ok(AgentReusableSymbolSelector::Explicit(
                AgentExactSymbolSelectorArgs {
                    symbol,
                    declaration_file,
                    declaration_start_offset,
                    kind,
                    containing_type,
                },
            )),
            _ => Err(
                "provide either --selector-handle or the complete explicit declaration selector"
                    .to_string(),
            ),
        }
    }
}

#[derive(Debug, Args, Clone)]
pub struct AgentReferencesArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Include the selected declaration in reference evidence.
    #[arg(long)]
    pub include_declaration: bool,
    /// Maximum relationship records to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding references page.
    #[arg(long)]
    pub page_token: Option<AgentRelationPageToken>,
    #[command(flatten)]
    pub view: AgentRelationViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentCallsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Maximum call traversal depth.
    #[arg(long, default_value_t)]
    pub depth: AgentRelationDepth,
    /// Maximum relationship records to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding page.
    #[arg(long)]
    pub page_token: Option<AgentRelationPageToken>,
    #[command(flatten)]
    pub view: AgentRelationViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentImplementationsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Maximum relationship records to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding implementations page.
    #[arg(long)]
    pub page_token: Option<AgentRelationPageToken>,
    #[command(flatten)]
    pub view: AgentRelationViewArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentHierarchyArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[command(flatten)]
    pub selector: AgentReusableSymbolSelectorArgs,
    /// Type hierarchy direction.
    #[arg(long, value_enum)]
    pub direction: AgentHierarchyDirection,
    /// Maximum type traversal depth.
    #[arg(long, default_value_t)]
    pub depth: AgentRelationDepth,
    /// Maximum relationship records to return.
    #[arg(long, default_value_t)]
    pub limit: AgentRelationLimit,
    /// Opaque query-bound token from the preceding hierarchy page.
    #[arg(long)]
    pub page_token: Option<AgentRelationPageToken>,
    #[command(flatten)]
    pub view: AgentRelationViewArgs,
}
