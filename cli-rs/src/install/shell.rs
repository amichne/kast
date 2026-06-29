pub fn install_shell(args: ShellInstallArgs) -> Result<InstallShellResult> {
    let config = config::KastConfig::load_global()?;
    let config_home = config::kast_config_home();
    let shell = args.shell.map(Ok).unwrap_or_else(detect_shell)?;
    let command_name = args
        .command_name
        .unwrap_or_else(default_shell_command_name)
        .trim()
        .to_string();
    validate_shell_command_name(&command_name)?;
    let bin_dir = shell_integration_bin_dir(&command_name, &config.paths.bin_dir)?;
    let source_file = args.source_file.map(config::normalize).unwrap_or_else(|| {
        config_home
            .join("shell")
            .join(format!("{command_name}.{}", shell.extension()))
    });
    let profile = args
        .profile
        .map(config::normalize)
        .unwrap_or_else(|| default_shell_profile(shell));
    let source_line = format!("source {}", shell_quote_path(&source_file));
    let source_content = shell_source_content(shell, &command_name, &bin_dir, &config_home);

    if !args.dry_run {
        if let Some(parent) = source_file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&source_file, source_content)?;
    }
    let profile_updated = patch_shell_profile(&profile, &source_line, args.dry_run)?;

    Ok(InstallShellResult {
        shell: shell.canonical().to_string(),
        command_name,
        bin_dir: bin_dir.display().to_string(),
        config_home: config_home.display().to_string(),
        source_file: source_file.display().to_string(),
        profile: profile.display().to_string(),
        profile_updated,
        dry_run: args.dry_run,
        source_line,
        schema_version: SCHEMA_VERSION,
    })
}

fn detect_shell() -> Result<ShellKind> {
    let shell = env::var_os("SHELL")
        .and_then(|value| PathBuf::from(value).file_name().map(|name| name.to_owned()))
        .and_then(|name| name.to_str().map(str::to_string))
        .unwrap_or_default();
    match shell.as_str() {
        "bash" => Ok(ShellKind::Bash),
        "zsh" => Ok(ShellKind::Zsh),
        _ => Err(CliError::new(
            "CLI_USAGE",
            "Could not infer a supported shell from SHELL. Pass `kast machine shell --shell bash` or `--shell zsh`.",
        )),
    }
}

fn default_shell_command_name() -> String {
    env::current_exe()
        .ok()
        .and_then(|path| {
            path.file_name()
                .and_then(|name| name.to_str())
                .map(str::to_string)
        })
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "kast".to_string())
}

fn shell_integration_bin_dir(command_name: &str, configured_bin_dir: &Path) -> Result<PathBuf> {
    let current_exe = env::current_exe()?;
    if current_exe
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == command_name)
        && let Some(parent) = current_exe.parent()
    {
        return Ok(parent.to_path_buf());
    }
    Ok(resolve_command_bin_dir(command_name)?.unwrap_or_else(|| configured_bin_dir.to_path_buf()))
}

fn default_shell_profile(shell: ShellKind) -> PathBuf {
    match shell {
        ShellKind::Bash => config::home_dir().join(".bashrc"),
        ShellKind::Zsh => config::home_dir().join(".zshrc"),
    }
}

fn validate_shell_command_name(command_name: &str) -> Result<()> {
    let valid = !command_name.is_empty()
        && command_name
            .chars()
            .all(|char| char.is_ascii_alphanumeric() || matches!(char, '-' | '_' | '.' | '+'));
    if valid {
        return Ok(());
    }
    let mut error = CliError::new(
        "CLI_USAGE",
        "Shell command name must contain only ASCII letters, digits, dash, underscore, dot, or plus.",
    );
    error
        .details
        .insert("commandName".to_string(), command_name.to_string());
    Err(error)
}

fn shell_source_content(
    shell: ShellKind,
    command_name: &str,
    bin_dir: &Path,
    config_home: &Path,
) -> String {
    format!(
        r#"# Managed by `kast machine shell`; re-run that command after moving Kast.
export KAST_CONFIG_HOME={}
_kast_bin_dir={}
case ":${{PATH}}:" in
  *":${{_kast_bin_dir}}:"*) ;;
  *) export PATH="${{_kast_bin_dir}}:${{PATH}}" ;;
esac
unset _kast_bin_dir

if command -v {command_name} >/dev/null 2>&1; then
  source <({command_name} machine completion {} --command-name {command_name})
fi
"#,
        shell_quote(&config_home.display().to_string()),
        shell_quote(&bin_dir.display().to_string()),
        shell.canonical(),
    )
}

fn patch_shell_profile(profile: &Path, source_line: &str, dry_run: bool) -> Result<bool> {
    let block = format!(
        "{SHELL_BLOCK_START}\n# Managed by `kast machine shell`; edit the generated source file instead.\n{source_line}\n{SHELL_BLOCK_END}\n"
    );
    let original = match fs::read_to_string(profile) {
        Ok(content) => content,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error.into()),
    };
    let updated = replace_managed_block(&original, &block);
    if updated == original {
        return Ok(false);
    }
    if !dry_run {
        if let Some(parent) = profile.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(profile, updated)?;
    }
    Ok(true)
}

fn replace_managed_block(original: &str, block: &str) -> String {
    replace_managed_block_with_markers(original, block, SHELL_BLOCK_START, SHELL_BLOCK_END)
}

fn replace_managed_block_with_markers(
    original: &str,
    block: &str,
    start_marker: &str,
    end_marker: &str,
) -> String {
    if let Some(start) = original.find(start_marker)
        && let Some(end_offset) = original[start..].find(end_marker)
    {
        let end = start + end_offset + end_marker.len();
        let mut updated = String::new();
        updated.push_str(&original[..start]);
        updated.push_str(block);
        updated.push_str(original[end..].trim_start_matches('\n'));
        return updated;
    }
    let mut updated = original.to_string();
    if !updated.is_empty() && !updated.ends_with('\n') {
        updated.push('\n');
    }
    if !updated.is_empty() {
        updated.push('\n');
    }
    updated.push_str(block);
    updated
}

fn shell_quote_path(path: &Path) -> String {
    shell_quote(&path.display().to_string())
}

fn shell_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}
