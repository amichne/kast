fn ensure_private_directory(path: &Path) -> Result<()> {
    ensure_private_directory_with(path, &sync_directory)
}

fn ensure_private_directory_with(
    path: &Path,
    publish_entry: &impl Fn(&Path) -> Result<()>,
) -> Result<()> {
    let mut missing = Vec::new();
    let mut existing = path;
    loop {
        match fs::symlink_metadata(existing) {
            Ok(metadata) => {
                admit_private_directory(existing, &metadata)?;
                break;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                missing.push(existing.to_path_buf());
                existing = existing.parent().ok_or_else(|| {
                    CliError::new(
                        "KAST_PLAN_STORE_INVALID",
                        "The private plan store has no publishable parent directory.",
                    )
                })?;
            }
            Err(error) => return Err(error.into()),
        }
    }

    for directory in missing.iter().rev() {
        match fs::create_dir(directory) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(error) => return Err(error.into()),
        }
        let metadata = fs::symlink_metadata(directory)?;
        admit_private_directory(directory, &metadata)?;
        set_mode(directory, 0o700)?;
        publish_entry(private_directory_parent(directory)?)?;
    }

    let metadata = fs::symlink_metadata(path)?;
    admit_private_directory(path, &metadata)?;
    set_mode(path, 0o700)?;
    if missing.is_empty() {
        publish_entry(private_directory_parent(path)?)?;
    }
    Ok(())
}

fn admit_private_directory(path: &Path, metadata: &fs::Metadata) -> Result<()> {
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(CliError::new(
            "KAST_PLAN_STORE_INVALID",
            format!("The private plan store {} is not a directory.", path.display()),
        ));
    }
    Ok(())
}

fn private_directory_parent(path: &Path) -> Result<&Path> {
    path.parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .ok_or_else(|| {
            CliError::new(
                "KAST_PLAN_STORE_INVALID",
                "The private plan store has no publishable parent directory.",
            )
        })
}
