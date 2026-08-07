#[cfg(target_os = "macos")]
#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstalledIdeaProductInfo {
    launch: Vec<InstalledIdeaProductLaunch>,
}

#[cfg(target_os = "macos")]
#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstalledIdeaProductLaunch {
    os: String,
    arch: String,
    java_executable_path: PathBuf,
    vm_options_file_path: Option<PathBuf>,
    boot_class_path_jar_names: Vec<PathBuf>,
    #[serde(default)]
    additional_jvm_arguments: Vec<String>,
    main_class: String,
}

#[cfg(target_os = "macos")]
fn installed_idea_sidecar_java_command(
    args: &DaemonStartArgs,
    config: &KastConfig,
    app: &Path,
) -> Result<Vec<String>> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
    let idea_home = app.join("Contents");
    let resources = idea_home.join("Resources");
    let product_info_path = resources.join("product-info.json");
    let product_info: InstalledIdeaProductInfo =
        serde_json::from_slice(&fs::read(&product_info_path).map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Cannot read installed IDEA product metadata at {}: {error}",
                    product_info_path.display(),
                ),
            )
        })?)
        .map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Installed IDEA product metadata is invalid at {}: {error}",
                    product_info_path.display(),
                ),
            )
        })?;
    let launch = product_info
        .launch
        .into_iter()
        .find(|launch| launch.os == "macOS" && product_arch_matches_current(&launch.arch))
        .ok_or_else(|| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "{} does not declare a macOS launch for architecture {}.",
                    product_info_path.display(),
                    env::consts::ARCH,
                ),
            )
        })?;
    let java_exec = canonical_product_file(&resources, &launch.java_executable_path, "JBR")?;
    let boot_classpath = launch
        .boot_class_path_jar_names
        .iter()
        .map(|name| canonical_product_file(&idea_home.join("lib"), name, "boot classpath"))
        .collect::<Result<Vec<_>>>()?;
    if boot_classpath.is_empty() {
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "{} does not declare an IDEA platform boot classpath.",
                product_info_path.display(),
            ),
        ));
    }
    let classpath = env::join_paths(&boot_classpath)
        .map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!("Cannot construct the installed IDEA boot classpath: {error}"),
            )
        })?
        .to_string_lossy()
        .into_owned();

    let sidecar_root = config
        .paths
        .cache_dir
        .join("idea-sidecars")
        .join(config::workspace_hash(&workspace_root));
    let idea_config = sidecar_root.join("idea-config");
    let idea_system = sidecar_root.join("idea-system");
    let idea_log = sidecar_root.join("idea-log");
    let plugins = sidecar_root.join("plugins");
    for directory in [&idea_config, &idea_system, &idea_log, &plugins] {
        fs::create_dir_all(directory).map_err(|error| {
            CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Cannot create isolated sidecar directory {}: {error}",
                    directory.display(),
                ),
            )
        })?;
    }
    preflight_installed_idea_semantic_runtime(&idea_home, &idea_system)?;
    let payload_plugin = installed_sidecar_plugin(args, config)?;
    let isolated_plugin = plugins.join("kast-indexer");
    ensure_isolated_plugin_link(&payload_plugin, &isolated_plugin)?;
    let runtime_config_file = write_runtime_config_file(args, config, None, &idea_home)?;

    let mut command = vec![java_exec.display().to_string()];
    if let Some(vm_options) = launch.vm_options_file_path.as_deref() {
        let vm_options = canonical_product_file(&resources, vm_options, "VM options")?;
        command.extend(product_jvm_arguments(
            &fs::read_to_string(&vm_options).map_err(|error| {
                CliError::new(
                    "DAEMON_START_ERROR",
                    format!(
                        "Cannot read IDEA VM options at {}: {error}",
                        vm_options.display()
                    ),
                )
            })?,
            app,
            &idea_home,
        ));
    }
    command.extend(
        launch
            .additional_jvm_arguments
            .iter()
            .map(|argument| materialize_product_argument(argument, app, &idea_home))
            .filter(|argument| !is_sidecar_managed_jvm_argument(argument)),
    );
    if let Ok(java_opts) = env::var("JAVA_OPTS") {
        command.extend(java_opts.split_whitespace().map(ToOwned::to_owned));
    }
    command.extend([
        "-Djava.awt.headless=true".to_string(),
        "-Didea.is.internal=true".to_string(),
        "-Dkast.idea.autostart=false".to_string(),
        format!("-Didea.home.path={}", idea_home.display()),
        format!(
            "-Didea.paths.selector=KastIndexer-{}",
            config::workspace_hash(&workspace_root),
        ),
        format!("-Didea.config.path={}", idea_config.display()),
        format!("-Didea.system.path={}", idea_system.display()),
        format!("-Didea.log.path={}", idea_log.display()),
        format!("-Didea.plugins.path={}", plugins.display()),
        format!("-Dkast.sidecar.plugin.path={}", isolated_plugin.display()),
        "-Dsplash=false".to_string(),
    ]);
    command.push("-cp".to_string());
    command.push(classpath);
    command.push(launch.main_class);
    command.push(INDEXER_STARTER_COMMAND.to_string());
    command.extend(config::server_launch_args(args, config)?);
    command.push(format!("--idea-home={}", idea_home.display()));
    command.push(format!(
        "--runtime-config-file={}",
        runtime_config_file.display(),
    ));
    Ok(command)
}

#[cfg(target_os = "macos")]
fn product_arch_matches_current(product_arch: &str) -> bool {
    match env::consts::ARCH {
        "aarch64" => matches!(product_arch, "aarch64" | "arm64"),
        "x86_64" => matches!(product_arch, "x86_64" | "amd64"),
        architecture => product_arch == architecture,
    }
}

#[cfg(target_os = "macos")]
fn canonical_product_file(base: &Path, path: &Path, purpose: &str) -> Result<PathBuf> {
    fs::canonicalize(base.join(path)).map_err(|error| {
        CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Installed IDEA {purpose} file is unavailable at {}: {error}",
                base.join(path).display(),
            ),
        )
    })
}

#[cfg(target_os = "macos")]
fn product_jvm_arguments(raw: &str, app: &Path, idea_home: &Path) -> Vec<String> {
    raw.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|argument| materialize_product_argument(argument, app, idea_home))
        .filter(|argument| !is_sidecar_managed_jvm_argument(argument))
        .collect()
}

#[cfg(target_os = "macos")]
fn materialize_product_argument(argument: &str, app: &Path, idea_home: &Path) -> String {
    argument
        .replace("$APP_PACKAGE", &app.display().to_string())
        .replace("$IDE_HOME", &idea_home.display().to_string())
        .replace("%IDE_HOME%", &idea_home.display().to_string())
}

#[cfg(target_os = "macos")]
fn is_sidecar_managed_jvm_argument(argument: &str) -> bool {
    [
        "-Djava.awt.headless=",
        "-Didea.home.path=",
        "-Didea.paths.selector=",
        "-Didea.config.path=",
        "-Didea.system.path=",
        "-Didea.log.path=",
        "-Didea.plugins.path=",
        "-Dkast.sidecar.plugin.path=",
        "-Dsplash=",
    ]
    .iter()
    .any(|prefix| argument.starts_with(prefix))
}

#[cfg(target_os = "macos")]
fn installed_sidecar_plugin(args: &DaemonStartArgs, config: &KastConfig) -> Result<PathBuf> {
    let conventional_home = config
        .paths
        .install_root
        .join("current/lib/backends/indexer/current/idea-home");
    let mut homes = Vec::new();
    if let Some(home) = &args.idea_home {
        homes.push(home.clone());
    }
    if let Some(home) = &config.indexer.host_home {
        homes.push(home.clone());
    }
    homes.push(conventional_home.clone());
    for home in homes {
        let plugin = home.join("plugins/kast-indexer");
        if plugin.join("lib").is_dir()
            && fs::read_dir(plugin.join("lib")).is_ok_and(|entries| {
                entries
                    .filter_map(std::result::Result::ok)
                    .any(|entry| entry.path().extension().is_some_and(|value| value == "jar"))
            })
        {
            return Ok(plugin);
        }
    }
    Err(CliError::new(
        "DAEMON_START_ERROR",
        format!(
            "The private Kast indexer payload is unavailable under {}. Run `kast setup` for the active release.",
            conventional_home.display(),
        ),
    ))
}

#[cfg(target_os = "macos")]
fn ensure_isolated_plugin_link(source: &Path, target: &Path) -> Result<()> {
    let source = fs::canonicalize(source).map_err(|error| {
        CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Cannot resolve sidecar plugin {}: {error}",
                source.display()
            ),
        )
    })?;
    let target_metadata = match target.symlink_metadata() {
        Ok(metadata) => Some(metadata),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
        Err(error) => {
            return Err(CliError::new(
                "DAEMON_START_ERROR",
                format!(
                    "Cannot inspect isolated sidecar plugin path {}: {error}",
                    target.display(),
                ),
            ));
        }
    };
    if target_metadata
        .as_ref()
        .is_some_and(|metadata| !metadata.file_type().is_symlink())
    {
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "The isolated sidecar plugin path {} is not a Kast-owned symlink.",
                target.display(),
            ),
        ));
    }
    if target_metadata.is_some()
        && fs::canonicalize(target).is_ok_and(|existing| existing == source)
    {
        return Ok(());
    }

    let temporary = target.with_file_name(format!(".kast-indexer-{}.tmp", uuid::Uuid::new_v4(),));
    std::os::unix::fs::symlink(&source, &temporary).map_err(|error| {
        CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Cannot stage sidecar plugin link {} to {}: {error}",
                temporary.display(),
                source.display(),
            ),
        )
    })?;
    if let Err(error) = fs::rename(&temporary, target) {
        let _ = fs::remove_file(&temporary);
        return Err(CliError::new(
            "DAEMON_START_ERROR",
            format!(
                "Cannot activate sidecar plugin link {} to {}: {error}",
                target.display(),
                source.display(),
            ),
        ));
    }
    Ok(())
}
