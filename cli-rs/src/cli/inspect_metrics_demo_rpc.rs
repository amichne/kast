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
pub struct PublicDemoArgs {
    #[command(flatten)]
    pub runtime: AgentRuntimeArgs,
    /// Open the story for a specific symbol query instead of ranked candidates.
    #[arg(long)]
    pub symbol: Option<String>,
}
