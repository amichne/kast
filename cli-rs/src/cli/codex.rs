#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct CodexArgs {
    #[command(subcommand)]
    pub command: CodexCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum CodexCommand {
    /// Generate or verify the repository-owned Codex plugin.
    Generate(CodexGenerateArgs),
}

#[derive(Debug, Args, Clone)]
pub struct CodexGenerateArgs {
    /// Fail when committed generated assets differ from the Rust contract.
    #[arg(long, conflicts_with = "release")]
    pub check: bool,
    /// Render a release artifact using the compiled Kast version.
    #[arg(long, requires = "output_dir")]
    pub release: bool,
    /// Marketplace root to render. Defaults to the checked-in source tree.
    #[arg(long)]
    pub output_dir: Option<PathBuf>,
}
