use clap::{Args, CommandFactory, Parser, Subcommand, ValueEnum};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Parser)]
#[command(
    name = "kast",
    version = version(),
    about = "Repo-local control plane for workspace daemons and Kotlin analysis requests.",
    disable_help_subcommand = true
)]
pub struct Cli {
    /// Select readable text or machine-readable JSON for operator command output.
    #[arg(long, value_enum, global = true, default_value = "human")]
    pub output: OutputFormat,
    #[command(subcommand)]
    pub command: Option<Command>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum OutputFormat {
    Human,
    Json,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Browse the command tree and scoped help pages.
    Help {
        #[arg(trailing_var_arg = true)]
        topic: Vec<String>,
    },
    /// Print the packaged CLI version.
    Version,
    /// Raw JSON-RPC transport escape hatch.
    #[command(hide = true)]
    Rpc(RpcArgs),
    /// Hidden agent-oriented pipe surface.
    #[command(hide = true)]
    Agent(AgentArgs),
    /// Validate a JSON-RPC request payload against the command catalog.
    Validate(ValidateArgs),
    /// Generate checked-in catalog-derived artifacts.
    Generate(GenerateArgs),
    /// Package distribution artifacts.
    Package(PackageArgs),
    /// Start or warm the workspace daemon.
    Up(RuntimeArgs),
    /// Check what backends are running.
    Status(RuntimeArgs),
    /// Stop the workspace daemon.
    Stop(RuntimeArgs),
    /// Stop every matching runtime and start it again.
    Restart(RuntimeArgs),
    /// Print the advertised capabilities for the workspace backend.
    Capabilities(RuntimeArgs),
    /// Run the Language Server Protocol adapter over stdio.
    Lsp(LspArgs),
    /// Open the interactive source-index demo backed by source-index.db.
    Demo(DemoArgs),
    /// Query source-index metrics directly from SQLite.
    Metrics {
        #[command(subcommand)]
        command: MetricsCommand,
    },
    /// Inspect manifest-backed Kast path resolution.
    Paths(PathsArgs),
    /// Install scoped Kast resources.
    Install(InstallArgs),
    /// Verify the global Kast install is still healthy.
    Doctor(DoctorArgs),
}

#[derive(Debug, Subcommand, Clone)]
pub enum MetricsCommand {
    /// Rank symbols by incoming references.
    FanIn(MetricsLimitArgs),
    /// Rank files by outgoing references.
    FanOut(MetricsLimitArgs),
    /// List declarations with no inbound reference rows.
    DeadCode(MetricsFilterArgs),
    /// Walk the files and symbols affected by a symbol change.
    Impact(MetricsImpactArgs),
    /// Report cross-module references.
    Coupling(MetricsScopeArgs),
    /// Search indexed symbols using persistent SQLite FTS.
    Search(MetricsSearchArgs),
}

#[derive(Debug, Args, Clone)]
pub struct MetricsScopeArgs {
    /// Absolute workspace root containing the Kast source-index cache.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Read a specific source-index.db instead of the workspace default.
    #[arg(long)]
    pub database: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
pub struct MetricsFilterArgs {
    #[command(flatten)]
    pub scope: MetricsScopeArgs,
    /// Glob applied to result file paths.
    #[arg(long)]
    pub file_glob: Option<String>,
    /// Prefix applied to result file paths.
    #[arg(long)]
    pub folder_filter: Option<String>,
}

#[derive(Debug, Args, Clone)]
pub struct MetricsLimitArgs {
    #[command(flatten)]
    pub filter: MetricsFilterArgs,
    /// Maximum rows to return.
    #[arg(long, default_value_t = 50)]
    pub limit: usize,
}

#[derive(Debug, Args, Clone)]
pub struct MetricsImpactArgs {
    #[command(flatten)]
    pub filter: MetricsFilterArgs,
    /// Fully-qualified symbol name.
    pub symbol: String,
    /// Maximum reverse-reference depth.
    #[arg(long, default_value_t = 3)]
    pub depth: usize,
}

#[derive(Debug, Args, Clone)]
pub struct MetricsSearchArgs {
    #[command(flatten)]
    pub scope: MetricsScopeArgs,
    /// Symbol query.
    pub query: String,
    /// Maximum symbols to return.
    #[arg(long, default_value_t = 25)]
    pub limit: usize,
}

#[derive(Debug, Args, Clone)]
pub struct DemoArgs {
    /// Absolute workspace root containing the Kast source-index cache.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Read a specific source-index.db instead of the workspace default.
    #[arg(long)]
    pub database: Option<PathBuf>,
    /// Fully-qualified symbol to open first.
    #[arg(long)]
    pub symbol: Option<String>,
    /// Initial symbol search query.
    #[arg(long)]
    pub query: Option<String>,
    /// Maximum rows per demo pane.
    #[arg(long, default_value_t = 30)]
    pub limit: usize,
    /// Print a deterministic JSON snapshot instead of entering the TUI.
    #[arg(long)]
    pub json: bool,
    /// Demo visualization to run.
    #[arg(long, value_enum, default_value = "compare")]
    pub view: DemoView,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum DemoView {
    /// Dual-pane comparison between lexical index candidates and Kast semantic matches.
    Compare,
    /// Existing source-index-backed symbol walk.
    Symbol,
}

#[derive(Debug, Args, Clone)]
pub struct RpcArgs {
    /// Raw JSON-RPC request string.
    pub request: Option<String>,
    /// Absolute JSON request file for operations with richer payloads.
    #[arg(long)]
    pub request_file: Option<PathBuf>,
    /// Absolute workspace root for daemon lifecycle and RPC commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Pin the command to a specific backend.
    #[arg(long = "backend", value_enum)]
    pub backend_name: Option<BackendName>,
}

#[derive(Debug, Args, Clone)]
pub struct AgentArgs {
    #[command(subcommand)]
    pub command: AgentCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum AgentCommand {
    /// Call any catalog method with params from flags, file, or stdin.
    Call(AgentCallArgs),
    /// Run the health RPC.
    Health(AgentRuntimeArgs),
    /// Read detailed backend runtime state.
    RuntimeStatus(AgentRuntimeArgs),
    /// Read advertised backend capabilities.
    Capabilities(AgentRuntimeArgs),
    /// Gather structural generation context for a Kotlin file.
    Scaffold(AgentScaffoldArgs),
    /// Rank candidate Kotlin declarations for a simple symbol name.
    Discover(AgentDiscoverArgs),
    /// Resolve a Kotlin symbol by name.
    Resolve(AgentSymbolResolveArgs),
    /// Find usages of a Kotlin symbol by name.
    References(AgentSymbolReferencesArgs),
    /// Expand a Kotlin call hierarchy by symbol name.
    Callers(AgentSymbolCallersArgs),
    /// Resolve the symbol at a file offset.
    RawResolve(AgentRawResolveArgs),
    /// Find references for the symbol at a file offset.
    RawReferences(AgentRawReferencesArgs),
    /// Expand call hierarchy from a file offset.
    RawCallHierarchy(AgentRawCallHierarchyArgs),
    /// Expand type hierarchy from a file offset.
    RawTypeHierarchy(AgentRawTypeHierarchyArgs),
    /// Find an insertion point near a file offset.
    RawSemanticInsertionPoint(AgentRawSemanticInsertionPointArgs),
    /// Read diagnostics for one or more files.
    RawDiagnostics(AgentFilePathsArgs),
    /// Rename the symbol at a file offset.
    RawRename(AgentRawRenameArgs),
    /// Optimize imports for one or more files.
    RawOptimizeImports(AgentFilePathsArgs),
    /// Refresh workspace state for optional files.
    RawWorkspaceRefresh(AgentOptionalFilePathsArgs),
    /// Read a hierarchical Kotlin file outline.
    FileOutline(AgentFileOutlineArgs),
    /// Search workspace symbols.
    WorkspaceSymbol(AgentWorkspaceSymbolArgs),
    /// Search workspace text.
    WorkspaceSearch(AgentWorkspaceSearchArgs),
    /// List workspace modules and optionally files.
    WorkspaceFiles(AgentWorkspaceFilesArgs),
    /// Find implementations from a file offset.
    RawImplementations(AgentRawImplementationsArgs),
    /// Read code actions at a file offset.
    RawCodeActions(AgentRawCodeActionsArgs),
    /// Read completions at a file offset.
    RawCompletions(AgentRawCompletionsArgs),
    /// Query source-index metrics through the RPC catalog.
    Metrics(AgentMetricsArgs),
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

#[derive(Debug, Args, Clone)]
pub struct AgentCallArgs {
    /// Catalog RPC method, such as symbol/resolve or raw/apply-edits.
    pub method: String,
    /// Params object, full JSON-RPC request, prior agent envelope, or nextRequest object.
    #[arg(long)]
    pub params: Option<String>,
    /// JSON file containing params, a full JSON-RPC request, prior envelope, or nextRequest.
    #[arg(long)]
    pub params_file: Option<PathBuf>,
    /// JSON file containing a full JSON-RPC request or pipe-compatible input object.
    #[arg(long)]
    pub request_file: Option<PathBuf>,
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
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

#[derive(Debug, Args, Clone)]
pub struct ValidateArgs {
    /// Raw JSON-RPC request string.
    pub request: Option<String>,
    /// Absolute JSON request file path.
    #[arg(long)]
    pub request_file: Option<PathBuf>,
    /// Validate all checked-in sample payloads.
    #[arg(long)]
    pub all_samples: bool,
    /// Request sample tree root. Defaults to the checked-in catalog samples.
    #[arg(long, hide = true)]
    pub samples_root: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
pub struct GenerateArgs {
    #[command(subcommand)]
    pub command: GenerateCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum GenerateCommand {
    /// Regenerate YAML, sample payloads, and JSON Schemas from commands.json.
    Contract(GenerateContractArgs),
}

#[derive(Debug, Args, Clone)]
pub struct PackageArgs {
    #[command(subcommand)]
    pub command: PackageCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum PackageCommand {
    /// Build the Ubuntu/Debian headless install bundle.
    #[command(name = "ubuntu-debian-bundle")]
    UbuntuDebianBundle(UbuntuDebianBundlePackageArgs),
}

#[derive(Debug, Args, Clone)]
pub struct UbuntuDebianBundlePackageArgs {
    /// Rust CLI zip archive containing kast at the archive root.
    #[arg(long)]
    pub cli_archive: PathBuf,
    /// Headless backend portable zip archive containing backend-headless/.
    #[arg(long)]
    pub backend_archive: PathBuf,
    /// Release tag or version for the generated bundle.
    #[arg(long)]
    pub version: String,
    /// Output tar.gz path. Defaults to dist/kast-ubuntu-debian-headless-x86_64-<version>.tar.gz.
    #[arg(long = "bundle-output")]
    pub bundle_output: Option<PathBuf>,
    /// Repository root containing scripts/install-ubuntu-debian.sh and LICENSE.
    #[arg(long, hide = true)]
    pub repo_root: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
pub struct GenerateContractArgs {
    /// Fail if generated contract artifacts are stale.
    #[arg(long)]
    pub check: bool,
    /// Command catalog path. Defaults to the checked-in commands.json.
    #[arg(long, hide = true)]
    pub catalog: Option<PathBuf>,
    /// Generated YAML path. Defaults to the checked-in commands.yaml.
    #[arg(long, hide = true)]
    pub yaml: Option<PathBuf>,
    /// Generated request sample tree. Defaults to the checked-in requests directory.
    #[arg(long, hide = true)]
    pub samples_root: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
pub struct RuntimeArgs {
    /// Absolute workspace root for daemon lifecycle and RPC commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Pin the command to a specific backend.
    #[arg(long = "backend", value_enum)]
    pub backend_name: Option<BackendName>,
    /// IDEA Community installation home for the headless backend.
    #[arg(long, hide = true)]
    pub idea_home: Option<PathBuf>,
    /// Maximum time to wait for a ready daemon when a command needs one.
    #[arg(long, default_value_t = 60_000, hide = true)]
    pub wait_timeout_ms: u64,
    /// Allow up to return while the daemon is servable in INDEXING.
    #[arg(long, num_args = 0..=1, default_missing_value = "true", hide = true)]
    pub accept_indexing: Option<bool>,
    /// Fail instead of auto-starting a headless daemon.
    #[arg(long, num_args = 0..=1, default_missing_value = "true", hide = true)]
    pub no_auto_start: Option<bool>,
    /// Unix-domain socket path for the backend to listen on when auto-started.
    #[arg(long, hide = true)]
    pub socket_path: Option<PathBuf>,
    /// Source module name passed to the backend when auto-started.
    #[arg(long, hide = true)]
    pub module_name: Option<String>,
    /// Comma-separated source root paths passed to the backend when auto-started.
    #[arg(long, hide = true)]
    pub source_roots: Option<String>,
    /// Comma-separated classpath JAR paths passed to the backend when auto-started.
    #[arg(long, hide = true)]
    pub classpath: Option<String>,
    /// Request timeout in milliseconds passed to the backend when auto-started.
    #[arg(long, hide = true)]
    pub request_timeout_ms: Option<u64>,
    /// Maximum results passed to the backend when auto-started.
    #[arg(long, hide = true)]
    pub max_results: Option<u32>,
    /// Maximum concurrent requests passed to the backend when auto-started.
    #[arg(long, hide = true)]
    pub max_concurrent_requests: Option<u32>,
    /// Enable profiling for an auto-started daemon.
    #[arg(long, hide = true)]
    pub profile: bool,
    /// Comma-separated profiling modes.
    #[arg(long, hide = true)]
    pub profile_modes: Option<String>,
    /// Profiling duration in seconds.
    #[arg(long, hide = true)]
    pub profile_duration: Option<u64>,
    /// OTLP endpoint override while profiling is enabled.
    #[arg(long, hide = true)]
    pub profile_otlp_endpoint: Option<String>,
}

#[derive(Debug, Args, Clone)]
pub struct DaemonStartArgs {
    /// Absolute workspace root for daemon lifecycle and RPC commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Backend runtime to launch. Defaults to headless.
    #[arg(long = "backend", value_enum)]
    pub backend_name: Option<BackendName>,
    /// Override the directory containing backend runtime classpath.txt.
    #[arg(long)]
    pub runtime_libs_dir: Option<PathBuf>,
    /// IDEA Community installation home for the headless backend.
    #[arg(long)]
    pub idea_home: Option<PathBuf>,
    /// Unix-domain socket path for the backend to listen on.
    #[arg(long)]
    pub socket_path: Option<PathBuf>,
    /// Source module name passed to the backend.
    #[arg(long)]
    pub module_name: Option<String>,
    /// Comma-separated source root paths passed to the backend.
    #[arg(long)]
    pub source_roots: Option<String>,
    /// Comma-separated classpath JAR paths passed to the backend.
    #[arg(long)]
    pub classpath: Option<String>,
    /// Request timeout in milliseconds passed to the backend.
    #[arg(long)]
    pub request_timeout_ms: Option<u64>,
    /// Maximum results passed to the backend.
    #[arg(long)]
    pub max_results: Option<u32>,
    /// Maximum concurrent requests passed to the backend.
    #[arg(long)]
    pub max_concurrent_requests: Option<u32>,
    /// Enable stdio transport.
    #[arg(long)]
    pub stdio: bool,
    /// Enable profiling for this daemon process.
    #[arg(long)]
    pub profile: bool,
    /// Comma-separated profiling modes.
    #[arg(long)]
    pub profile_modes: Option<String>,
    /// Profiling duration in seconds.
    #[arg(long)]
    pub profile_duration: Option<u64>,
    /// OTLP endpoint override while profiling is enabled.
    #[arg(long)]
    pub profile_otlp_endpoint: Option<String>,
}

#[derive(Debug, Args, Clone)]
pub struct LspArgs {
    /// Enable stdio transport.
    #[arg(long)]
    pub stdio: bool,
    /// Absolute workspace root for daemon lifecycle and LSP requests.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Pin LSP requests to a specific backend.
    #[arg(long = "backend", value_enum)]
    pub backend_name: Option<BackendName>,
    /// Maximum time to wait for a ready daemon when LSP needs one.
    #[arg(long, default_value_t = 60_000)]
    pub request_timeout_ms: u64,
}

impl From<RuntimeArgs> for DaemonStartArgs {
    fn from(value: RuntimeArgs) -> Self {
        Self {
            workspace_root: value.workspace_root,
            backend_name: value.backend_name,
            runtime_libs_dir: None,
            idea_home: value.idea_home,
            socket_path: value.socket_path,
            module_name: value.module_name,
            source_roots: value.source_roots,
            classpath: value.classpath,
            request_timeout_ms: value.request_timeout_ms,
            max_results: value.max_results,
            max_concurrent_requests: value.max_concurrent_requests,
            stdio: false,
            profile: value.profile,
            profile_modes: value.profile_modes,
            profile_duration: value.profile_duration,
            profile_otlp_endpoint: value.profile_otlp_endpoint,
        }
    }
}

#[derive(Debug, Args, Clone)]
pub struct PathsArgs {
    /// Absolute workspace root for workspace-local config inspection.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Show the IDEA host path view.
    #[arg(long)]
    pub idea: bool,
}

#[derive(Debug, Args, Clone)]
pub struct DoctorArgs {
    /// Repair the install manifest and managed install state. Plain doctor is read-only.
    #[arg(long)]
    pub repair: bool,
}

#[derive(Debug, Args, Clone)]
pub struct InstallArgs {
    #[command(subcommand)]
    pub command: InstallCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum InstallCommand {
    /// Activate a portable Kast install bundle from its bundled manifest.
    #[command(name = "activate-bundle")]
    ActivateBundle(ActivateBundleArgs),
    /// Install the packaged kast skill into the current workspace.
    Skill(ResourceInstallArgs),
    /// Install portable agent instruction files.
    Instructions(ResourceInstallArgs),
    /// Install the packaged Copilot LSP, instructions, agents, and extension tools.
    Copilot(CopilotInstallArgs),
    /// Install the Homebrew-managed IDEA plugin cask and link JetBrains profiles.
    Plugin(IdeaPluginInstallArgs),
    /// Install shell PATH and completion integration.
    Shell(ShellInstallArgs),
    /// Print shell completion scripts.
    Completion(CompletionArgs),
}

#[derive(Debug, Args, Clone)]
pub struct ActivateBundleArgs {
    /// Extracted bundle directory or bundle .tar.gz archive.
    #[arg(long)]
    pub source: PathBuf,
    /// Managed install root. Defaults to KAST_INSTALL_ROOT or ~/.local/share/kast.
    #[arg(long)]
    pub install_root: Option<PathBuf>,
    /// Directory for the kast shim. Defaults to ~/.local/bin.
    #[arg(long)]
    pub bin_dir: Option<PathBuf>,
    /// Kast config home. Defaults to KAST_CONFIG_HOME or ~/.config/kast.
    #[arg(long)]
    pub config_home: Option<PathBuf>,
    /// Validate the bundle and current install without changing files.
    #[arg(long)]
    pub verify_only: bool,
}

#[derive(Debug, Args, Clone)]
pub struct InstallRepairArgs {
    /// Apply the planned repairs. Without this flag, no files are changed.
    #[arg(long)]
    pub apply: bool,
    /// JetBrains config root containing IDE profile directories to audit.
    #[arg(long)]
    pub jetbrains_config_root: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
pub struct CompletionArgs {
    /// Shell to generate completion code for.
    #[arg(value_enum)]
    pub shell: ShellKind,
    /// Command name to embed in completion output. Defaults to kast.
    #[arg(long)]
    pub command_name: Option<String>,
}

#[derive(Debug, Args, Clone)]
pub struct IdeaPluginInstallArgs {
    /// JetBrains config root containing IDE profile directories when linking profiles.
    #[arg(long)]
    pub jetbrains_config_root: Option<PathBuf>,
    /// Link the Homebrew cask into local JetBrains IDE profiles.
    #[arg(long, hide = true)]
    pub link_jetbrains_profiles: bool,
    /// Override the cask token. Defaults to <kast formula tap>/kast-plugin.
    #[arg(long)]
    pub cask_token: Option<String>,
    /// Replace or refetch the plugin artifact when the installer supports it.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Print the planned Homebrew command without running it.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args, Clone)]
pub struct ShellInstallArgs {
    /// Shell to install integration for. Defaults to the current SHELL.
    #[arg(long, value_enum)]
    pub shell: Option<ShellKind>,
    /// Shell profile file to patch. Defaults to ~/.zshrc or ~/.bashrc.
    #[arg(long)]
    pub profile: Option<PathBuf>,
    /// Managed source file to write. Defaults to <KAST_CONFIG_HOME>/shell/<command>.<shell>.
    #[arg(long)]
    pub source_file: Option<PathBuf>,
    /// Command name to add completions for. Defaults to this executable's file name.
    #[arg(long)]
    pub command_name: Option<String>,
    /// Print the planned integration without writing files.
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(Debug, Args, Clone)]
pub struct ResourceInstallArgs {
    /// Target root directory.
    #[arg(long)]
    pub target_dir: Option<PathBuf>,
    /// Directory name for the installed skill. Defaults to kast.
    #[arg(long)]
    pub name: Option<String>,
    /// Overwrite existing managed resources.
    #[arg(short = 'f', long)]
    pub force: bool,
}

#[derive(Debug, Args, Clone)]
pub struct CopilotInstallArgs {
    /// Target .github directory.
    #[arg(long)]
    pub target_dir: Option<PathBuf>,
    /// Overwrite existing managed resources.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Do not add managed package paths to Git info/exclude.
    #[arg(long)]
    pub no_auto_exclude_git: bool,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum ShellKind {
    Bash,
    Zsh,
}

impl ShellKind {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Bash => "bash",
            Self::Zsh => "zsh",
        }
    }

    pub fn extension(self) -> &'static str {
        match self {
            Self::Bash => "bash",
            Self::Zsh => "zsh",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum BackendName {
    Idea,
    Headless,
}

impl BackendName {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Idea => "idea",
            Self::Headless => "headless",
        }
    }
}

pub fn version() -> &'static str {
    option_env!("KAST_VERSION").unwrap_or(env!("CARGO_PKG_VERSION"))
}

pub fn print_topic_help(topic: &[String]) -> crate::error::Result<()> {
    let mut command = Cli::command();
    let mut selected = &mut command;
    let mut traversed = Vec::new();
    for part in topic {
        traversed.push(part.as_str());
        selected = selected.find_subcommand_mut(part).ok_or_else(|| {
            crate::error::CliError::new(
                "CLI_HELP_TOPIC_NOT_FOUND",
                format!(
                    "No Kast help topic named `{}`. Run `kast --help` for the full command tree.",
                    traversed.join(" ")
                ),
            )
        })?;
    }
    selected.print_long_help()?;
    println!();
    Ok(())
}
