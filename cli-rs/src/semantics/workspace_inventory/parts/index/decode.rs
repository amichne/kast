fn decode_package(row: &ManifestRow) -> (WorkspacePackageEvidence, bool) {
    if !row.metadata_present {
        return (WorkspacePackageEvidence::Unavailable, false);
    }
    match row.package_state.as_deref() {
        Some("PROVEN_ROOT")
            if row.package_fq_id.is_none()
                && row.package_fq_name.is_none()
                && row.package_unproven_reason.is_none() =>
        {
            (WorkspacePackageEvidence::ProvenRoot, true)
        }
        Some("PROVEN_NAMED")
            if row.package_fq_id.is_some() && row.package_unproven_reason.is_none() =>
        {
            let Some(fq_name) = row.package_fq_name.clone() else {
                return (
                    WorkspacePackageEvidence::InvalidReference(
                        WorkspacePackageInvalidReference::DanglingFqName,
                    ),
                    false,
                );
            };
            match KotlinPackageFqName::parse_persisted(fq_name) {
                Some(name) => (WorkspacePackageEvidence::ProvenNamed(name), true),
                None => (
                    WorkspacePackageEvidence::InvalidReference(
                        WorkspacePackageInvalidReference::InvalidFqName,
                    ),
                    false,
                ),
            }
        }
        Some("UNPROVEN") if row.package_fq_id.is_none() && row.package_fq_name.is_none() => {
            match row
                .package_unproven_reason
                .as_deref()
                .and_then(WorkspacePackageUnprovenReason::parse)
            {
                Some(reason) => (WorkspacePackageEvidence::Unproven(reason), false),
                None => (
                    WorkspacePackageEvidence::InvalidReference(
                        WorkspacePackageInvalidReference::IllegalStateTuple,
                    ),
                    false,
                ),
            }
        }
        Some("PROVEN_ROOT" | "PROVEN_NAMED" | "UNPROVEN") => (
            WorkspacePackageEvidence::InvalidReference(
                WorkspacePackageInvalidReference::IllegalStateTuple,
            ),
            false,
        ),
        _ => (
            WorkspacePackageEvidence::InvalidReference(
                WorkspacePackageInvalidReference::InvalidState,
            ),
            false,
        ),
    }
}

fn relative_manifest_path(dir_path: &str, filename: &str) -> Option<WorkspaceFilePath> {
    if dir_path.starts_with(ABSOLUTE_PATH_PREFIX) {
        return None;
    }
    let relative_dir = dir_path
        .strip_prefix(RELATIVE_ESCAPE_PREFIX)
        .unwrap_or(dir_path);
    if relative_dir.contains('\\') || filename.contains(['/', '\\']) {
        return None;
    }
    let path = if relative_dir.is_empty() {
        PathBuf::from(filename)
    } else {
        PathBuf::from(relative_dir).join(filename)
    };
    WorkspaceFilePath::from_relative_path(path)
}

fn is_kotlin_source(filename: &str) -> bool {
    Path::new(filename)
        .extension()
        .is_some_and(|extension| extension == "kt")
}

fn contain_path(
    root: &WorkspaceRoot,
    relative_path: &Path,
) -> (WorkspaceFileDrift, PathContainment) {
    let candidate = root.as_path().join(relative_path);
    match std::fs::symlink_metadata(&candidate) {
        Ok(_) => match std::fs::canonicalize(&candidate) {
            Ok(canonical) if canonical.starts_with(root.as_path()) => {
                (WorkspaceFileDrift::InSync, PathContainment::Contained)
            }
            Ok(_) => (WorkspaceFileDrift::Unknown, PathContainment::Outside),
            Err(_) => (WorkspaceFileDrift::Unknown, PathContainment::Unprovable),
        },
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            contain_missing_path(root, candidate)
        }
        Err(_) => (WorkspaceFileDrift::Unknown, PathContainment::Unprovable),
    }
}

fn contain_missing_path(
    root: &WorkspaceRoot,
    candidate: PathBuf,
) -> (WorkspaceFileDrift, PathContainment) {
    let mut ancestor = candidate.as_path();
    loop {
        let Some(parent) = ancestor.parent() else {
            return (WorkspaceFileDrift::Unknown, PathContainment::Unprovable);
        };
        ancestor = parent;
        if !ancestor.starts_with(root.as_path()) {
            return (WorkspaceFileDrift::Unknown, PathContainment::Outside);
        }
        match std::fs::symlink_metadata(ancestor) {
            Ok(_) => {
                return match std::fs::canonicalize(ancestor) {
                    Ok(canonical) if canonical.starts_with(root.as_path()) => (
                        WorkspaceFileDrift::MissingOnDisk,
                        PathContainment::Contained,
                    ),
                    Ok(_) => (WorkspaceFileDrift::Unknown, PathContainment::Outside),
                    Err(_) => (WorkspaceFileDrift::Unknown, PathContainment::Unprovable),
                };
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => {
                return (WorkspaceFileDrift::Unknown, PathContainment::Unprovable);
            }
        }
    }
}
