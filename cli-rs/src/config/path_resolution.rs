pub fn init_config() -> Result<PathBuf> {
    let config_file = global_config_path();
    if !config_file.exists() {
        if let Some(parent) = config_file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&config_file, default_config_template()?)?;
    }
    Ok(config_file)
}

pub fn default_config_template() -> Result<String> {
    Ok(String::new())
}

pub fn path_resolution_report(
    config: &KastConfig,
    workspace_root: Option<&Path>,
    mode: PathResolutionMode,
) -> Result<PathResolutionReport> {
    let install_manifest = manifest::default_install_manifest_path();
    let install_manifest_exists = install_manifest.is_file();
    let global_config = global_config_path();
    let workspace_config = workspace_root
        .map(workspace_data_directory)
        .transpose()?
        .map(|workspace_dir| workspace_dir.join("config.toml"));
    let global_keys = config_keys(&global_config)?;
    let workspace_keys = workspace_config
        .as_deref()
        .map(config_keys)
        .transpose()?
        .unwrap_or_default();
    let mut warnings = vec![];
    for key in global_keys
        .iter()
        .chain(workspace_keys.iter())
        .filter(|key| install_owned_config_key(key))
    {
        warnings.push(format!(
            "config key {key} is ignored; install-owned paths are resolved from install.json"
        ));
    }
    if mode == PathResolutionMode::Idea {
        for key in workspace_keys
            .iter()
            .filter(|key| idea_ignored_workspace_key(key))
        {
            warnings.push(format!(
                "workspace {key} is ignored by IDEA; global/default path resolution is used"
            ));
        }
    }
    let entry_context =
        PathResolutionEntryContext::from_environment(workspace_root, install_manifest_exists);
    let entries = path_resolution_entries(config, mode, entry_context);
    Ok(PathResolutionReport {
        root: config.paths.install_root.display().to_string(),
        config_files: config_files(install_manifest, global_config, workspace_config),
        entries,
        warnings,
        schema_version: SCHEMA_VERSION,
    })
}

fn path_resolution_entries(
    config: &KastConfig,
    mode: PathResolutionMode,
    context: PathResolutionEntryContext,
) -> Vec<PathResolutionEntry> {
    let mut entries = vec![
        path_entry(
            "paths.installRoot",
            &config.paths.install_root,
            "directory",
            None,
            context.install_root_source,
            mode,
        ),
        path_entry(
            "paths.binDir",
            &config.paths.bin_dir,
            "directory",
            None,
            context.bin_dir_source,
            mode,
        ),
        path_entry(
            "paths.libDir",
            &config.paths.lib_dir,
            "directory",
            Some("paths.installRoot"),
            context.install_root_source,
            mode,
        ),
        path_entry(
            "paths.cacheDir",
            &config.paths.cache_dir,
            "directory",
            None,
            context.cache_dir_source,
            mode,
        ),
        path_entry(
            "paths.logsDir",
            &config.paths.logs_dir,
            "directory",
            context.logs_dir_parent,
            context.logs_dir_source,
            mode,
        ),
        path_entry(
            "paths.runtimeDir",
            &config.paths.runtime_dir,
            "directory",
            context.runtime_dir_parent,
            context.runtime_dir_source,
            mode,
        ),
        path_entry(
            "paths.descriptorDir",
            &config.paths.descriptor_dir,
            "directory",
            context.workspace_state_parent,
            context.workspace_state_source,
            mode,
        ),
        path_entry(
            "paths.socketDir",
            &config.paths.socket_dir,
            "directory",
            context.workspace_state_parent,
            context.workspace_state_source,
            mode,
        ),
        path_entry(
            "cli.binaryPath",
            &config.cli.binary_path,
            "file",
            Some("paths.binDir"),
            context.bin_dir_source,
            mode,
        ),
    ];
    if let Some(runtime_libs_dir) = &config.backends.headless.runtime_libs_dir {
        entries.push(path_entry(
            "backends.headless.runtimeLibsDir",
            runtime_libs_dir,
            "directory",
            Some("paths.libDir"),
            context.install_root_source,
            mode,
        ));
        entries.push(path_entry(
            "backends.headless.runtimeLibsClasspath",
            &runtime_libs_dir.join("classpath.txt"),
            "file",
            Some("backends.headless.runtimeLibsDir"),
            context.install_root_source,
            mode,
        ));
    }
    if let Some(idea_home) = &config.backends.headless.idea_home {
        entries.push(path_entry(
            "backends.headless.ideaHome",
            idea_home,
            "directory",
            None,
            PathResolutionSource::Manifest,
            mode,
        ));
    }
    entries
}

fn env_present(env_key: &str) -> bool {
    env_value_present(env::var_os(env_key))
}

fn env_value_present(value: Option<std::ffi::OsString>) -> bool {
    value.is_some_and(|value| !value.is_empty())
}

fn source_for_manifest_or_env_state(
    install_manifest_exists: bool,
    env_present: bool,
) -> PathResolutionSource {
    if install_manifest_exists {
        PathResolutionSource::Manifest
    } else if env_present {
        PathResolutionSource::Env
    } else {
        PathResolutionSource::Default
    }
}

fn path_entry(
    key: &str,
    path: &Path,
    expected_kind: &str,
    derived_from: Option<&str>,
    source: PathResolutionSource,
    _mode: PathResolutionMode,
) -> PathResolutionEntry {
    PathResolutionEntry {
        key: key.to_string(),
        value: path.display().to_string(),
        source,
        owner: path_owner(key).to_string(),
        derived_from: derived_from.map(str::to_string),
        exists: match expected_kind {
            "file" => path.is_file(),
            "directory" => path.is_dir(),
            _ => path.exists(),
        },
        expected_kind: expected_kind.to_string(),
        used_by_idea: idea_uses_path(key),
    }
}

fn config_files(
    install_manifest: PathBuf,
    global_config: PathBuf,
    workspace_config: Option<PathBuf>,
) -> Vec<PathResolutionConfigFile> {
    let mut files = vec![
        PathResolutionConfigFile {
            scope: "install-manifest".to_string(),
            exists: install_manifest.is_file(),
            path: install_manifest.display().to_string(),
        },
        PathResolutionConfigFile {
            scope: "global".to_string(),
            exists: global_config.is_file(),
            path: global_config.display().to_string(),
        },
    ];
    if let Some(workspace_config) = workspace_config {
        files.push(PathResolutionConfigFile {
            scope: "workspace".to_string(),
            exists: workspace_config.is_file(),
            path: workspace_config.display().to_string(),
        });
    }
    files
}

fn path_owner(key: &str) -> &'static str {
    match key {
        "cli.binaryPath" => "install",
        key if key.starts_with("paths.") => "install",
        key if key.starts_with("backends.headless.") => "install",
        _ => "runtime",
    }
}

fn idea_uses_path(key: &str) -> bool {
    matches!(
        key,
        "paths.installRoot"
            | "paths.binDir"
            | "paths.cacheDir"
            | "paths.logsDir"
            | "paths.runtimeDir"
            | "paths.descriptorDir"
            | "cli.binaryPath"
    )
}

fn idea_ignored_workspace_key(key: &str) -> bool {
    let key = normalize_config_key(key);
    install_owned_config_key(&key)
}

fn install_owned_config_key(key: &str) -> bool {
    let key = normalize_config_key(key);
    key.starts_with("paths.")
        || key == "cli.binarypath"
        || key.starts_with("install.")
        || key == "backends.headless.runtimelibsdir"
        || key == "backends.headless.ideahome"
}

fn config_keys(path: &Path) -> Result<BTreeSet<String>> {
    if !path.is_file() {
        return Ok(BTreeSet::new());
    }
    let value: toml::Value = match toml::from_str(&fs::read_to_string(path)?) {
        Ok(value) => value,
        Err(_) => return Ok(BTreeSet::new()),
    };
    let mut keys = BTreeSet::new();
    collect_config_keys("", &value, &mut keys);
    Ok(keys)
}

fn collect_config_keys(prefix: &str, value: &toml::Value, keys: &mut BTreeSet<String>) {
    match value {
        toml::Value::Table(table) => {
            for (key, value) in table {
                let next_prefix = if prefix.is_empty() {
                    key.to_string()
                } else {
                    format!("{prefix}.{key}")
                };
                collect_config_keys(&next_prefix, value, keys);
            }
        }
        _ => {
            keys.insert(normalize_config_key(prefix));
        }
    }
}

fn normalize_config_key(key: &str) -> String {
    key.split('.')
        .map(|segment| {
            segment
                .chars()
                .filter(|char| *char != '-' && *char != '_')
                .collect::<String>()
                .to_ascii_lowercase()
        })
        .collect::<Vec<_>>()
        .join(".")
}
