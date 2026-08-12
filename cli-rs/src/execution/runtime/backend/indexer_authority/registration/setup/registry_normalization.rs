#[derive(Debug)]
struct RuntimeWorkspaceRegistryDirectory {
    path: PathBuf,
    workspace_key: String,
}

#[derive(Debug)]
enum RuntimeServicesEntry {
    Workspace(RuntimeWorkspaceRegistryDirectory),
    Noise(PathBuf),
}

#[derive(Debug)]
enum RuntimeWorkspaceEntry {
    ActivePointer,
    PublicationTemporary {
        path: PathBuf,
        temporary: RegistrationPublicationTemporary,
    },
    Registration(PathBuf),
    Noise(PathBuf),
}

fn classify_runtime_services_entry(entry: fs::DirEntry) -> Result<RuntimeServicesEntry> {
    let path = entry.path();
    let Some(workspace_key) = entry.file_name().to_str().map(str::to_owned) else {
        return Ok(RuntimeServicesEntry::Noise(path));
    };
    if !entry.file_type()?.is_dir() || !is_sha256(&workspace_key) {
        return Ok(RuntimeServicesEntry::Noise(path));
    }
    Ok(RuntimeServicesEntry::Workspace(
        RuntimeWorkspaceRegistryDirectory {
            path,
            workspace_key,
        },
    ))
}

fn classify_runtime_workspace_entry(entry: fs::DirEntry) -> Result<RuntimeWorkspaceEntry> {
    let path = entry.path();
    let name = entry.file_name();
    if name == "active.json" {
        return Ok(RuntimeWorkspaceEntry::ActivePointer);
    }
    if let Some(temporary) = registration_publication_temporary(&name) {
        return Ok(RuntimeWorkspaceEntry::PublicationTemporary { path, temporary });
    }
    if entry.file_type()?.is_dir()
        && name.to_str().and_then(canonical_uuid).is_some()
    {
        return Ok(RuntimeWorkspaceEntry::Registration(path));
    }
    Ok(RuntimeWorkspaceEntry::Noise(path))
}

fn remove_runtime_registry_noise(path: &Path) -> Result<()> {
    crate::manifest::remove_path(path)
        .and_then(|()| sync_parent(path))
        .map_err(|error| {
            let mut failure = CliError::new(
                "SETUP_RUNTIME_REGISTRY_NORMALIZATION_FAILED",
                format!(
                    "Kast-owned runtime registry entry could not be removed: {}",
                    path.display()
                ),
            );
            failure
                .details
                .insert("path".to_string(), path.display().to_string());
            failure
                .details
                .insert("causeCode".to_string(), error.code.to_string());
            failure
                .details
                .insert("causeMessage".to_string(), error.message);
            failure
        })
}
