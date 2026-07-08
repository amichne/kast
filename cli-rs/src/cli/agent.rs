#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct AgentArgs {
    #[command(subcommand)]
    pub command: AgentCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum AgentCommand {
    /// Prepare agent resources and warm the workspace runtime.
    #[command(hide = true)]
    Up(AgentUpArgs),
    /// Verify Kast readiness for agent and semantic workflows.
    #[command(hide = true)]
    Ready(ReadyArgs),
    /// Install repo-local or portable agent resources.
    #[command(hide = true)]
    Setup(AgentSetupArgs),
    /// Run the Language Server Protocol adapter over stdio.
    Lsp(LspArgs),
    /// Verify backend health, runtime state, and capabilities.
    Verify(AgentVerifyArgs),
    /// Query and resolve a symbol, optionally gathering references and callers.
    Symbol(AgentSymbolArgs),
    /// Query source-index impact for a fully-qualified symbol.
    Impact(AgentImpactArgs),
    /// Refresh touched files and run diagnostics.
    Diagnostics(AgentDiagnosticsArgs),
    /// Rename a compiler-resolved symbol by identity.
    Rename(AgentRenameArgs),
    /// List catalog-backed tools for CLI-capable agent hosts.
    #[command(hide = true)]
    Tools(RemovedAgentCommandArgs),
    /// Call any catalog method with params from flags, file, or stdin.
    #[command(hide = true)]
    Call(RemovedAgentCommandArgs),
    /// Run a file-backed multi-step workflow.
    #[command(hide = true)]
    Workflow(RemovedAgentCommandArgs),
    /// Run the health RPC.
    #[command(hide = true)]
    Health(AgentRuntimeArgs),
    /// Read detailed backend runtime state.
    #[command(hide = true)]
    RuntimeStatus(AgentRuntimeArgs),
    /// Read advertised backend capabilities.
    #[command(hide = true)]
    Capabilities(AgentRuntimeArgs),
    /// Gather structural generation context for a Kotlin file.
    #[command(hide = true)]
    Scaffold(AgentScaffoldArgs),
    /// Rank candidate Kotlin declarations for a simple symbol name.
    #[command(hide = true)]
    Discover(AgentDiscoverArgs),
    /// Resolve a Kotlin symbol by name.
    #[command(hide = true)]
    Resolve(AgentSymbolResolveArgs),
    /// Find usages of a Kotlin symbol by name.
    #[command(hide = true)]
    References(AgentSymbolReferencesArgs),
    /// Expand a Kotlin call hierarchy by symbol name.
    #[command(hide = true)]
    Callers(AgentSymbolCallersArgs),
    /// Resolve the symbol at a file offset.
    #[command(hide = true)]
    RawResolve(AgentRawResolveArgs),
    /// Find references for the symbol at a file offset.
    #[command(hide = true)]
    RawReferences(AgentRawReferencesArgs),
    /// Expand call hierarchy from a file offset.
    #[command(hide = true)]
    RawCallHierarchy(AgentRawCallHierarchyArgs),
    /// Expand type hierarchy from a file offset.
    #[command(hide = true)]
    RawTypeHierarchy(AgentRawTypeHierarchyArgs),
    /// Find an insertion point near a file offset.
    #[command(hide = true)]
    RawSemanticInsertionPoint(AgentRawSemanticInsertionPointArgs),
    /// Read diagnostics for one or more files.
    #[command(hide = true)]
    RawDiagnostics(AgentFilePathsArgs),
    /// Rename the symbol at a file offset.
    #[command(hide = true)]
    RawRename(AgentRawRenameArgs),
    /// Optimize imports for one or more files.
    #[command(hide = true)]
    RawOptimizeImports(AgentFilePathsArgs),
    /// Refresh workspace state for optional files.
    #[command(hide = true)]
    RawWorkspaceRefresh(AgentOptionalFilePathsArgs),
    /// Read a hierarchical Kotlin file outline.
    #[command(hide = true)]
    FileOutline(AgentFileOutlineArgs),
    /// Search workspace symbols.
    #[command(hide = true)]
    WorkspaceSymbol(AgentWorkspaceSymbolArgs),
    /// Search workspace text.
    #[command(hide = true)]
    WorkspaceSearch(AgentWorkspaceSearchArgs),
    /// List workspace modules and optionally files.
    #[command(hide = true)]
    WorkspaceFiles(AgentWorkspaceFilesArgs),
    /// Find implementations from a file offset.
    #[command(hide = true)]
    RawImplementations(AgentRawImplementationsArgs),
    /// Read code actions at a file offset.
    #[command(hide = true)]
    RawCodeActions(AgentRawCodeActionsArgs),
    /// Read completions at a file offset.
    #[command(hide = true)]
    RawCompletions(AgentRawCompletionsArgs),
    /// Query source-index metrics through the RPC catalog.
    #[command(hide = true)]
    Metrics(AgentMetricsArgs),
}

#[derive(Debug, Args, Clone)]
pub struct AgentUpArgs {
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
    /// Do not add the managed skill path to Git info/exclude.
    #[arg(long)]
    pub no_auto_exclude_git: bool,
    /// Explain setup and runtime actions without writing files or starting a backend.
    #[arg(long)]
    pub dry_run: bool,
    /// Skip first-run interactive onboarding even in a smart terminal.
    #[arg(long)]
    pub no_onboard: bool,
}

#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct AgentSetupArgs {
    #[command(subcommand)]
    pub command: Option<AgentSetupCommand>,
    #[command(flatten)]
    pub guidance: AgentGuidanceSetupArgs,
}

#[derive(Debug, Args, Clone, Default)]
pub struct AgentGuidanceSetupArgs {
    /// Absolute workspace root for harness-agnostic agent resource setup.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Packaged skill target root. Defaults to configured setup, then .agents/skills.
    #[arg(long = "skill-target-dir")]
    pub skill_target_dir: Option<PathBuf>,
    /// Repository context file to patch with Kast managed guidance.
    #[arg(long = "context-file")]
    pub context_files: Vec<PathBuf>,
    /// Additional AGENTS.md or AGENTS.local.md files to patch with Kast managed guidance.
    #[arg(long = "agents-md", hide = true)]
    pub agents_md: Vec<PathBuf>,
    /// Overwrite modified Kast managed regions.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Do not add the managed skill path to Git info/exclude.
    #[arg(long)]
    pub no_auto_exclude_git: bool,
    /// Explain setup without writing files.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Subcommand, Clone)]
pub enum AgentSetupCommand {
    /// Removed legacy auto installer. Use root `kast setup`.
    #[command(hide = true)]
    Auto(AgentSetupAutoArgs),
    /// Removed legacy Copilot package installer. Use root `kast setup`.
    #[command(hide = true)]
    Copilot(CopilotInstallArgs),
    /// Install the packaged Kast skill.
    #[command(hide = true)]
    Skill(ResourceInstallArgs),
    /// Removed legacy Markdown instruction installer. Use root `kast setup`.
    #[command(hide = true)]
    Instructions(ResourceInstallArgs),
}

#[derive(Debug, Args, Clone)]
pub struct AgentSetupAutoArgs {
    /// Agent harness resource to install. Defaults to projectOpen.agentHarness, then repository detection.
    #[arg(long, value_enum)]
    pub harness: Option<AgentSetupHarness>,
    /// Target directory for the selected harness.
    #[arg(long)]
    pub target_dir: Option<PathBuf>,
    /// Overwrite existing managed resources.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Do not add managed package paths to Git info/exclude.
    #[arg(long)]
    pub no_auto_exclude_git: bool,
    /// Explain the selected harness without writing files.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AgentSetupHarness {
    Auto,
    Copilot,
    Skill,
    Instructions,
}

impl AgentSetupHarness {
    pub fn is_auto(self) -> bool {
        matches!(self, Self::Auto)
    }
}

#[derive(Debug, Args, Clone, Default)]
pub struct AgentRuntimeArgs {
    /// Absolute workspace root for daemon lifecycle and RPC commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Pin the command to a specific backend.
    #[arg(long = "backend", value_enum)]
    pub backend_name: Option<BackendName>,
}

#[derive(Debug, Args, Clone, Default)]
pub struct RemovedAgentCommandArgs {
    /// Raw stale command arguments preserved only so removed commands can emit a tombstone.
    #[arg(allow_hyphen_values = true, trailing_var_arg = true)]
    pub args: Vec<String>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentVerifyArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
}

#[derive(Debug, Args, Clone)]
pub struct AgentSymbolArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Symbol query text. Use this for lookup; mutating commands use --symbol <fq-name>.
    #[arg(long)]
    pub query: String,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long)]
    pub containing_type: Option<String>,
    #[arg(long)]
    pub references: bool,
    #[arg(long, value_enum)]
    pub callers: Option<AgentSymbolCallDirection>,
    #[arg(long, default_value_t = 3)]
    pub caller_depth: u32,
    #[arg(long, default_value_t = 10)]
    pub limit: u32,
}

#[derive(Debug, Args, Clone)]
pub struct AgentImpactArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Fully-qualified symbol name.
    #[arg(long)]
    pub symbol: String,
    #[arg(long, default_value_t = 3)]
    pub depth: u32,
    #[arg(long, default_value_t = 50)]
    pub limit: u32,
}

#[derive(Debug, Args, Clone)]
pub struct AgentDiagnosticsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long = "file-path", required = true)]
    pub file_paths: Vec<String>,
    #[arg(long)]
    pub skip_refresh: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRenameArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Existing declaration identity to rename.
    #[arg(long)]
    pub symbol: String,
    #[arg(long)]
    pub new_name: String,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long)]
    pub containing_type: Option<String>,
    /// Apply the rename. Without this flag, Kast only reports the planned request.
    #[arg(long)]
    pub apply: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentScaffoldArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub target_file: String,
    #[arg(long)]
    pub target_symbol: Option<String>,
    #[arg(long, value_enum)]
    pub mode: Option<AgentScaffoldMode>,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentDiscoverArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub symbol: String,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long)]
    pub line: Option<u32>,
    #[arg(long)]
    pub code_snippet: Option<String>,
    #[arg(long)]
    pub containing_type: Option<String>,
    #[arg(long)]
    pub include_declaration_scope: bool,
    #[arg(long)]
    pub max_results: Option<u32>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentSymbolResolveArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub symbol: String,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub containing_type: Option<String>,
    #[arg(long)]
    pub include_declaration_scope: bool,
    #[arg(long)]
    pub include_documentation: bool,
    #[arg(long)]
    pub surrounding_lines: Option<u32>,
    #[arg(long)]
    pub include_surrounding_members: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentSymbolReferencesArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub symbol: String,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub containing_type: Option<String>,
    #[arg(long)]
    pub include_declaration: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentSymbolCallersArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub symbol: String,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub containing_type: Option<String>,
    #[arg(long, value_enum)]
    pub direction: Option<AgentSymbolCallDirection>,
    #[arg(long)]
    pub depth: Option<u32>,
    #[arg(long)]
    pub max_total_calls: Option<u32>,
    #[arg(long)]
    pub max_children_per_node: Option<u32>,
    #[arg(long)]
    pub timeout_millis: Option<u32>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentPositionArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub file_path: String,
    #[arg(long)]
    pub offset: u64,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawResolveArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long)]
    pub include_declaration_scope: bool,
    #[arg(long)]
    pub include_documentation: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawReferencesArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long)]
    pub include_declaration: bool,
    #[arg(long)]
    pub include_usage_site_scope: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawCallHierarchyArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long, value_enum)]
    pub direction: AgentRawCallDirection,
    #[arg(long)]
    pub depth: Option<u32>,
    #[arg(long)]
    pub max_total_calls: Option<u32>,
    #[arg(long)]
    pub max_children_per_node: Option<u32>,
    #[arg(long)]
    pub timeout_millis: Option<u32>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawTypeHierarchyArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long, value_enum)]
    pub direction: Option<AgentRawTypeDirection>,
    #[arg(long)]
    pub depth: Option<u32>,
    #[arg(long)]
    pub max_results: Option<u32>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawSemanticInsertionPointArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long)]
    pub target: String,
}

#[derive(Debug, Args, Clone)]
pub struct AgentFilePathsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long = "file-path", required = true)]
    pub file_paths: Vec<String>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentOptionalFilePathsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long = "file-path")]
    pub file_paths: Vec<String>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawRenameArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long)]
    pub new_name: String,
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentFileOutlineArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub file_path: String,
}

#[derive(Debug, Args, Clone)]
pub struct AgentWorkspaceSymbolArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub pattern: String,
    #[arg(long, value_enum)]
    pub kind: Option<AgentRawSymbolKind>,
    #[arg(long)]
    pub max_results: Option<u32>,
    #[arg(long)]
    pub regex: bool,
    #[arg(long)]
    pub include_declaration_scope: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentWorkspaceSearchArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub pattern: String,
    #[arg(long)]
    pub regex: bool,
    #[arg(long)]
    pub max_results: Option<u32>,
    #[arg(long)]
    pub file_glob: Option<String>,
    #[arg(long)]
    pub case_sensitive: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentWorkspaceFilesArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long)]
    pub module_name: Option<String>,
    #[arg(long)]
    pub include_files: bool,
    #[arg(long)]
    pub max_files_per_module: Option<u32>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawImplementationsArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long)]
    pub max_results: Option<u32>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawCodeActionsArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long)]
    pub diagnostic_code: Option<String>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentRawCompletionsArgs {
    #[command(flatten)]
    pub position: AgentPositionArgs,
    #[arg(long)]
    pub max_results: Option<u32>,
    #[arg(long = "kind-filter")]
    pub kind_filter: Vec<String>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentMetricsArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    #[arg(long, value_enum)]
    pub metric: AgentMetric,
    #[arg(long)]
    pub limit: Option<u32>,
    #[arg(long)]
    pub symbol: Option<String>,
    #[arg(long)]
    pub depth: Option<u32>,
    #[arg(long)]
    pub file_glob: Option<String>,
    #[arg(long)]
    pub folder_filter: Option<String>,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
}

impl AgentSymbolKind {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Class => "class",
            Self::Interface => "interface",
            Self::Object => "object",
            Self::Function => "function",
            Self::Property => "property",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentScaffoldMode {
    Implement,
    Replace,
    Consolidate,
    Extract,
}

impl AgentScaffoldMode {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Implement => "implement",
            Self::Replace => "replace",
            Self::Consolidate => "consolidate",
            Self::Extract => "extract",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentSymbolCallDirection {
    Incoming,
    Outgoing,
}

impl AgentSymbolCallDirection {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Incoming => "incoming",
            Self::Outgoing => "outgoing",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentRawCallDirection {
    Incoming,
    Outgoing,
}

impl AgentRawCallDirection {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Incoming => "INCOMING",
            Self::Outgoing => "OUTGOING",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentRawTypeDirection {
    Supertypes,
    Subtypes,
    Both,
}

impl AgentRawTypeDirection {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Supertypes => "SUPERTYPES",
            Self::Subtypes => "SUBTYPES",
            Self::Both => "BOTH",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentRawSymbolKind {
    Class,
    Interface,
    Object,
    Function,
    Property,
    Parameter,
    Unknown,
}

impl AgentRawSymbolKind {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Class => "CLASS",
            Self::Interface => "INTERFACE",
            Self::Object => "OBJECT",
            Self::Function => "FUNCTION",
            Self::Property => "PROPERTY",
            Self::Parameter => "PARAMETER",
            Self::Unknown => "UNKNOWN",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum AgentMetric {
    #[value(name = "fanIn")]
    FanIn,
    #[value(name = "fanOut")]
    FanOut,
    #[value(name = "deadCode")]
    DeadCode,
    Impact,
    Coupling,
    Search,
    Graph,
}

impl AgentMetric {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::FanIn => "fanIn",
            Self::FanOut => "fanOut",
            Self::DeadCode => "deadCode",
            Self::Impact => "impact",
            Self::Coupling => "coupling",
            Self::Search => "search",
            Self::Graph => "graph",
        }
    }
}
