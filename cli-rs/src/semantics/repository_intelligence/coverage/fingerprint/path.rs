fn semantic_manifest_path(directory: &str, filename: &str) -> Result<Option<String>> {
    if directory.starts_with("__kast_abs__/") {
        return Ok(None);
    }
    if filename.contains(['/', '\\']) {
        return Err(CliError::new(
            "GRAPH_COVERAGE_UNAVAILABLE",
            "semantic graph manifest filename is not canonical",
        ));
    }
    let directory = directory
        .strip_prefix("__kast_rel__/")
        .unwrap_or(directory);
    let path = if directory.is_empty() {
        PathBuf::from(filename)
    } else {
        PathBuf::from(directory).join(filename)
    };
    if path
        .components()
        .any(|component| !matches!(component, std::path::Component::Normal(_)))
    {
        return Err(CliError::new(
            "GRAPH_COVERAGE_UNAVAILABLE",
            "semantic graph manifest path is not canonical",
        ));
    }
    Ok(Some(path.to_string_lossy().into_owned()))
}
