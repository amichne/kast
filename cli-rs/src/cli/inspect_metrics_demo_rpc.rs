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
pub struct PublicDemoArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Open the story for a specific symbol query instead of ranked candidates.
    #[arg(long)]
    pub symbol: Option<String>,
}

#[derive(Debug, Args, Clone, Default)]
#[command(disable_help_flag = true)]
pub struct RemovedDemoArgs {
    /// Stale demo arguments retained only to emit replacement guidance.
    #[arg(allow_hyphen_values = true, trailing_var_arg = true)]
    pub args: Vec<String>,
}
