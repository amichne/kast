#[derive(Debug, Args, Clone)]
#[command(disable_help_subcommand = true)]
pub struct LocalDevelopmentArgs {
    #[command(subcommand)]
    pub command: LocalDevelopmentCommand,
}

#[derive(Debug, Subcommand, Clone)]
pub enum LocalDevelopmentCommand {
    /// Capture strict source identity for a refresh transaction.
    Snapshot(LocalDevelopmentSnapshotArgs),
    /// Bind one built artifact to the captured source snapshot and its exact bytes.
    Attest(LocalDevelopmentAttestArgs),
    /// Publish one immutable source-attested generation for reuse.
    Prepare(LocalDevelopmentPrepareArgs),
    /// Verify every source and component digest in a prepared generation.
    Verify(LocalDevelopmentVerifyArgs),
    /// Activate one already-prepared generation without rebuilding it.
    Activate(LocalDevelopmentActivateArgs),
    /// Refresh one isolated, revision-coherent local development authority.
    Refresh(LocalDevelopmentRefreshArgs),
    /// Reactivate the validated previous local generation.
    Rollback(LocalDevelopmentRollbackArgs),
    /// Remove only receipt-owned local state and restore ordinary authority.
    Remove(LocalDevelopmentRemoveArgs),
}

#[derive(Debug, Args, Clone)]
pub struct LocalDevelopmentSnapshotArgs {
    /// Canonical checkout whose content is captured.
    #[arg(long)]
    pub source_root: PathBuf,
    /// Strict JSON snapshot file to write atomically.
    #[arg(long)]
    pub output_file: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct LocalDevelopmentAttestArgs {
    /// Canonical checkout whose source snapshot produced the artifact.
    #[arg(long)]
    pub source_root: PathBuf,
    /// Captured source snapshot that must still match while hashing the artifact.
    #[arg(long)]
    pub expected_source_snapshot: PathBuf,
    /// Typed artifact surface being attested.
    #[arg(long, value_enum)]
    pub artifact_kind: LocalArtifactKindArg,
    /// Built CLI file or portable headless backend directory.
    #[arg(long)]
    pub artifact: PathBuf,
    /// Strict provenance JSON file to write atomically.
    #[arg(long)]
    pub output_file: PathBuf,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq, Eq)]
pub enum LocalArtifactKindArg {
    Cli,
    HeadlessBackend,
}

#[derive(Debug, Args, Clone)]
pub struct LocalDevelopmentRefreshArgs {
    /// Canonical checkout whose source snapshot owns the local authority.
    #[arg(long)]
    pub source_root: PathBuf,
    /// Exact workspace that receives the revision-matched agent guidance.
    #[arg(long)]
    pub workspace_root: PathBuf,
    /// Isolated local-development prefix. Defaults to a checkout-derived path.
    #[arg(long)]
    pub prefix: Option<PathBuf>,
    /// Captured source snapshot that must still match after artifact staging.
    #[arg(long)]
    pub expected_source_snapshot: PathBuf,
    /// Built development CLI artifact to install.
    #[arg(long)]
    pub cli_binary: PathBuf,
    /// Source-bound provenance emitted by that exact CLI artifact.
    #[arg(long)]
    pub cli_provenance: PathBuf,
    /// Built portable headless backend directory to install.
    #[arg(long)]
    pub backend_directory: PathBuf,
    /// Source-bound provenance for the exact backend directory bytes.
    #[arg(long)]
    pub backend_provenance: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct LocalDevelopmentPrepareArgs {
    /// Canonical checkout whose captured source owns the generation.
    #[arg(long)]
    pub source_root: PathBuf,
    /// Captured source snapshot that must still match every prepared input.
    #[arg(long)]
    pub expected_source_snapshot: PathBuf,
    /// Exact source-bound development CLI.
    #[arg(long)]
    pub cli_binary: PathBuf,
    /// Provenance for the exact source-bound CLI.
    #[arg(long)]
    pub cli_provenance: PathBuf,
    /// Exact source-bound portable headless backend directory.
    #[arg(long)]
    pub backend_directory: PathBuf,
    /// Provenance for the exact source-bound backend tree.
    #[arg(long)]
    pub backend_provenance: PathBuf,
    /// Immutable generation directory to publish atomically.
    #[arg(long)]
    pub output_directory: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct LocalDevelopmentVerifyArgs {
    /// Canonical checkout whose current source must match the generation.
    #[arg(long)]
    pub source_root: PathBuf,
    /// Prepared generation directory to verify without mutation.
    #[arg(long)]
    pub prepared_generation: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct LocalDevelopmentActivateArgs {
    /// Canonical checkout whose source identity owns the generation.
    #[arg(long)]
    pub source_root: PathBuf,
    /// Exact workspace that receives revision-matched agent guidance.
    #[arg(long)]
    pub workspace_root: PathBuf,
    /// Isolated local-development prefix. Defaults to a checkout-derived path.
    #[arg(long)]
    pub prefix: Option<PathBuf>,
    /// Already-prepared generation to verify and activate without builds.
    #[arg(long)]
    pub prepared_generation: PathBuf,
}

#[derive(Debug, Args, Clone)]
pub struct LocalDevelopmentRollbackArgs {
    /// Exact isolated local-development prefix to roll back.
    #[arg(long)]
    pub prefix: PathBuf,
    /// Exact previous generation to activate; retries are a no-op once it is current.
    #[arg(long)]
    pub to_generation: String,
}

#[derive(Debug, Args, Clone)]
pub struct LocalDevelopmentRemoveArgs {
    /// Exact isolated local-development prefix to remove.
    #[arg(long)]
    pub prefix: PathBuf,
    /// Exact workspace whose receipt-owned guidance binding may be removed.
    #[arg(long)]
    pub workspace_root: PathBuf,
}
