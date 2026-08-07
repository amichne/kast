#[cfg(target_os = "macos")]
fn preflight_installed_idea_semantic_runtime(idea_home: &Path, idea_system: &Path) -> Result<()> {
    const KOTLIN_BUILDER: &str = "org/jetbrains/kotlin/jps/build/KotlinBuilder.class";

    let kotlin_jps = idea_home.join("plugins/Kotlin/lib/jps/kotlin-jps-plugin.jar");
    let file = fs::File::open(&kotlin_jps).map_err(|error| {
        CliError::new(
            "INDEXER_DEPENDENCY_UNAVAILABLE",
            format!(
                "Required Kotlin/JPS dependency org.jetbrains.kotlin:kotlin-jps-plugin is unavailable at {}: {error}. Repair the supported IDE installation before starting Kast.",
                kotlin_jps.display(),
            ),
        )
    })?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|error| invalid_kotlin_jps_dependency(&kotlin_jps, &error.to_string()))?;
    let mut builder = archive
        .by_name(KOTLIN_BUILDER)
        .map_err(|error| invalid_kotlin_jps_dependency(&kotlin_jps, &error.to_string()))?;
    std::io::copy(&mut builder, &mut std::io::sink())
        .map_err(|error| invalid_kotlin_jps_dependency(&kotlin_jps, &error.to_string()))?;

    let plugin_ids = idea_system.join("plugins/pluginsXMLIds.json");
    if plugin_ids.is_file() {
        let raw = fs::read(&plugin_ids).map_err(|error| {
            CliError::new(
                "INDEXER_CACHE_INVALID",
                format!("Cannot read IDEA plugin cache {}: {error}", plugin_ids.display()),
            )
        })?;
        let ids: Vec<String> = serde_json::from_slice(&raw).map_err(|error| {
            CliError::new(
                "INDEXER_CACHE_INVALID",
                format!(
                    "IDEA plugin cache {} is corrupt: {error}. Remove this exact isolated cache and restart Kast to rebuild it.",
                    plugin_ids.display(),
                ),
            )
        })?;
        let unique = ids.iter().collect::<std::collections::HashSet<_>>();
        if ids.iter().any(|id| id.trim().is_empty()) || unique.len() != ids.len() {
            return Err(CliError::new(
                "INDEXER_CACHE_INVALID",
                format!(
                    "IDEA plugin cache {} contains blank or duplicate plugin identifiers. Remove this exact isolated cache and restart Kast to rebuild it.",
                    plugin_ids.display(),
                ),
            ));
        }
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn invalid_kotlin_jps_dependency(path: &Path, reason: &str) -> CliError {
    CliError::new(
        "INDEXER_DEPENDENCY_INVALID",
        format!(
            "Required Kotlin/JPS dependency org.jetbrains.kotlin:kotlin-jps-plugin is invalid at {}: {reason}. Repair the supported IDE installation before starting Kast.",
            path.display(),
        ),
    )
}
