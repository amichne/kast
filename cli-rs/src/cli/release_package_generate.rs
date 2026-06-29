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
