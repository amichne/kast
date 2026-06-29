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

#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct RuntimeCommandArgs {
    #[command(subcommand)]
    pub command: RuntimeCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum RuntimeCommand {
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
}
