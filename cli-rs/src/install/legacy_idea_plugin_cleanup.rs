const LEGACY_IDEA_PLUGIN_CLEANUP_RELEASE: &str = "0.13.0";

#[derive(Debug, Clone, PartialEq, Eq)]
struct OwnedLegacySymlink {
    path: PathBuf,
    target: PathBuf,
}

fn owned_legacy_idea_plugin_links(
    jetbrains_config_root: &Path,
    formula_prefix: &Path,
) -> Result<Vec<OwnedLegacySymlink>> {
    owned_legacy_idea_plugin_links_for_release(
        jetbrains_config_root,
        formula_prefix,
        cli::version(),
    )
}

fn owned_legacy_idea_plugin_links_for_release(
    jetbrains_config_root: &Path,
    formula_prefix: &Path,
    release_version: &str,
) -> Result<Vec<OwnedLegacySymlink>> {
    if release_version != LEGACY_IDEA_PLUGIN_CLEANUP_RELEASE {
        return Ok(vec![]);
    }
    let Some(homebrew_root) = homebrew_root_from_formula_prefix(formula_prefix) else {
        return Ok(vec![]);
    };
    let Ok(entries) = fs::read_dir(jetbrains_config_root) else {
        return Ok(vec![]);
    };
    let mut owned = vec![];
    for entry in entries {
        let entry = entry?;
        if !entry.file_type()?.is_dir() || !recognized_jetbrains_profile(&entry.file_name()) {
            continue;
        }
        let path = entry.path().join("plugins/kast");
        let Ok(metadata) = fs::symlink_metadata(&path) else {
            continue;
        };
        if !metadata.file_type().is_symlink() {
            continue;
        }
        let target = fs::read_link(&path)?;
        if exact_legacy_cask_target(&homebrew_root, &target) {
            owned.push(OwnedLegacySymlink { path, target });
        }
    }
    owned.sort_by(|left, right| left.path.cmp(&right.path));
    Ok(owned)
}

fn recognized_jetbrains_profile(name: &std::ffi::OsStr) -> bool {
    let Some(name) = name.to_str() else {
        return false;
    };
    [
        "IntelliJIdea",
        "IdeaIC",
        "AndroidStudio",
        "GoLand",
        "PyCharm",
        "Rider",
        "WebStorm",
        "CLion",
        "RubyMine",
        "DataGrip",
    ]
    .iter()
    .filter_map(|prefix| name.strip_prefix(prefix))
    .any(|version| {
        !version.is_empty()
            && version
                .split('.')
                .all(|segment| !segment.is_empty() && segment.chars().all(|c| c.is_ascii_digit()))
    })
}

fn homebrew_root_from_formula_prefix(formula_prefix: &Path) -> Option<PathBuf> {
    let canonical = fs::canonicalize(formula_prefix).ok()?;
    let components = canonical.components().collect::<Vec<_>>();
    let cellar = components
        .windows(3)
        .position(|window| window[0].as_os_str() == "Cellar" && window[1].as_os_str() == "kast")?;
    let mut root = PathBuf::new();
    for component in &components[..cellar] {
        root.push(component.as_os_str());
    }
    Some(root)
}

fn exact_legacy_cask_target(homebrew_root: &Path, target: &Path) -> bool {
    if !target.is_absolute()
        || target
            .components()
            .any(|component| matches!(component, Component::ParentDir | Component::CurDir))
    {
        return false;
    }
    let expected_parent = homebrew_root.join("Caskroom/kast-plugin");
    let Ok(relative) = target.strip_prefix(expected_parent) else {
        return false;
    };
    let components = relative.components().collect::<Vec<_>>();
    components.len() == 2
        && matches!(components[0], Component::Normal(_))
        && components[1].as_os_str() == "backend-idea"
}

#[cfg(target_os = "macos")]
fn require_jetbrains_ides_closed_for_legacy_cleanup() -> Result<()> {
    require_jetbrains_ides_closed_from_pgrep(
        ProcessCommand::new("pgrep")
            .args([
                "-f",
                "/(IntelliJ IDEA|Android Studio|GoLand|PyCharm|Rider|WebStorm|CLion|RubyMine|DataGrip)[^/]*\\.app/Contents/MacOS/",
            ])
            .output(),
    )
}

#[cfg(not(target_os = "macos"))]
fn require_jetbrains_ides_closed_for_legacy_cleanup() -> Result<()> {
    Ok(())
}

#[cfg(target_os = "macos")]
fn require_jetbrains_ides_closed_from_pgrep(output: std::io::Result<Output>) -> Result<()> {
    let output = output.map_err(|error| {
        CliError::new(
            "JETBRAINS_IDE_STATE_UNAVAILABLE",
            format!(
                "Could not prove that JetBrains IDEs are closed before legacy cleanup: {error}. Leave the link unchanged, close affected IDE windows, and rerun `kast repair --for machine --apply`.",
            ),
        )
    })?;
    match output.status.code() {
        Some(0) => Err(CliError::new(
            "JETBRAINS_IDE_OPEN",
            "Close the affected JetBrains IDE windows before removing recognized legacy Homebrew plugin links, then rerun `kast repair --for machine --apply`.",
        )),
        Some(1) => Ok(()),
        status => Err(CliError::new(
            "JETBRAINS_IDE_STATE_UNAVAILABLE",
            format!(
                "Could not prove that JetBrains IDEs are closed before legacy cleanup because pgrep returned {status:?}. Leave the link unchanged, close affected IDE windows, and rerun `kast repair --for machine --apply`.",
            ),
        )),
    }
}
