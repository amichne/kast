fn bundle_source_root(source: &Path, scratch_root: &Path) -> Result<PathBuf> {
    if source.is_dir() {
        return Ok(source.to_path_buf());
    }
    if source.is_file() {
        return extract_bundle_tarball(source, &scratch_root.join("extract"));
    }
    Err(CliError::new(
        "BUNDLE_SOURCE_NOT_FOUND",
        format!("Bundle source was not found: {}", source.display()),
    ))
}

fn extract_bundle_tarball(archive_path: &Path, output_dir: &Path) -> Result<PathBuf> {
    let top_level = validate_tarball_members(archive_path)?;
    fs::create_dir_all(output_dir)?;
    let archive_file = fs::File::open(archive_path)?;
    let decoder = GzDecoder::new(archive_file);
    let mut archive = tar::Archive::new(decoder);
    archive.unpack(output_dir).map_err(|error| {
        CliError::new(
            "BUNDLE_ARCHIVE_INVALID",
            format!(
                "Could not extract bundle archive {}: {error}",
                archive_path.display()
            ),
        )
    })?;
    let bundle_root = output_dir.join(top_level);
    if bundle_root.is_dir() {
        Ok(bundle_root)
    } else {
        Err(CliError::new(
            "BUNDLE_ARCHIVE_INVALID",
            format!(
                "Bundle archive {} did not extract to a top-level directory.",
                archive_path.display()
            ),
        ))
    }
}

fn validate_tarball_members(archive_path: &Path) -> Result<PathBuf> {
    let archive_file = fs::File::open(archive_path)?;
    let decoder = GzDecoder::new(archive_file);
    let mut archive = tar::Archive::new(decoder);
    let mut top_level: Option<PathBuf> = None;
    let entries = archive.entries().map_err(|error| {
        CliError::new(
            "BUNDLE_ARCHIVE_INVALID",
            format!(
                "Could not read bundle archive {}: {error}",
                archive_path.display()
            ),
        )
    })?;
    for entry in entries {
        let entry = entry.map_err(|error| {
            CliError::new(
                "BUNDLE_ARCHIVE_INVALID",
                format!(
                    "Could not read bundle archive {}: {error}",
                    archive_path.display()
                ),
            )
        })?;
        if entry.header().entry_type().is_symlink() || entry.header().entry_type().is_hard_link() {
            return Err(CliError::new(
                "BUNDLE_ARCHIVE_INVALID",
                format!(
                    "Bundle archive {} must not contain link entries.",
                    archive_path.display()
                ),
            ));
        }
        let relative = safe_relative_path(&entry.path()?, "archive member")?;
        let Some(first_component) = relative.components().next() else {
            return Err(CliError::new(
                "BUNDLE_ARCHIVE_INVALID",
                "Bundle archive contains an empty member path.",
            ));
        };
        let current_top = PathBuf::from(first_component.as_os_str());
        match &top_level {
            Some(expected) if expected != &current_top => {
                return Err(CliError::new(
                    "BUNDLE_ARCHIVE_INVALID",
                    "Bundle archive must contain exactly one top-level directory.",
                ));
            }
            Some(_) => {}
            None => top_level = Some(current_top),
        }
    }
    top_level.ok_or_else(|| {
        CliError::new(
            "BUNDLE_ARCHIVE_INVALID",
            format!("Bundle archive is empty: {}", archive_path.display()),
        )
    })
}
