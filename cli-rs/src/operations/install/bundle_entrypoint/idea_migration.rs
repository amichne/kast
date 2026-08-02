fn supported_idea_plugins_dirs() -> Result<Vec<PathBuf>> {
    let application_support = manifest::home_dir().join("Library/Application Support");
    let mut candidates = Vec::new();
    for (root, prefixes) in [
        (
            application_support.join("JetBrains"),
            &["IntelliJIdea2026.2", "IdeaIC2026.2"][..],
        ),
        (
            application_support.join("Google"),
            &["AndroidStudio2026.1"][..],
        ),
    ] {
        let entries = match fs::read_dir(&root) {
            Ok(entries) => entries,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(CliError::new(
                    "IDE_PROFILE_INSPECTION_FAILED",
                    format!("Cannot inspect {}: {error}", root.display()),
                ));
            }
        };
        candidates.extend(
            entries
                .filter_map(std::result::Result::ok)
                .filter(|entry| {
                    entry.file_type().is_ok_and(|kind| kind.is_dir())
                        && entry.file_name().to_str().is_some_and(|name| {
                            prefixes.iter().any(|prefix| name.starts_with(prefix))
                        })
                })
                .map(|entry| entry.path().join("plugins")),
        );
    }
    candidates.sort();
    candidates.dedup();
    Ok(candidates)
}

fn foreground_ide_is_open() -> Result<bool> {
    if let Ok(state) = env::var("KAST_MACHINE_IDE_STATE") {
        return match state.as_str() {
            "open" => Ok(true),
            "closed" => Ok(false),
            _ => Err(CliError::new(
                "IDE_STATE_INVALID",
                "KAST_MACHINE_IDE_STATE must be `open` or `closed` when set.",
            )),
        };
    }
    #[cfg(target_os = "macos")]
    {
        let output = ProcessCommand::new("pgrep")
            .args([
                "-f",
                "/(IntelliJ IDEA|Android Studio)[^/]*\\.app/Contents/MacOS/",
            ])
            .output()?;
        match output.status.code() {
            Some(0) => Ok(true),
            Some(1) => Ok(false),
            status => Err(CliError::new(
                "IDE_STATE_UNAVAILABLE",
                format!("Could not determine IDE process state: {status:?}."),
            )),
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        Ok(false)
    }
}
