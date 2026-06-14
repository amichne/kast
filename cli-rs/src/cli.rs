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
    /// Run a read-only Language Server Protocol adapter over stdio.
    Lsp(LspArgs),
    /// Open the interactive source-index demo backed by source-index.db.
    Demo(DemoArgs),
    /// Query source-index metrics directly from SQLite.
    Metrics {
        #[command(subcommand)]
        command: MetricsCommand,
    },
    /// Install or update local integrations and managed assets.
    Setup(SetupArgs),
    /// Install or repair Kast resources.
    Install(InstallArgs),
    /// Verify the global Kast install is still healthy.
    Doctor,
}

#[derive(Debug, Subcommand, Clone)]
pub enum BackendCommand {
    /// Install one backend component from a release asset or local archive.
    Install(BackendInstallArgs),
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
    /// Disable TLS certificate verification for downloads; SHA256SUMS and provenance checks still run.
    #[arg(long)]
    pub insecure_skip_tls_verify: bool,
    /// Replace an existing installed backend version.
    #[arg(short = 'f', long)]
    pub force: bool,
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
    /// Release tag or version for the retired headless auto-install fallback.
    #[arg(long, hide = true)]
    pub install_version: Option<String>,
    /// Release directory URL for the retired headless auto-install fallback.
    #[arg(long, hide = true)]
    pub install_base_url: Option<String>,
    /// Disable TLS certificate verification for the retired download fallback.
    #[arg(long, hide = true)]
    pub install_insecure_skip_tls_verify: bool,
    #[arg(skip = false)]
    pub auto_install_headless: bool,
}

#[derive(Debug, Args, Clone)]
pub struct DaemonStartArgs {
    /// Absolute workspace root for daemon lifecycle and RPC commands.
    #[arg(long)]
    pub workspace_root: Option<PathBuf>,
    /// Backend runtime to launch. Defaults to headless.
    #[arg(long = "backend", visible_alias = "backend-name", value_enum)]
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
    #[arg(long = "backend", visible_alias = "backend-name", value_enum)]
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
pub struct SetupArgs {
    /// Replace existing installed resources.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Skip global config and managed asset repair.
    #[arg(long)]
    pub skip_repair: bool,
    /// Shell to install integration for. Defaults to the current SHELL.
    #[arg(long, value_enum)]
    pub shell: Option<ShellKind>,
    /// Skip shell PATH and completion integration.
    #[arg(long)]
    pub skip_shell: bool,
    /// Skip headless backend installation or refresh.
    #[arg(long)]
    pub skip_headless: bool,
    /// Skip IDEA plugin installation or profile linking.
    #[arg(long)]
    pub skip_plugin: bool,
    /// Local headless backend zip archive for refreshing an existing headless install.
    #[arg(long)]
    pub headless_archive: Option<PathBuf>,
    /// Release tag or version for refreshing an existing headless install.
    #[arg(long)]
    pub version: Option<String>,
    /// Release directory URL used when refreshing an existing headless install.
    #[arg(long)]
    pub base_url: Option<String>,
    /// Install the packaged kast skill into the configured target directory.
    #[arg(long)]
    pub include_skill: bool,
    /// Skip packaged kast skill installation even when --include-skill is present.
    #[arg(long)]
    pub skip_skill: bool,
    /// Target root directory for --include-skill.
    #[arg(long)]
    pub skill_target_dir: Option<PathBuf>,
    /// Install the packaged Copilot agents, hooks, skills, LSP config, and extensions.
    #[arg(long)]
    pub include_copilot: bool,
    /// Skip packaged Copilot extension installation even when --include-copilot is present.
    #[arg(long)]
    pub skip_copilot: bool,
    /// Target .github directory for --include-copilot.
    #[arg(long)]
    pub copilot_target_dir: Option<PathBuf>,
    /// Link the Homebrew cask into local JetBrains IDE profiles.
    #[arg(long, hide = true)]
    pub link_jetbrains_profiles: bool,
    /// JetBrains config root containing IDE profile directories.
    #[arg(long)]
    pub jetbrains_config_root: Option<PathBuf>,
}

#[derive(Debug, Args, Clone)]
pub struct InstallArgs {
    #[command(subcommand)]
    pub command: Option<InstallCommand>,
    /// Absolute path to a portable Kast zip archive to install.
    #[arg(long, hide = true)]
    pub archive: Option<PathBuf>,
    /// Instance name for the installed build.
    #[arg(long, hide = true)]
    pub instance: Option<String>,
    /// Root directory for instances.
    #[arg(long, hide = true)]
    pub instances_root: Option<PathBuf>,
    /// Directory for launcher scripts.
    #[arg(long, hide = true)]
    pub bin_dir: Option<PathBuf>,
}

#[derive(Debug, Subcommand, Clone)]
pub enum InstallCommand {
    /// Install the headless JVM backend from an internal archive.
    #[command(hide = true)]
    Headless(HeadlessInstallArgs),
    /// Audit and repair stale Kast installs, resources, and profile links.
    Affected(AffectedInstallArgs),
    /// Install the packaged kast skill into the current workspace.
    Skill(ResourceInstallArgs),
    /// Install the packaged Copilot agents, hooks, skills, LSP config, and extensions.
    #[command(alias = "copilot-extension")]
    Copilot(CopilotInstallArgs),
    /// Install the Homebrew-managed IDEA plugin cask and link JetBrains profiles.
    #[command(alias = "idea-plugin", alias = "developer-plugin")]
    Plugin(IdeaPluginInstallArgs),
    /// Install shell PATH and completion integration.
    Shell(ShellInstallArgs),
    /// Print shell completion scripts.
    Completion(CompletionArgs),
}

#[derive(Debug, Args, Clone)]
pub struct AffectedInstallArgs {
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
pub struct HeadlessInstallArgs {
    /// Local backend zip archive to refresh an existing Linux headless tarball install.
    #[arg(long)]
    pub archive: Option<PathBuf>,
    /// Version label to record for the local archive. Defaults to this CLI version.
    #[arg(long)]
    pub version: Option<String>,
    /// Retired standalone backend release URL option.
    #[arg(long)]
    pub base_url: Option<String>,
    /// Retired standalone backend download TLS option.
    #[arg(long)]
    pub insecure_skip_tls_verify: bool,
    /// Replace an existing installed backend version.
    #[arg(short = 'f', long)]
    pub force: bool,
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
    /// Target .github directory. Defaults to <cwd>/.github.
    #[arg(long)]
    pub target_dir: Option<PathBuf>,
    /// Overwrite existing managed resources.
    #[arg(short = 'f', long)]
    pub force: bool,
    /// Do not install the native Copilot extension files under .github/extensions/kast.
    #[arg(long)]
    pub exclude_extension: bool,
    /// Do not install .github/lsp.json.
    #[arg(long)]
    pub exclude_lsp: bool,
    /// Do not install .github/instructions.
    #[arg(long)]
    pub exclude_instructions: bool,
    /// Do not install .github/agents.
    #[arg(long)]
    pub exclude_agents: bool,
    /// Do not install .github/hooks.
    #[arg(long)]
    pub exclude_hooks: bool,
    /// Do not install .agents/skills from the Copilot primitive bundle.
    #[arg(long)]
    pub exclude_skills: bool,
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

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum BackendComponent {
    Headless,
}

impl BackendComponent {
    pub fn canonical(self) -> &'static str {
        match self {
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
