use clap::{Args, Parser, Subcommand, ValueEnum};
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
    #[command(subcommand)]
    pub command: Option<Command>,
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
    /// Write and inspect Kast configuration.
    Config {
        #[command(subcommand)]
        command: ConfigCommand,
    },
    /// Start the standalone JVM backend for a workspace.
    Daemon {
        #[command(subcommand)]
        command: DaemonCommand,
    },
    /// Install or remove JVM backend components.
    Backend {
        #[command(subcommand)]
        command: BackendCommand,
    },
    /// Send a raw JSON-RPC request to the workspace daemon.
    Rpc(RpcArgs),
    /// Start or warm the workspace daemon.
    Up(RuntimeArgs),
    /// Check what backends are running.
    Status(RuntimeArgs),
    /// Stop the workspace daemon.
    Stop(RuntimeArgs),
    /// Print the advertised capabilities for the workspace backend.
    Capabilities(RuntimeArgs),
    /// Open the interactive symbol-walking demo backed by source-index.db.
    Demo(DemoArgs),
    /// Query source-index metrics directly from SQLite.
    Metrics {
        #[command(subcommand)]
        command: MetricsCommand,
    },
    /// Install a portable archive or packaged resources.
    Install(InstallArgs),
    /// Report the recorded global Kast install state.
    Info,
    /// Verify the global Kast install is still healthy.
    Doctor,
    /// Remove config-managed files or packaged resources.
    Uninstall(UninstallArgs),
    /// Verify the installed Copilot extension version matches this CLI.
    VerifyExtension,
}

#[derive(Debug, Subcommand)]
pub enum ConfigCommand {
    /// Write a default Kast config file.
    Init,
}

#[derive(Debug, Subcommand)]
pub enum DaemonCommand {
    /// Launch the standalone JVM backend in the foreground.
    Start(DaemonStartArgs),
}

#[derive(Debug, Subcommand, Clone)]
pub enum BackendCommand {
    /// Install one backend component from a release asset or local archive.
    Install(BackendInstallArgs),
    /// Remove one backend component managed by kast.
    Uninstall(BackendUninstallArgs),
}

#[derive(Debug, Args, Clone)]
pub struct BackendInstallArgs {
    /// Backend component to install.
    #[arg(value_enum)]
    pub backend: BackendComponent,
    /// Local backend zip archive. When omitted, kast downloads the release asset.
    #[arg(long)]
    pub archive: Option<PathBuf>,
    /// Release tag or version. Defaults to this CLI version.
    #[arg(long)]
    pub version: Option<String>,
    /// Release directory URL containing backend zip, SHA256SUMS, and build-provenance.json.
    /// Defaults to the matching GitHub release.
    #[arg(long)]
    pub base_url: Option<String>,
    /// Replace an existing installed backend version.
    #[arg(long, num_args = 0..=1, default_missing_value = "true")]
    pub yes: Option<bool>,
}

#[derive(Debug, Args, Clone)]
pub struct BackendUninstallArgs {
    /// Backend component to remove.
    #[arg(value_enum)]
    pub backend: BackendComponent,
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
    /// Open an interactive metrics graph, or print it as JSON when stdout is not a TTY.
    Graph(MetricsGraphArgs),
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
pub struct MetricsGraphArgs {
    #[command(flatten)]
    pub scope: MetricsScopeArgs,
    /// Fully-qualified focal symbol name.
    pub symbol: String,
    /// Maximum reverse-reference depth.
    #[arg(long, default_value_t = 3)]
    pub depth: usize,
    /// Print JSON instead of entering the terminal UI.
    #[arg(long)]
    pub json: bool,
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
    /// Maximum rows per symbol-walk pane.
    #[arg(long, default_value_t = 30)]
    pub limit: usize,
    /// Print a deterministic JSON snapshot instead of entering the TUI.
    #[arg(long)]
    pub json: bool,
    /// Demo visualization to run.
    #[arg(long, value_enum, default_value = "symbol")]
    pub view: DemoView,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum DemoView {
    /// Existing source-index-backed symbol walk.
    Symbol,
    /// Spatial structural tree over source-index declarations.
    Spatial,
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
    #[arg(long = "backend", visible_alias = "backend-name", value_enum)]
    pub backend_name: Option<BackendName>,
}

#[derive(Debug, Args, Clone)]
pub struct RuntimeArgs {
    /// Absolute workspace root for daemon lifecycle and RPC commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Pin the command to a specific backend.
    #[arg(long = "backend", visible_alias = "backend-name", value_enum)]
    pub backend_name: Option<BackendName>,
    /// IntelliJ IDEA Community installation home for the headless backend.
    #[arg(long)]
    pub idea_home: Option<PathBuf>,
    /// Maximum time to wait for a ready daemon when a command needs one.
    #[arg(long, default_value_t = 60_000)]
    pub wait_timeout_ms: u64,
    /// Allow up to return while the daemon is servable in INDEXING.
    #[arg(long, num_args = 0..=1, default_missing_value = "true")]
    pub accept_indexing: Option<bool>,
    /// Fail instead of auto-starting a standalone daemon.
    #[arg(long, num_args = 0..=1, default_missing_value = "true")]
    pub no_auto_start: Option<bool>,
    /// Unix-domain socket path for the backend to listen on when auto-started.
    #[arg(long)]
    pub socket_path: Option<PathBuf>,
    /// Source module name passed to the backend when auto-started.
    #[arg(long)]
    pub module_name: Option<String>,
    /// Comma-separated source root paths passed to the backend when auto-started.
    #[arg(long)]
    pub source_roots: Option<String>,
    /// Comma-separated classpath JAR paths passed to the backend when auto-started.
    #[arg(long)]
    pub classpath: Option<String>,
    /// Request timeout in milliseconds passed to the backend when auto-started.
    #[arg(long)]
    pub request_timeout_ms: Option<u64>,
    /// Maximum results passed to the backend when auto-started.
    #[arg(long)]
    pub max_results: Option<u32>,
    /// Maximum concurrent requests passed to the backend when auto-started.
    #[arg(long)]
    pub max_concurrent_requests: Option<u32>,
    /// Enable profiling for an auto-started daemon.
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
pub struct DaemonStartArgs {
    /// Absolute workspace root for daemon lifecycle and RPC commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Backend runtime to launch. Defaults to standalone.
    #[arg(long = "backend", visible_alias = "backend-name", value_enum)]
    pub backend_name: Option<BackendName>,
    /// Override the directory containing backend runtime classpath.txt.
    #[arg(long)]
    pub runtime_libs_dir: Option<PathBuf>,
    /// IntelliJ IDEA Community installation home for the headless backend.
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
pub struct InstallArgs {
    #[command(subcommand)]
    pub command: Option<InstallCommand>,
    /// Absolute path to a portable Kast zip archive to install.
    #[arg(long)]
    pub archive: Option<PathBuf>,
    /// Instance name for the installed build.
    #[arg(long)]
    pub instance: Option<String>,
    /// Root directory for instances.
    #[arg(long)]
    pub instances_root: Option<PathBuf>,
    /// Directory for launcher scripts.
    #[arg(long)]
    pub bin_dir: Option<PathBuf>,
}

#[derive(Debug, Subcommand, Clone)]
pub enum InstallCommand {
    /// Install the packaged kast skill into the current workspace.
    Skill(ResourceInstallArgs),
    /// Install the packaged Copilot agents, hooks, and extension.
    CopilotExtension(ResourceInstallArgs),
    /// Download the Homebrew-managed IntelliJ plugin cask.
    #[command(alias = "developer-plugin")]
    IntellijPlugin(IntellijPluginInstallArgs),
}

#[derive(Debug, Args, Clone)]
pub struct IntellijPluginInstallArgs {
    /// JetBrains config root containing IDE profile directories when linking profiles.
    #[arg(long)]
    pub jetbrains_config_root: Option<PathBuf>,
    /// Link the Homebrew cask into local JetBrains IDE profiles instead of downloading the zip.
    #[arg(long)]
    pub link_jetbrains_profiles: bool,
    /// Directory for the downloaded IntelliJ plugin zip. Defaults to ~/Downloads.
    #[arg(long)]
    pub download_dir: Option<PathBuf>,
    /// Override the cask token. Defaults to <kast formula tap>/kast-plugin.
    #[arg(long)]
    pub cask_token: Option<String>,
    /// Print the planned Homebrew command without running it.
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
    /// Deprecated alias for --name.
    #[arg(long)]
    pub link_name: Option<String>,
    /// Overwrite existing managed resources.
    #[arg(long, num_args = 0..=1, default_missing_value = "true")]
    pub yes: Option<bool>,
}

#[derive(Debug, Args, Clone)]
pub struct UninstallArgs {
    #[command(subcommand)]
    pub command: Option<UninstallCommand>,
}

#[derive(Debug, Subcommand, Clone)]
pub enum UninstallCommand {
    /// Remove packaged Copilot agents, hooks, and extension from the current workspace.
    CopilotExtension(ResourceInstallArgs),
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum BackendName {
    Intellij,
    Headless,
    Standalone,
}

impl BackendName {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Intellij => "intellij",
            Self::Headless => "headless",
            Self::Standalone => "standalone",
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum BackendComponent {
    Standalone,
    Headless,
}

impl BackendComponent {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Standalone => "standalone",
            Self::Headless => "headless",
        }
    }
}

pub fn version() -> &'static str {
    option_env!("KAST_VERSION").unwrap_or(env!("CARGO_PKG_VERSION"))
}

pub fn print_topic_help(topic: &[String]) -> crate::error::Result<()> {
    let joined = topic.join(" ");
    println!("Kast CLI {}", version());
    println!();
    println!("Help topic: {joined}");
    println!("Run `kast --help` for the full command tree.");
    Ok(())
}
