#[cfg(target_os = "macos")]
fn preflight_installed_idea_semantic_runtime(
    payload_plugin: &Path,
    idea_home: &Path,
    idea_system: &Path,
) -> Result<()> {
    const KOTLIN_BUILDER: &str = "org/jetbrains/kotlin/jps/build/KotlinBuilder.class";
    const KOTLIN_FREEZABLE: &str =
        "org/jetbrains/kotlin/cli/common/arguments/Freezable.class";

    if let Some(conflict) = payload_kotlin_class_conflict(
        payload_plugin,
        &[KOTLIN_BUILDER, KOTLIN_FREEZABLE],
    )? {
        return Err(CliError::new(
            "INDEXER_DEPENDENCY_CONFLICT",
            format!(
                "The active Kast release incorrectly packages platform-owned Kotlin class {} at {}, which conflicts with the installed Kotlin plugin classloader. Run `kast setup` with a corrected release before starting Kast.",
                conflict.class_entry,
                conflict.jar_path.display(),
            ),
        ));
    }

    let kotlin_jps = idea_home.join("plugins/Kotlin/lib/jps/kotlin-jps-plugin.jar");
    require_installed_kotlin_class(&kotlin_jps, KOTLIN_BUILDER)?;
    let kotlin_compiler_common =
        idea_home.join("plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar");
    require_installed_kotlin_class(&kotlin_compiler_common, KOTLIN_FREEZABLE)?;

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
struct PayloadKotlinClassConflict {
    jar_path: PathBuf,
    class_entry: String,
}

#[cfg(target_os = "macos")]
fn payload_kotlin_class_conflict(
    payload_plugin: &Path,
    platform_class_entries: &[&str],
) -> Result<Option<PayloadKotlinClassConflict>> {
    let plugin_libs = payload_plugin.join("lib");
    let mut plugin_jars = fs::read_dir(&plugin_libs)
        .map_err(|error| {
            CliError::new(
                "INDEXER_DEPENDENCY_INVALID",
                format!(
                    "The active Kast release plugin library is unavailable at {}: {error}.",
                    plugin_libs.display(),
                ),
            )
        })?
        .collect::<std::io::Result<Vec<_>>>()
        .map_err(|error| {
            CliError::new(
                "INDEXER_DEPENDENCY_INVALID",
                format!(
                    "The active Kast release plugin library cannot be inspected at {}: {error}.",
                    plugin_libs.display(),
                ),
            )
        })?
        .into_iter()
        .map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|extension| extension == "jar"))
        .collect::<Vec<_>>();
    plugin_jars.sort();

    for jar_path in plugin_jars {
        let file = fs::File::open(&jar_path).map_err(|error| {
            invalid_payload_plugin_dependency(&jar_path, &error.to_string())
        })?;
        let mut archive = zip::ZipArchive::new(file).map_err(|error| {
            invalid_payload_plugin_dependency(&jar_path, &error.to_string())
        })?;
        for index in 0..archive.len() {
            let entry = archive.by_index(index).map_err(|error| {
                invalid_payload_plugin_dependency(&jar_path, &error.to_string())
            })?;
            if platform_class_entries.contains(&entry.name()) {
                return Ok(Some(PayloadKotlinClassConflict {
                    jar_path,
                    class_entry: entry.name().to_string(),
                }));
            }
        }
    }
    Ok(None)
}

#[cfg(target_os = "macos")]
fn require_installed_kotlin_class(kotlin_jar: &Path, class_entry: &str) -> Result<()> {
    let file = fs::File::open(kotlin_jar).map_err(|error| {
        CliError::new(
            "INDEXER_DEPENDENCY_UNAVAILABLE",
            format!(
                "The supported IntelliJ installation is missing its Kotlin plugin dependency at {}: {error}.",
                kotlin_jar.display(),
            ),
        )
    })?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|error| invalid_installed_kotlin_jps_dependency(kotlin_jar, &error.to_string()))?;
    let mut required_class = archive
        .by_name(class_entry)
        .map_err(|error| invalid_installed_kotlin_jps_dependency(kotlin_jar, &error.to_string()))?;
    std::io::copy(&mut required_class, &mut std::io::sink())
        .map_err(|error| invalid_installed_kotlin_jps_dependency(kotlin_jar, &error.to_string()))?;
    Ok(())
}

#[cfg(target_os = "macos")]
fn invalid_payload_plugin_dependency(path: &Path, reason: &str) -> CliError {
    CliError::new(
        "INDEXER_DEPENDENCY_INVALID",
        format!(
            "The active Kast release contains an invalid plugin dependency at {}: {reason}.",
            path.display(),
        ),
    )
}

#[cfg(target_os = "macos")]
fn invalid_installed_kotlin_jps_dependency(path: &Path, reason: &str) -> CliError {
    CliError::new(
        "INDEXER_DEPENDENCY_INVALID",
        format!(
            "The supported IntelliJ installation contains an invalid Kotlin/JPS dependency at {}: {reason}.",
            path.display(),
        ),
    )
}
