#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct AgentArgs {
    #[command(subcommand)]
    pub command: AgentCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum AgentCommand {
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
    /// List catalog-backed tools for CLI-capable agent hosts.
    #[command(hide = true)]
    Tools(RemovedAgentCommandArgs),
    /// Call any catalog method with params from flags, file, or stdin.
    #[command(hide = true)]
    Call(RemovedAgentCommandArgs),
    /// Run a file-backed multi-step workflow.
    #[command(hide = true)]
    Workflow(RemovedAgentCommandArgs),
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
pub struct AgentAddFileArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Absolute path of the Kotlin file to create.
    #[arg(long)]
    pub file_path: String,
    /// File containing the complete content to write.
    #[arg(long)]
    pub content_file: PathBuf,
    /// Apply the file creation. Without this flag, Kast only reports the planned request.
    #[arg(long)]
    pub apply: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentScopedMutationArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Named declaration scope that receives the content.
    #[arg(long)]
    pub inside_scope: Option<String>,
    /// File scope that receives the content.
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
    /// Apply the mutation. Without this flag, Kast only reports the planned request.
    #[arg(long)]
    pub apply: bool,
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
    /// Apply the mutation. Without this flag, Kast only reports the planned request.
    #[arg(long)]
    pub apply: bool,
}

#[derive(Debug, Args, Clone)]
pub struct AgentReplaceDeclarationArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Existing declaration identity to replace.
    #[arg(long)]
    pub symbol: String,
    /// File containing the replacement declaration content.
    #[arg(long)]
    pub content_file: PathBuf,
    #[arg(long, value_enum)]
    pub kind: Option<AgentSymbolKind>,
    #[arg(long)]
    pub file_hint: Option<String>,
    #[arg(long)]
    pub containing_type: Option<String>,
    /// Apply the replacement. Without this flag, Kast only reports the planned request.
    #[arg(long)]
    pub apply: bool,
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
