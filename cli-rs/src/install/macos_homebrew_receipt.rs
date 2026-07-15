const MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION: u32 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum MacosInstallAuthority {
    MacosHomebrew,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MacosHomebrewCliReceipt {
    pub binary: PathBuf,
    pub formula_prefix: PathBuf,
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MacosHomebrewInstallReceipt {
    pub schema_version: u32,
    pub authority: MacosInstallAuthority,
    pub cli: MacosHomebrewCliReceipt,
    pub updated_at: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LegacyMacosHomebrewInstallReceipt {
    schema_version: u32,
    authority: MacosInstallAuthority,
    cli: MacosHomebrewCliReceipt,
    plugin: LegacyPluginFields,
    updated_at: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LegacyPluginFields {
    cask_token: String,
    version: String,
}

impl MacosHomebrewInstallReceipt {
    fn new(cli_binary: PathBuf, formula_prefix: PathBuf, cli_version: String) -> Self {
        Self {
            schema_version: MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION,
            authority: MacosInstallAuthority::MacosHomebrew,
            cli: MacosHomebrewCliReceipt {
                binary: cli_binary,
                formula_prefix,
                version: cli_version,
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

pub fn read_macos_homebrew_receipt_at(path: &Path) -> Result<MacosHomebrewInstallReceipt> {
    let raw = fs::read(path)?;
    let receipt: MacosHomebrewInstallReceipt = serde_json::from_slice(&raw).map_err(|error| {
        CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "macOS Homebrew CLI receipt is not valid schema-2 JSON at {}; run `kast repair --for machine --apply` with this Homebrew-installed CLI to migrate recognized legacy state: {error}",
                path.display()
            ),
        )
    })?;
    validate_macos_homebrew_receipt(path, receipt)
}

fn validate_macos_homebrew_receipt(
    path: &Path,
    receipt: MacosHomebrewInstallReceipt,
) -> Result<MacosHomebrewInstallReceipt> {
    if receipt.schema_version != MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "macOS Homebrew CLI receipt schemaVersion {} is not supported; expected {} at {}; run `kast repair --for machine --apply`",
                receipt.schema_version,
                MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION,
                path.display()
            ),
        ));
    }
    if !receipt.cli.binary.is_absolute()
        || !receipt.cli.formula_prefix.is_absolute()
        || receipt.cli.version.trim().is_empty()
        || receipt.updated_at.trim().is_empty()
    {
        return Err(invalid_receipt(path, "contains an invalid CLI authority projection"));
    }
    if receipt.cli.version != cli::version() {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_VERSION_MISMATCH",
            format!(
                "macOS Homebrew CLI receipt records version {}; update Kast and rerun `kast repair --for machine --apply` with running version {} at {}",
                receipt.cli.version,
                cli::version(),
                path.display()
            ),
        ));
    }
    if !receipt.cli.formula_prefix.is_dir() || !receipt_binary_is_executable(&receipt.cli.binary) {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_BINARY_MISSING",
            format!(
                "macOS Homebrew CLI receipt points to a missing formula prefix or non-executable CLI binary at {}; reinstall the Kast formula and run `kast repair --for machine --apply`",
                receipt.cli.binary.display()
            ),
        ));
    }
    if !path_is_below_homebrew_formula(&receipt.cli.binary, &receipt.cli.formula_prefix) {
        return Err(invalid_receipt(
            path,
            "CLI resolves outside its Homebrew formula prefix",
        ));
    }
    if !is_kast_homebrew_formula_prefix(&receipt.cli.formula_prefix, &receipt.cli.version) {
        return Err(invalid_receipt(
            path,
            "formulaPrefix is not an exact Homebrew Cellar/kast version root",
        ));
    }
    Ok(receipt)
}

fn invalid_receipt(path: &Path, reason: &str) -> CliError {
    CliError::new(
        "MACOS_HOMEBREW_RECEIPT_INVALID",
        format!("macOS Homebrew CLI receipt {reason} at {}", path.display()),
    )
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

fn path_is_below_homebrew_formula(path: &Path, formula_prefix: &Path) -> bool {
    let Ok(path) = fs::canonicalize(path) else {
        return false;
    };
    let Ok(formula_prefix) = fs::canonicalize(formula_prefix) else {
        return false;
    };
    path.starts_with(&formula_prefix)
}

fn is_kast_homebrew_formula_prefix(formula_prefix: &Path, expected_version: &str) -> bool {
    let Ok(formula_prefix) = fs::canonicalize(formula_prefix) else {
        return false;
    };
    let Some(version) = formula_prefix.file_name() else {
        return false;
    };
    let Some(formula) = formula_prefix.parent() else {
        return false;
    };
    let Some(cellar) = formula.parent() else {
        return false;
    };
    version == expected_version
        && formula.file_name().is_some_and(|name| name == "kast")
        && cellar.file_name().is_some_and(|name| name == "Cellar")
}

fn running_cli_matches_receipt(receipt: &MacosHomebrewInstallReceipt) -> bool {
    let Ok(recorded) = fs::canonicalize(&receipt.cli.binary) else {
        return false;
    };
    let Ok(running) = env::current_exe().and_then(fs::canonicalize) else {
        return false;
    };
    recorded == running
}

fn validate_running_macos_homebrew_receipt(
    path: &Path,
    receipt: MacosHomebrewInstallReceipt,
) -> Result<MacosHomebrewInstallReceipt> {
    if !running_cli_matches_receipt(&receipt) {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_BINARY_MISMATCH",
            format!(
                "macOS Homebrew CLI receipt binary {} does not match the running Kast executable; invoke the exact Homebrew formula binary and run `kast repair --for machine --apply` at {}",
                receipt.cli.binary.display(),
                path.display(),
            ),
        ));
    }
    Ok(receipt)
}

fn exact_legacy_macos_homebrew_receipt(
    path: &Path,
) -> Result<Option<MacosHomebrewInstallReceipt>> {
    let raw = fs::read(path)?;
    let legacy: LegacyMacosHomebrewInstallReceipt = serde_json::from_slice(&raw).map_err(|error| {
        CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "Homebrew receipt at {} is neither schema 2 nor the exact recognized schema-1 joint receipt; it was preserved unchanged: {error}",
                path.display(),
            ),
        )
    })?;
    let recognized = legacy.schema_version == 1
        && legacy.cli.version == cli::version()
        && legacy.plugin.version == cli::version()
        && legacy.plugin.cask_token == "amichne/kast/kast-plugin"
        && !legacy.updated_at.trim().is_empty()
        && legacy.cli.binary.is_absolute()
        && legacy.cli.formula_prefix.is_absolute()
        && receipt_binary_is_executable(&legacy.cli.binary)
        && path_is_below_homebrew_formula(&legacy.cli.binary, &legacy.cli.formula_prefix)
        && is_kast_homebrew_formula_prefix(&legacy.cli.formula_prefix, &legacy.cli.version);
    if !recognized {
        let mut error = CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "Legacy Homebrew receipt at {} is not an exact schema-1 Kast joint receipt for running CLI version {}; it was preserved unchanged",
                path.display(),
                cli::version(),
            ),
        );
        error.details.insert(
            "legacyCliVersion".to_string(),
            legacy.cli.version.clone(),
        );
        error.details.insert(
            "legacyPluginVersion".to_string(),
            legacy.plugin.version.clone(),
        );
        error.details.insert(
            "binaryExecutable".to_string(),
            receipt_binary_is_executable(&legacy.cli.binary).to_string(),
        );
        error.details.insert(
            "binaryBelowFormula".to_string(),
            path_is_below_homebrew_formula(&legacy.cli.binary, &legacy.cli.formula_prefix)
                .to_string(),
        );
        return Err(error);
    }
    let _legacy_authority = legacy.authority;
    let migrated = MacosHomebrewInstallReceipt::new(
        legacy.cli.binary,
        legacy.cli.formula_prefix,
        legacy.cli.version,
    );
    if !running_cli_matches_receipt(&migrated) {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_BINARY_MISMATCH",
            format!(
                "Legacy Homebrew receipt at {} does not name the running Kast executable; it was preserved unchanged",
                path.display(),
            ),
        ));
    }
    Ok(Some(migrated))
}

#[cfg(target_os = "macos")]
fn discover_running_homebrew_receipt() -> Result<Option<MacosHomebrewInstallReceipt>> {
    let output = match ProcessCommand::new("brew").args(["--prefix", "kast"]).output() {
        Ok(output) if output.status.success() => output,
        _ => return Ok(None),
    };
    let formula_prefix = PathBuf::from(String::from_utf8_lossy(&output.stdout).trim());
    let running = env::current_exe()?;
    if formula_prefix.as_os_str().is_empty()
        || !is_kast_homebrew_formula_prefix(&formula_prefix, cli::version())
        || !path_is_below_homebrew_formula(&running, &formula_prefix)
    {
        return Ok(None);
    }
    Ok(Some(MacosHomebrewInstallReceipt::new(
        running,
        formula_prefix,
        cli::version().to_string(),
    )))
}

#[cfg(not(target_os = "macos"))]
fn discover_running_homebrew_receipt() -> Result<Option<MacosHomebrewInstallReceipt>> {
    Ok(None)
}

pub fn read_macos_homebrew_receipt() -> Result<Option<MacosHomebrewInstallReceipt>> {
    let path = default_macos_homebrew_receipt_path();
    if !path.is_file() {
        return Ok(None);
    }
    read_macos_homebrew_receipt_at(&path)
        .and_then(|receipt| validate_running_macos_homebrew_receipt(&path, receipt))
        .map(Some)
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

pub fn macos_homebrew_repair_authority_is_provable() -> Result<bool> {
    #[cfg(target_os = "macos")]
    {
        Ok(default_macos_homebrew_receipt_path().is_file()
            || discover_running_homebrew_receipt()?.is_some())
    }
    #[cfg(not(target_os = "macos"))]
    {
        Ok(false)
    }
}
