#[derive(Debug, Parser)]
#[command(
    name = "kast",
    version = version(),
    about = "Compiler-backed Kotlin knowledge and changes for coding agents.",
    disable_help_subcommand = true,
    after_help = "Developer operations: run `kast` to read `developerOperations`, then invoke `/kast:developer`."
)]
pub struct KastCli {
    /// Select compact TOON or JSON with the same canonical protocol schema.
    #[arg(long, value_enum, global = true, default_value_t = KastOutputFormat::Toon)]
    pub output: KastOutputFormat,
    #[command(subcommand)]
    pub command: Option<KastCommand>,
}

#[derive(Debug, Subcommand)]
pub enum KastCommand {
    #[command(name = "__internal", hide = true)]
    Internal(KastInternalArgs),
    /// Demand source-ready semantic evidence for the exact workspace.
    Up,
    /// Establish and refresh exact-workspace semantic evidence.
    Workspace(KastWorkspaceArgs),
    /// Discover workspace-relative Kotlin files.
    File(KastFileArgs),
    /// Find symbols and traverse compiler-backed relationships.
    Symbol(KastSymbolArgs),
    /// Traverse compiler-backed relationships from an exact selector.
    Relation(KastRelationArgs),
    /// Inspect persisted topology and graph statistics.
    Graph(KastGraphArgs),
    /// Check compiler diagnostics for changed or selected files.
    Diagnostic(KastDiagnosticArgs),
    /// Plan, apply, or recover validated semantic changes.
    Change(KastChangeArgs),
}

#[derive(Debug, Args)]
pub struct KastInternalArgs {
    #[command(subcommand)]
    pub command: KastInternalCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastInternalCommand {
    Resources(KastResourcesArgs),
}

#[derive(Debug, Args)]
pub struct KastResourcesArgs {
    #[command(subcommand)]
    pub command: KastResourcesCommand,
}

#[derive(Debug, Subcommand)]
pub enum KastResourcesCommand {
    Install {
        #[arg(
            long = "harness",
            required = true,
            value_enum,
            action = clap::ArgAction::Append
        )]
        harnesses: Vec<KastHarness>,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, ValueEnum)]
pub enum KastHarness {
    Codex,
    Claude,
    Copilot,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum KastOutputFormat {
    Json,
    Toon,
}

impl From<KastOutputFormat> for OutputFormat {
    fn from(value: KastOutputFormat) -> Self {
        match value {
            KastOutputFormat::Json => Self::Json,
            KastOutputFormat::Toon => Self::Toon,
        }
    }
}

include!("public_operations.rs");
