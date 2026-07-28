#[derive(Debug, Args, Clone)]
#[command(
    disable_help_subcommand = true,
    after_help = "Examples:\n  kast config list --workspace-root \"$PWD\"\n  kast config set indexing.phase2Parallelism 2 --workspace-root \"$PWD\"\n  kast config unset indexing.phase2Parallelism --workspace-root \"$PWD\""
)]
pub struct ConfigArgs {
    #[command(subcommand)]
    pub command: ConfigCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum ConfigCommand {
    /// List the complete effective configuration and mutable workspace fields.
    List(ConfigWorkspaceArgs),
    /// Set one supported workspace field.
    Set(ConfigSetArgs),
    /// Remove one workspace override and reveal its inherited value.
    Unset(ConfigUnsetArgs),
}

#[derive(Debug, Args, Clone)]
pub struct ConfigWorkspaceArgs {
    /// Absolute workspace root whose effective configuration is inspected.
    #[arg(long)]
    pub workspace_root: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct ConfigSetArgs {
    /// Exact dotted configuration key from `kast config list`.
    pub key: String,
    /// Boolean, integer, or string value accepted by the selected field.
    pub value: String,
    /// Absolute workspace root whose override is updated.
    #[arg(long)]
    pub workspace_root: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct ConfigUnsetArgs {
    /// Exact dotted configuration key from `kast config list`.
    pub key: String,
    /// Absolute workspace root whose override is removed.
    #[arg(long)]
    pub workspace_root: PathBuf,
}
