#[derive(Debug)]
struct HomebrewContext {
    brew_prefix: PathBuf,
    formula_prefix: PathBuf,
    cli_path: PathBuf,
}

#[derive(Debug)]
struct JetBrainsPluginDir {
    product: String,
    year: u32,
    minor: u32,
    patch: u32,
    path: PathBuf,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum HomebrewCaskInstallAction {
    Install,
    Reinstall,
}

impl HomebrewCaskInstallAction {
    fn for_installed_cask(installed: bool) -> Self {
        if installed {
            Self::Reinstall
        } else {
            Self::Install
        }
    }

    fn as_brew_arg(self) -> &'static str {
        match self {
            Self::Install => "install",
            Self::Reinstall => "reinstall",
        }
    }

    fn completion_label(self) -> &'static str {
        match self {
            Self::Install => "Homebrew install complete",
            Self::Reinstall => "Homebrew reinstall complete",
        }
    }

    fn failure_label(self) -> &'static str {
        match self {
            Self::Install => "Homebrew install failed",
            Self::Reinstall => "Homebrew reinstall failed",
        }
    }

    fn brew_args(self, cask_token: &str, force: bool) -> Vec<String> {
        let mut args = vec![
            self.as_brew_arg().to_string(),
            "--cask".to_string(),
            cask_token.to_string(),
        ];
        if force {
            args.insert(2, "--force".to_string());
        }
        args
    }
}

fn run_reported_step<T>(
    reporter: &mut dyn InstallReporter,
    started: &str,
    finished: impl FnOnce(&T) -> String,
    failed: &str,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    reporter.idea_plugin_step_started(started)?;
    match action() {
        Ok(value) => {
            reporter.idea_plugin_step_finished(&finished(&value))?;
            Ok(value)
        }
        Err(error) => {
            reporter.idea_plugin_step_failed(failed)?;
            Err(error)
        }
    }
}

fn brew_command_display(args: &[String]) -> String {
    format!("brew {}", args.join(" "))
}

fn jetbrains_profile_count_label(count: usize) -> String {
    match count {
        1 => "1 JetBrains profile".to_string(),
        count => format!("{count} JetBrains profiles"),
    }
}

fn discover_homebrew_context() -> Result<HomebrewContext> {
    let brew_prefix = homebrew_prefix(&["--prefix"])?;
    let formula_prefix = homebrew_prefix(&["--prefix", KAST_FORMULA_NAME])?;
    let cli_path = env::current_exe()?;
    Ok(HomebrewContext {
        brew_prefix,
        formula_prefix,
        cli_path,
    })
}

fn verify_homebrew_cli(homebrew: &HomebrewContext) -> Result<()> {
    if path_is_below_homebrew_formula(&homebrew.cli_path, &homebrew.formula_prefix) {
        return Ok(());
    }
    let mut error = CliError::new(
        "HOMEBREW_INSTALL_REQUIRED",
        format!(
            "`kast machine plugin` must be run from the Homebrew-installed kast binary under {}",
            homebrew.formula_prefix.display()
        ),
    );
    error.details.insert(
        "cliPath".to_string(),
        homebrew.cli_path.display().to_string(),
    );
    error.details.insert(
        "formulaPrefix".to_string(),
        homebrew.formula_prefix.display().to_string(),
    );
    Err(error)
}

fn homebrew_prefix(args: &[&str]) -> Result<PathBuf> {
    let output = run_brew(args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_PREFIX_FAILED",
            "Homebrew did not report the expected install prefix",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let prefix = stdout.trim();
    if prefix.is_empty() {
        return Err(CliError::new(
            "HOMEBREW_PREFIX_FAILED",
            "Homebrew returned an empty install prefix",
        ));
    }
    Ok(PathBuf::from(prefix))
}

fn homebrew_formula_tap() -> Result<String> {
    let args = ["info", "--json=v2", KAST_FORMULA_NAME];
    let output = run_brew(&args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_TAP_LOOKUP_FAILED",
            "Homebrew did not report metadata for the kast formula",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    parse_homebrew_formula_tap(&stdout).ok_or_else(|| {
        CliError::new(
            "HOMEBREW_TAP_LOOKUP_FAILED",
            "Homebrew metadata did not include a tap for the kast formula",
        )
    })
}

fn parse_homebrew_formula_tap(json: &str) -> Option<String> {
    let value: Value = serde_json::from_str(json).ok()?;
    value
        .get("formulae")?
        .as_array()?
        .first()?
        .get("tap")?
        .as_str()
        .map(str::trim)
        .filter(|tap| !tap.is_empty())
        .map(str::to_string)
}

fn homebrew_cask_download_plan(
    cask_token: &str,
    plugin_directories: &[PathBuf],
) -> Result<IdeaPluginDownloadPlan> {
    Ok(IdeaPluginDownloadPlan {
        cask_token: cask_token.to_string(),
        plugin_version: homebrew_cask_metadata(cask_token)?.plugin_version,
        download_cache: homebrew_cask_cache_path(cask_token)?,
        plugin_directories: plugin_directories.to_vec(),
    })
}

struct HomebrewCaskMetadata {
    plugin_version: String,
}

fn homebrew_cask_metadata(cask_token: &str) -> Result<HomebrewCaskMetadata> {
    let args = ["info", "--json=v2", "--cask", cask_token];
    let output = run_brew(&args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_CASK_METADATA_FAILED",
            "Homebrew did not report metadata for the Kast IDEA plugin cask",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    parse_homebrew_cask_metadata(&stdout).ok_or_else(|| {
        CliError::new(
            "HOMEBREW_CASK_METADATA_FAILED",
            "Homebrew cask metadata did not include a plugin version.",
        )
    })
}

fn parse_homebrew_cask_metadata(json: &str) -> Option<HomebrewCaskMetadata> {
    let value: Value = serde_json::from_str(json).ok()?;
    let version = value
        .get("casks")?
        .as_array()?
        .first()?
        .get("version")?
        .as_str()?
        .trim();
    (!version.is_empty()).then(|| HomebrewCaskMetadata {
        plugin_version: version.to_string(),
    })
}

fn homebrew_cask_cache_path(cask_token: &str) -> Result<PathBuf> {
    let args = ["--cache", "--cask", cask_token];
    let output = run_brew(&args)?;
    if !output.status.success() {
        return Err(command_error(
            "HOMEBREW_CASK_CACHE_FAILED",
            "Homebrew did not report the Kast IDEA plugin cask cache path",
            &args
                .iter()
                .map(|value| value.to_string())
                .collect::<Vec<_>>(),
            &output,
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let path = stdout.trim();
    if path.is_empty() {
        return Err(CliError::new(
            "HOMEBREW_CASK_CACHE_FAILED",
            "Homebrew returned an empty cask cache path.",
        ));
    }
    Ok(PathBuf::from(path))
}

fn prefetch_homebrew_cask(
    cask_token: &str,
    force: bool,
    download_cache: &Path,
    reporter: &mut dyn InstallReporter,
) -> Result<u64> {
    let mut args = vec!["fetch".to_string(), "--cask".to_string()];
    if force {
        args.push("--force".to_string());
    }
    args.push(cask_token.to_string());

    reporter.idea_plugin_step_started(&format!(
        "Fetching Homebrew cask ({})",
        brew_command_display(&args)
    ))?;
    let mut child = match ProcessCommand::new("brew")
        .args(&args)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(error) => {
            reporter.idea_plugin_step_failed("Could not start Homebrew fetch")?;
            return Err(CliError::new(
                "HOMEBREW_NOT_FOUND",
                format!("Unable to run `brew`: {error}"),
            ));
        }
    };

    loop {
        let status = match child.try_wait() {
            Ok(Some(status)) => status,
            Ok(None) => {
                reporter.idea_plugin_download_progress(file_size(download_cache).unwrap_or(0))?;
                thread::sleep(Duration::from_millis(100));
                continue;
            }
            Err(error) => {
                reporter.idea_plugin_step_failed("Could not monitor Homebrew fetch")?;
                return Err(error.into());
            }
        };
        if status.success() {
            let downloaded_bytes = file_size(download_cache).unwrap_or(0);
            reporter.idea_plugin_download_progress(downloaded_bytes)?;
            reporter.idea_plugin_download_finished(downloaded_bytes)?;
            return Ok(downloaded_bytes);
        }
        reporter.idea_plugin_step_failed("Homebrew cask fetch failed")?;
        let mut error = CliError::new(
            "HOMEBREW_CASK_FETCH_FAILED",
            "Homebrew failed to fetch the Kast IDEA plugin cask.",
        );
        error
            .details
            .insert("command".to_string(), brew_command_display(&args));
        return Err(error);
    }
}

fn file_size(path: &Path) -> Option<u64> {
    fs::metadata(path).ok().map(|metadata| metadata.len())
}

fn homebrew_cask_installed(cask_name: &str) -> Result<bool> {
    let output = run_brew(&["list", "--cask", cask_name])?;
    Ok(output.status.success())
}

fn homebrew_cask_version(cask_name: &str) -> Result<Option<String>> {
    let output = run_brew(&["list", "--cask", "--versions", cask_name])?;
    if !output.status.success() {
        return Ok(None);
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    Ok(parse_homebrew_cask_version(&stdout, cask_name))
}

fn parse_homebrew_cask_version(output: &str, cask_name: &str) -> Option<String> {
    output.lines().find_map(|line| {
        let mut parts = line.split_whitespace();
        let name = parts.next()?;
        if name != cask_name {
            return None;
        }
        let version = parts.collect::<Vec<_>>().join(" ");
        (!version.trim().is_empty()).then_some(version)
    })
}

fn run_brew(args: &[&str]) -> Result<Output> {
    let mut command = ProcessCommand::new("brew");
    command.args(args);
    command.output().map_err(|error| {
        CliError::new(
            "HOMEBREW_NOT_FOUND",
            format!("Unable to run `brew`: {error}"),
        )
    })
}

fn run_brew_with_jetbrains_root(args: &[String], jetbrains_config_root: &Path) -> Result<Output> {
    let mut command = ProcessCommand::new("brew");
    command
        .args(args)
        .env("KAST_JETBRAINS_CONFIG_ROOT", jetbrains_config_root);
    command.output().map_err(|error| {
        CliError::new(
            "HOMEBREW_NOT_FOUND",
            format!("Unable to run `brew`: {error}"),
        )
    })
}

fn command_error(code: &'static str, message: &str, args: &[String], output: &Output) -> CliError {
    let mut error = CliError::new(code, message);
    error
        .details
        .insert("command".to_string(), format!("brew {}", args.join(" ")));
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if !stdout.is_empty() {
        error.details.insert("stdout".to_string(), stdout);
    }
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if !stderr.is_empty() {
        error.details.insert("stderr".to_string(), stderr);
    }
    error
}

fn path_is_below_homebrew_formula(path: &Path, formula_prefix: &Path) -> bool {
    let canonical_path = fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    let canonical_formula_prefix =
        fs::canonicalize(formula_prefix).unwrap_or_else(|_| formula_prefix.to_path_buf());
    canonical_path.starts_with(&canonical_formula_prefix) || path.starts_with(formula_prefix)
}

fn default_jetbrains_config_root() -> PathBuf {
    config::home_dir().join("Library/Application Support/JetBrains")
}

fn jetbrains_plugin_dirs(root: &Path) -> Result<Vec<PathBuf>> {
    if !root.is_dir() {
        return Ok(vec![]);
    }
    let mut dirs = vec![];
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        let Some((product, year, minor, patch)) = parse_jetbrains_profile_name(name) else {
            continue;
        };
        dirs.push(JetBrainsPluginDir {
            product,
            year,
            minor,
            patch,
            path: path.join("plugins"),
        });
    }
    dirs.sort_by(|left, right| {
        left.product
            .cmp(&right.product)
            .then_with(|| right.year.cmp(&left.year))
            .then_with(|| right.minor.cmp(&left.minor))
            .then_with(|| right.patch.cmp(&left.patch))
            .then_with(|| {
                left.path
                    .display()
                    .to_string()
                    .cmp(&right.path.display().to_string())
            })
    });
    Ok(dirs.into_iter().map(|dir| dir.path).collect())
}

#[cfg(target_os = "macos")]
pub(crate) fn latest_jetbrains_ide_app_name() -> Result<Option<String>> {
    let jetbrains_config_root = env::var_os("KAST_JETBRAINS_CONFIG_ROOT")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(config::normalize)
        .unwrap_or_else(default_jetbrains_config_root);
    latest_jetbrains_ide_app_name_under(&jetbrains_config_root)
}

#[cfg(any(target_os = "macos", test))]
fn latest_jetbrains_ide_app_name_under(root: &Path) -> Result<Option<String>> {
    if !root.is_dir() {
        return Ok(None);
    }
    let mut candidates = vec![];
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|name| name.to_str()) else {
            continue;
        };
        let Some((product, year, minor, patch)) = parse_jetbrains_profile_name(name) else {
            continue;
        };
        let Some(app_name) = jetbrains_profile_app_name(&product) else {
            continue;
        };
        candidates.push((
            jetbrains_app_preference(&product),
            year,
            minor,
            patch,
            app_name,
        ));
    }
    candidates.sort_by(|left, right| {
        left.0
            .cmp(&right.0)
            .then_with(|| right.1.cmp(&left.1))
            .then_with(|| right.2.cmp(&left.2))
            .then_with(|| right.3.cmp(&left.3))
            .then_with(|| left.4.cmp(right.4))
    });
    Ok(candidates
        .first()
        .map(|(_, _, _, _, app_name)| app_name.to_string()))
}

#[cfg(any(target_os = "macos", test))]
fn jetbrains_profile_app_name(product: &str) -> Option<&'static str> {
    match product {
        "IntelliJIdea" => Some("IntelliJ IDEA"),
        "AndroidStudio" => Some("Android Studio"),
        _ => None,
    }
}

#[cfg(any(target_os = "macos", test))]
fn jetbrains_app_preference(product: &str) -> u8 {
    match product {
        "IntelliJIdea" => 0,
        "AndroidStudio" => 1,
        _ => 2,
    }
}

fn parse_jetbrains_profile_name(name: &str) -> Option<(String, u32, u32, u32)> {
    let (version_start, _) = name.char_indices().find(|(_, ch)| ch.is_ascii_digit())?;
    let product = &name[..version_start];
    let first = product.chars().next()?;
    if !first.is_ascii_alphabetic() || !product.chars().all(|ch| ch.is_ascii_alphanumeric()) {
        return None;
    }
    let rest = &name[version_start..];
    let mut parts = rest.split('.');
    let year = parse_fixed_digits(parts.next()?, 4)?;
    let minor = parse_digits(parts.next()?)?;
    let patch = match parts.next() {
        Some(value) => parse_digits(value)?,
        None => 0,
    };
    if parts.next().is_some() {
        return None;
    }
    Some((product.to_string(), year, minor, patch))
}

fn parse_fixed_digits(value: &str, len: usize) -> Option<u32> {
    if value.len() != len {
        return None;
    }
    parse_digits(value)
}

fn parse_digits(value: &str) -> Option<u32> {
    if value.is_empty() || !value.chars().all(|ch| ch.is_ascii_digit()) {
        return None;
    }
    value.parse().ok()
}

fn cask_name(cask_token: &str) -> String {
    cask_token
        .rsplit('/')
        .next()
        .unwrap_or(cask_token)
        .to_string()
}
