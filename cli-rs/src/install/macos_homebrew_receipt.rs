const MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum MacosInstallAuthority {
    MacosHomebrew,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MacosHomebrewCliReceipt {
    pub binary: PathBuf,
    pub formula_prefix: PathBuf,
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MacosHomebrewPluginReceipt {
    pub cask_token: String,
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MacosHomebrewInstallReceipt {
    pub schema_version: u32,
    pub authority: MacosInstallAuthority,
    pub cli: MacosHomebrewCliReceipt,
    pub plugin: MacosHomebrewPluginReceipt,
    pub updated_at: String,
}

impl MacosHomebrewInstallReceipt {
    fn new(
        cli_binary: PathBuf,
        formula_prefix: PathBuf,
        cli_version: String,
        cask_token: String,
        plugin_version: String,
    ) -> Self {
        Self {
            schema_version: MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION,
            authority: MacosInstallAuthority::MacosHomebrew,
            cli: MacosHomebrewCliReceipt {
                binary: cli_binary,
                formula_prefix,
                version: cli_version,
            },
            plugin: MacosHomebrewPluginReceipt {
                cask_token,
                version: plugin_version,
            },
            updated_at: current_timestamp(),
        }
    }
}

pub fn macos_homebrew_receipt_path(home: &Path) -> PathBuf {
    home.join("Library/Application Support/Kast/homebrew-install.json")
}

pub fn default_macos_homebrew_receipt_path() -> PathBuf {
    macos_homebrew_receipt_path(&config::home_dir())
}

fn write_macos_homebrew_receipt_at(
    path: &Path,
    receipt: &MacosHomebrewInstallReceipt,
) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension(format!("json.{}.tmp", std::process::id()));
    fs::write(&temporary, serde_json::to_vec_pretty(receipt)?)?;
    fs::rename(temporary, path)?;
    Ok(())
}

fn write_macos_homebrew_receipt(receipt: &MacosHomebrewInstallReceipt) -> Result<PathBuf> {
    let path = default_macos_homebrew_receipt_path();
    write_macos_homebrew_receipt_at(&path, receipt)?;
    Ok(path)
}

pub fn read_macos_homebrew_receipt_at(path: &Path) -> Result<MacosHomebrewInstallReceipt> {
    let raw = fs::read(path)?;
    let receipt: MacosHomebrewInstallReceipt = serde_json::from_slice(&raw).map_err(|error| {
        CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "macOS Homebrew install receipt is not valid JSON at {}: {error}",
                path.display()
            ),
        )
    })?;
    if receipt.schema_version != MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "macOS Homebrew install receipt schemaVersion {} is not supported; expected {} at {}",
                receipt.schema_version,
                MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION,
                path.display()
            ),
        ));
    }
    if !receipt.cli.binary.is_absolute()
        || !receipt.cli.formula_prefix.is_absolute()
        || receipt.cli.version.trim().is_empty()
        || receipt.plugin.cask_token.trim().is_empty()
        || receipt.plugin.version.trim().is_empty()
        || receipt.updated_at.trim().is_empty()
    {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "macOS Homebrew install receipt contains an invalid authority projection at {}",
                path.display()
            ),
        ));
    }
    let expected_version = cli::version();
    if receipt.cli.version != receipt.plugin.version
        || receipt.cli.version != expected_version
    {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_VERSION_MISMATCH",
            format!(
                "macOS Homebrew install receipt records CLI version {} and plugin version {}; both must match running Kast version {} at {}",
                receipt.cli.version,
                receipt.plugin.version,
                expected_version,
                path.display()
            ),
        ));
    }
    if !receipt.cli.formula_prefix.is_dir() || !receipt_binary_is_executable(&receipt.cli.binary) {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_BINARY_MISSING",
            format!(
                "macOS Homebrew install receipt points to a missing formula prefix or non-executable CLI binary at {}; rerun the Kast macOS installer",
                receipt.cli.binary.display()
            ),
        ));
    }
    if !path_is_below_homebrew_formula(&receipt.cli.binary, &receipt.cli.formula_prefix) {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "macOS Homebrew install receipt CLI resolves outside its formula prefix at {}",
                path.display()
            ),
        ));
    }
    Ok(receipt)
}

fn receipt_binary_is_executable(path: &Path) -> bool {
    let Ok(metadata) = fs::metadata(path) else {
        return false;
    };
    if !metadata.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        metadata.permissions().mode() & 0o111 != 0
    }
    #[cfg(not(unix))]
    {
        true
    }
}

pub fn read_macos_homebrew_receipt() -> Result<Option<MacosHomebrewInstallReceipt>> {
    let path = default_macos_homebrew_receipt_path();
    if !path.is_file() {
        return Ok(None);
    }
    read_macos_homebrew_receipt_at(&path).map(Some)
}

pub fn macos_homebrew_authority_is_active() -> Result<bool> {
    #[cfg(target_os = "macos")]
    {
        Ok(read_macos_homebrew_receipt()?.is_some())
    }
    #[cfg(not(target_os = "macos"))]
    {
        Ok(false)
    }
}
