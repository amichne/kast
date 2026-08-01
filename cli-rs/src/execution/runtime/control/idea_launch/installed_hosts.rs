#[cfg(target_os = "macos")]
fn installed_idea_apps() -> Vec<PathBuf> {
    let mut candidates = idea_apps_in(Path::new("/Applications"), 1);
    if let Some(home) = std::env::var_os("HOME") {
        let home = PathBuf::from(home);
        candidates.extend(idea_apps_in(&home.join("Applications"), 1));
        candidates.extend(idea_apps_in(
            &home.join("Library/Application Support/JetBrains/Toolbox/apps"),
            7,
        ));
    }
    let mut installed = candidates
        .into_iter()
        .filter(|candidate| candidate.is_dir())
        .collect::<Vec<_>>();
    installed.sort();
    installed.dedup();
    installed
}

#[cfg(target_os = "macos")]
fn select_supported_idea_app(installed: Vec<PathBuf>) -> Result<PathBuf> {
    let supported = installed
        .iter()
        .filter(|candidate| is_supported_idea_app(candidate))
        .cloned()
        .collect::<Vec<_>>();
    match supported.as_slice() {
        [app] => Ok(app.clone()),
        [] if !installed.is_empty() => Err(CliError::new(
            "IDEA_VERSION_UNSUPPORTED",
            "Kast requires IntelliJ IDEA 2026.2/build 262 or Android Studio 2026.1.2/build 261; no supported installed bundle was found.",
        )),
        [] => Err(CliError::new(
            "IDEA_HOST_NOT_FOUND",
            "Kast could not find IntelliJ IDEA 2026.2 or Android Studio 2026.1.2. Install one or set runtime.ideaLaunch.command to its exact application bundle.",
        )),
        _ => Err(CliError::new(
            "IDEA_HOST_AMBIGUOUS",
            "Multiple supported IntelliJ IDEA or Android Studio application bundles were found; set runtime.ideaLaunch.command to the exact bundle.",
        )),
    }
}

#[cfg(target_os = "macos")]
fn resolve_explicit_idea_app(command: &Path) -> Result<PathBuf> {
    let canonical = fs::canonicalize(command).map_err(|error| {
        CliError::new(
            "IDEA_HOST_NOT_FOUND",
            format!(
                "Configured IDEA application could not be resolved at {}: {error}",
                command.display()
            ),
        )
    })?;
    let app = canonical
        .ancestors()
        .find(|ancestor| ancestor.extension().is_some_and(|extension| extension == "app"))
        .map(Path::to_path_buf)
        .ok_or_else(|| {
            CliError::new(
                "IDEA_LAUNCH_CONFIG_INVALID",
                "runtime.ideaLaunch.command must resolve inside an IntelliJ IDEA or Android Studio .app bundle on macOS.",
            )
        })?;
    ensure_supported_idea_app(&app)?;
    Ok(app)
}

#[cfg(target_os = "macos")]
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IdeaProductInfo {
    product_code: String,
    data_directory_name: String,
}

#[cfg(target_os = "macos")]
fn require_current_plugin_for_app(app: &Path, config: &KastConfig) -> Result<()> {
    let home = std::env::var_os("HOME").map(PathBuf::from).ok_or_else(|| {
        CliError::new(
            "IDEA_PLUGIN_UPDATE_REQUIRED",
            "HOME is unavailable, so Kast cannot verify the selected IDE plugin profile.",
        )
    })?;
    let installed_plugin = idea_plugin_directory_for_app(app, &home)?;
    let plugin_archive = config.paths.install_root.join("current/idea/kast.zip");
    if crate::install::idea_plugin_directory_matches_archive(&installed_plugin, &plugin_archive)
        .unwrap_or(false)
    {
        return Ok(());
    }
    let mut error = CliError::new(
        "IDEA_PLUGIN_UPDATE_REQUIRED",
        format!(
            "The Kast plugin for {} is missing or differs from the active Kast release. Run `kast setup`; close and relaunch only this application if setup requests it.",
            app.display(),
        ),
    );
    error.details.insert(
        "ideaPluginDirectory".to_string(),
        installed_plugin.display().to_string(),
    );
    Err(error)
}

#[cfg(target_os = "macos")]
fn idea_plugin_directory_for_app(app: &Path, home: &Path) -> Result<PathBuf> {
    let product = fs::read_to_string(app.join("Contents/Resources/product-info.json"))
        .ok()
        .and_then(|raw| serde_json::from_str::<IdeaProductInfo>(&raw).ok())
        .ok_or_else(|| {
            CliError::new(
                "IDEA_PLUGIN_UPDATE_REQUIRED",
                format!(
                    "Cannot determine the plugin profile for {}. Run Kast setup for this application before opening the project.",
                    app.display(),
                ),
            )
        })?;
    let vendor = if product.product_code == "AI" {
        "Google"
    } else {
        "JetBrains"
    };
    Ok(home
        .join("Library/Application Support")
        .join(vendor)
        .join(&product.data_directory_name)
        .join("plugins/kast"))
}

#[cfg(target_os = "macos")]
fn idea_apps_in(root: &Path, depth: usize) -> Vec<PathBuf> {
    if depth == 0 {
        return vec![];
    }
    let Ok(entries) = fs::read_dir(root) else {
        return vec![];
    };
    entries
        .filter_map(std::result::Result::ok)
        .flat_map(|entry| {
            let path = entry.path();
            if path.extension().is_some_and(|extension| extension == "app")
                && path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| {
                        name.starts_with("IntelliJ IDEA") || name.starts_with("Android Studio")
                    })
            {
                vec![path]
            } else if entry.file_type().is_ok_and(|kind| kind.is_dir()) {
                idea_apps_in(&path, depth - 1)
            } else {
                vec![]
            }
        })
        .collect()
}

#[cfg(target_os = "macos")]
fn ensure_supported_idea_app(app: &Path) -> Result<()> {
    let build = idea_app_build(app);
    if build.as_ref().is_some_and(IdeaAppBuild::is_supported) {
        Ok(())
    } else {
        let mut error = CliError::new(
            "IDEA_VERSION_UNSUPPORTED",
            format!(
                "Kast requires IntelliJ IDEA 2026.2/build 262 or Android Studio 2026.1.2/build 261; {} reports {}.",
                app.display(),
                build
                    .map(|build| format!("{}-{}", build.product_code, build.branch))
                    .unwrap_or_else(|| "an unknown build".to_string()),
            ),
        );
        error
            .details
            .insert("ideaApp".to_string(), app.display().to_string());
        Err(error)
    }
}

#[cfg(target_os = "macos")]
fn is_supported_idea_app(app: &Path) -> bool {
    idea_app_build(app)
        .as_ref()
        .is_some_and(IdeaAppBuild::is_supported)
}

#[cfg(target_os = "macos")]
#[derive(Debug)]
struct IdeaAppBuild {
    product_code: String,
    branch: String,
}

#[cfg(target_os = "macos")]
impl IdeaAppBuild {
    fn is_supported(&self) -> bool {
        matches!(
            (self.product_code.as_str(), self.branch.as_str()),
            ("AI", "261") | ("IC" | "IU", "262")
        )
    }
}

#[cfg(target_os = "macos")]
fn idea_app_build(app: &Path) -> Option<IdeaAppBuild> {
    fs::read_to_string(app.join("Contents/Resources/build.txt"))
        .ok()
        .and_then(|build| {
            let (product_code, version) = build.trim().split_once('-')?;
            let branch = version.split('.').next()?;
            Some(IdeaAppBuild {
                product_code: product_code.to_string(),
                branch: branch.to_string(),
            })
        })
}
