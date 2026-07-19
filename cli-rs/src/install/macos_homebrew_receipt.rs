const MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION: u32 = 3;

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
    pub release_revision: cli::ReleaseRevision,
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
    cli: LegacyMacosHomebrewCliReceipt,
    plugin: LegacyPluginFields,
    updated_at: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LegacySchema2MacosHomebrewInstallReceipt {
    schema_version: u32,
    authority: MacosInstallAuthority,
    cli: LegacyMacosHomebrewCliReceipt,
    updated_at: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LegacyMacosHomebrewCliReceipt {
    binary: PathBuf,
    formula_prefix: PathBuf,
    version: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LegacyPluginFields {
    cask_token: String,
    version: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum ExistingMacosHomebrewReceiptForRepair {
    Current(MacosHomebrewInstallReceipt),
    StaleSchema3,
    LegacySchema2,
    LegacySchema1(MacosHomebrewInstallReceipt),
}

#[derive(Debug)]
pub(crate) enum MacosHomebrewAuthorityResolution {
    Absent,
    Active(MacosHomebrewInstallReceipt),
    Recoverable(MacosHomebrewInstallReceipt),
    Blocked(CliError),
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
                release_revision: cli::ReleaseRevision::current(),
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
    use std::io::Write as _;

    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension(format!("json.{}.tmp", std::process::id()));
    let mut file = fs::File::create(&temporary)?;
    file.write_all(&serde_json::to_vec_pretty(receipt)?)?;
    file.write_all(b"\n")?;
    file.sync_all()?;
    fs::rename(&temporary, path)?;
    if let Some(parent) = path.parent() {
        fs::File::open(parent)?.sync_all()?;
    }
    Ok(())
}

fn with_macos_homebrew_receipt_lock<T>(
    receipt_path: &Path,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    let parent = receipt_path.parent().ok_or_else(|| {
        CliError::new(
            "MACOS_HOMEBREW_RECEIPT_PATH_INVALID",
            format!(
                "macOS Homebrew receipt has no parent directory: {}",
                receipt_path.display(),
            ),
        )
    })?;
    fs::create_dir_all(parent)?;
    let lock = fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(parent.join("homebrew-install.lock"))?;
    lock_macos_homebrew_receipt(&lock)?;
    let result = action();
    unlock_macos_homebrew_receipt(&lock)?;
    result
}

#[cfg(unix)]
fn lock_macos_homebrew_receipt(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd as _;

    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(unix)]
fn unlock_macos_homebrew_receipt(file: &fs::File) -> Result<()> {
    use std::os::fd::AsRawFd as _;

    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_UN) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error().into())
    }
}

#[cfg(not(unix))]
fn lock_macos_homebrew_receipt(_file: &fs::File) -> Result<()> {
    Ok(())
}

#[cfg(not(unix))]
fn unlock_macos_homebrew_receipt(_file: &fs::File) -> Result<()> {
    Ok(())
}

pub fn read_macos_homebrew_receipt_at(path: &Path) -> Result<MacosHomebrewInstallReceipt> {
    let raw = fs::read(path)?;
    let receipt: MacosHomebrewInstallReceipt = serde_json::from_slice(&raw).map_err(|error| {
        CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "macOS Homebrew CLI receipt is not valid schema-3 JSON at {}; run `kast repair --for machine --apply` with this Homebrew-installed CLI to migrate recognized legacy state: {error}",
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
    if receipt.cli.release_revision != cli::ReleaseRevision::current() {
        return Err(CliError::new(
            "MACOS_HOMEBREW_RECEIPT_REVISION_MISMATCH",
            format!(
                "macOS Homebrew CLI receipt records release revision {}; update Kast and rerun `kast repair --for machine --apply` with running revision {} at {}",
                receipt.cli.release_revision.as_str(),
                cli::release_revision(),
                path.display(),
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

fn classify_existing_macos_homebrew_receipt_for_repair(
    path: &Path,
) -> Result<ExistingMacosHomebrewReceiptForRepair> {
    if let Ok(receipt) = read_macos_homebrew_receipt_at(path) {
        return Ok(ExistingMacosHomebrewReceiptForRepair::Current(receipt));
    }
    if exact_stale_schema_3_macos_homebrew_receipt(path)? {
        return Ok(ExistingMacosHomebrewReceiptForRepair::StaleSchema3);
    }
    if exact_legacy_schema_2_macos_homebrew_receipt(path)? {
        return Ok(ExistingMacosHomebrewReceiptForRepair::LegacySchema2);
    }
    if let Some(receipt) = exact_legacy_macos_homebrew_receipt(path)? {
        return Ok(ExistingMacosHomebrewReceiptForRepair::LegacySchema1(
            receipt,
        ));
    }
    Err(CliError::new(
        "MACOS_HOMEBREW_RECEIPT_INVALID",
        format!(
            "Homebrew receipt at {} is not current schema 3, an exact stale schema-3 CLI receipt, an exact schema-2 CLI receipt, or the exact recognized schema-1 joint receipt; it was preserved unchanged",
            path.display(),
        ),
    ))
}

fn exact_stale_schema_3_macos_homebrew_receipt(path: &Path) -> Result<bool> {
    let raw = fs::read(path)?;
    let Ok(receipt) = serde_json::from_slice::<MacosHomebrewInstallReceipt>(&raw) else {
        return Ok(false);
    };
    Ok(receipt.schema_version == MACOS_HOMEBREW_RECEIPT_SCHEMA_VERSION
        && (receipt.cli.version != cli::version()
            || receipt.cli.release_revision != cli::ReleaseRevision::current())
        && !receipt.cli.version.trim().is_empty()
        && !receipt.updated_at.trim().is_empty()
        && path_is_normalized_absolute(&receipt.cli.binary)
        && path_is_normalized_absolute(&receipt.cli.formula_prefix)
        && path_is_lexically_below(&receipt.cli.binary, &receipt.cli.formula_prefix)
        && is_lexical_kast_homebrew_formula_prefix(
            &receipt.cli.formula_prefix,
            &receipt.cli.version,
        ))
}

fn exact_legacy_schema_2_macos_homebrew_receipt(path: &Path) -> Result<bool> {
    let raw = fs::read(path)?;
    let Ok(receipt) = serde_json::from_slice::<LegacySchema2MacosHomebrewInstallReceipt>(&raw)
    else {
        return Ok(false);
    };
    let _legacy_authority = receipt.authority;
    Ok(receipt.schema_version == 2
        && !receipt.cli.version.trim().is_empty()
        && !receipt.updated_at.trim().is_empty()
        && path_is_normalized_absolute(&receipt.cli.binary)
        && path_is_normalized_absolute(&receipt.cli.formula_prefix)
        && path_is_lexically_below(&receipt.cli.binary, &receipt.cli.formula_prefix)
        && is_lexical_kast_homebrew_formula_prefix(
            &receipt.cli.formula_prefix,
            &receipt.cli.version,
        ))
}

fn path_is_normalized_absolute(path: &Path) -> bool {
    path.is_absolute()
        && path
            .components()
            .all(|component| !matches!(component, Component::CurDir | Component::ParentDir))
}

fn path_is_lexically_below(path: &Path, parent: &Path) -> bool {
    path.strip_prefix(parent)
        .is_ok_and(|relative| !relative.as_os_str().is_empty())
}

fn is_lexical_kast_homebrew_formula_prefix(
    formula_prefix: &Path,
    expected_version: &str,
) -> bool {
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
        && legacy.cli.version == legacy.plugin.version
        && !legacy.cli.version.trim().is_empty()
        && legacy.plugin.cask_token == "amichne/kast/kast-plugin"
        && !legacy.updated_at.trim().is_empty()
        && path_is_normalized_absolute(&legacy.cli.binary)
        && path_is_normalized_absolute(&legacy.cli.formula_prefix)
        && path_is_lexically_below(&legacy.cli.binary, &legacy.cli.formula_prefix)
        && is_lexical_kast_homebrew_formula_prefix(
            &legacy.cli.formula_prefix,
            &legacy.cli.version,
        );
    if !recognized {
        let mut error = CliError::new(
            "MACOS_HOMEBREW_RECEIPT_INVALID",
            format!(
                "Legacy Homebrew receipt at {} is not an exact schema-1 Kast joint receipt; it was preserved unchanged",
                path.display(),
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
    Ok(Some(migrated))
}

#[cfg(target_os = "macos")]
fn discover_running_homebrew_receipt() -> Result<Option<MacosHomebrewInstallReceipt>> {
    let running = fs::canonicalize(env::current_exe()?)?;
    let Some(formula_prefix) = running.parent().and_then(Path::parent).map(Path::to_path_buf) else {
        return Ok(None);
    };
    if !receipt_binary_is_executable(&running)
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

pub(crate) fn resolve_macos_homebrew_authority() -> MacosHomebrewAuthorityResolution {
    #[cfg(not(target_os = "macos"))]
    {
        MacosHomebrewAuthorityResolution::Absent
    }
    #[cfg(target_os = "macos")]
    {
        let path = default_macos_homebrew_receipt_path();
        if !path.is_file() {
            return match discover_running_homebrew_receipt() {
                Ok(Some(replacement)) => {
                    MacosHomebrewAuthorityResolution::Recoverable(replacement)
                }
                Ok(None) => MacosHomebrewAuthorityResolution::Absent,
                Err(error) => MacosHomebrewAuthorityResolution::Blocked(error),
            };
        }
        match read_macos_homebrew_receipt_at(&path) {
            Ok(receipt) => {
                match validate_running_macos_homebrew_receipt(&path, receipt) {
                    Ok(receipt) => MacosHomebrewAuthorityResolution::Active(receipt),
                    Err(error) => MacosHomebrewAuthorityResolution::Blocked(error),
                }
            }
            Err(strict_error) => match classify_existing_macos_homebrew_receipt_for_repair(&path) {
                Ok(
                    ExistingMacosHomebrewReceiptForRepair::StaleSchema3
                    | ExistingMacosHomebrewReceiptForRepair::LegacySchema2
                    | ExistingMacosHomebrewReceiptForRepair::LegacySchema1(_),
                ) => match discover_running_homebrew_receipt() {
                    Ok(Some(replacement)) => {
                        MacosHomebrewAuthorityResolution::Recoverable(replacement)
                    }
                    Ok(None) => MacosHomebrewAuthorityResolution::Blocked(CliError::new(
                        "MACOS_HOMEBREW_RECEIPT_BINARY_MISMATCH",
                        format!(
                            "Recognized stale Homebrew receipt state at {}, but the running Kast executable is not the exact current Cellar/kast formula binary; the receipt was preserved unchanged",
                            path.display(),
                        ),
                    )),
                    Err(error) => MacosHomebrewAuthorityResolution::Blocked(error),
                },
                Ok(ExistingMacosHomebrewReceiptForRepair::Current(_)) => {
                    MacosHomebrewAuthorityResolution::Blocked(strict_error)
                }
                Err(_) => MacosHomebrewAuthorityResolution::Blocked(strict_error),
            },
        }
    }
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
