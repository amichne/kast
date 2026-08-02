pub(crate) fn resolve_installed_idea_sidecar_app(
    _workspace_root: &Path,
    config: &KastConfig,
) -> Result<PathBuf> {
    if config.runtime.idea_launch.command != Path::new("idea") {
        return resolve_explicit_sidecar_host(&config.runtime.idea_launch.command);
    }
    select_supported_sidecar_host(installed_sidecar_hosts())
}

fn installed_sidecar_hosts() -> Vec<PathBuf> {
    let mut candidates = sidecar_hosts_in(Path::new("/Applications"), 1);
    if let Some(home) = std::env::var_os("HOME") {
        let home = PathBuf::from(home);
        candidates.extend(sidecar_hosts_in(&home.join("Applications"), 1));
        candidates.extend(sidecar_hosts_in(
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

fn select_supported_sidecar_host(installed: Vec<PathBuf>) -> Result<PathBuf> {
    let supported = installed
        .iter()
        .filter(|candidate| is_supported_sidecar_host(candidate))
        .cloned()
        .collect::<Vec<_>>();
    match supported.as_slice() {
        [app] => Ok(app.clone()),
        [] if !installed.is_empty() => Err(CliError::new(
            "IDEA_VERSION_UNSUPPORTED",
            "The private headless runtime requires IntelliJ IDEA 2026.2/build 262 or Android Studio 2026.1.2/build 261; no supported installed bundle was found.",
        )),
        [] => Err(CliError::new(
            "IDEA_HOST_NOT_FOUND",
            "The private headless runtime could not find a supported IntelliJ IDEA or Android Studio installation.",
        )),
        _ => Err(CliError::new(
            "IDEA_HOST_AMBIGUOUS",
            "More than one supported runtime host is installed; set runtime.ideaLaunch.command to the exact application bundle.",
        )),
    }
}

fn resolve_explicit_sidecar_host(command: &Path) -> Result<PathBuf> {
    let canonical = fs::canonicalize(command).map_err(|error| {
        CliError::new(
            "IDEA_HOST_NOT_FOUND",
            format!(
                "Configured runtime host could not be resolved at {}: {error}",
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
                "runtime.ideaLaunch.command must resolve inside a supported .app bundle on macOS.",
            )
        })?;
    ensure_supported_sidecar_host(&app)?;
    Ok(app)
}

fn sidecar_hosts_in(root: &Path, depth: usize) -> Vec<PathBuf> {
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
                sidecar_hosts_in(&path, depth - 1)
            } else {
                vec![]
            }
        })
        .collect()
}

fn ensure_supported_sidecar_host(app: &Path) -> Result<()> {
    let build = sidecar_host_build(app);
    if build.as_ref().is_some_and(SidecarHostBuild::is_supported) {
        Ok(())
    } else {
        Err(CliError::new(
            "IDEA_VERSION_UNSUPPORTED",
            format!("Unsupported private runtime host at {}.", app.display()),
        ))
    }
}

fn is_supported_sidecar_host(app: &Path) -> bool {
    sidecar_host_build(app)
        .as_ref()
        .is_some_and(SidecarHostBuild::is_supported)
}

struct SidecarHostBuild {
    product_code: String,
    branch: String,
}

impl SidecarHostBuild {
    fn is_supported(&self) -> bool {
        matches!(
            (self.product_code.as_str(), self.branch.as_str()),
            ("AI", "261") | ("IC" | "IU", "262")
        )
    }
}

fn sidecar_host_build(app: &Path) -> Option<SidecarHostBuild> {
    fs::read_to_string(app.join("Contents/Resources/build.txt"))
        .ok()
        .and_then(|build| {
            let (product_code, version) = build.trim().split_once('-')?;
            let branch = version.split('.').next()?;
            Some(SidecarHostBuild {
                product_code: product_code.to_string(),
                branch: branch.to_string(),
            })
        })
}
