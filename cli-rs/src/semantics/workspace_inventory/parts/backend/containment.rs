fn contained_workspace_path(
    root: &Path,
    path: &Path,
) -> Result<WorkspaceFilePath, BackendRpcFailure> {
    let relative = if path.is_absolute() {
        path.strip_prefix(root)
            .map_err(|_| BackendRpcFailure::Containment {
                path: path.to_path_buf(),
                reason: "absolute path is outside the admitted workspace".to_string(),
            })?
            .to_path_buf()
    } else {
        path.to_path_buf()
    };
    let relative = WorkspaceFilePath::from_relative_path(relative).ok_or_else(|| {
        BackendRpcFailure::Containment {
            path: path.to_path_buf(),
            reason: "path is not normalized workspace-relative".to_string(),
        }
    })?;
    prove_containment(root, relative.as_path()).map_err(|reason| {
        BackendRpcFailure::Containment {
            path: path.to_path_buf(),
            reason,
        }
    })?;
    Ok(relative)
}

fn prove_containment(root: &Path, relative: &Path) -> Result<(), String> {
    let candidate = root.join(relative);
    if std::fs::symlink_metadata(&candidate).is_ok() {
        let canonical = std::fs::canonicalize(&candidate)
            .map_err(|error| format!("existing path cannot be canonicalized: {error}"))?;
        return canonical
            .starts_with(root)
            .then_some(())
            .ok_or_else(|| "existing path resolves outside the admitted workspace".to_string());
    }
    let mut ancestor = candidate.as_path();
    while std::fs::symlink_metadata(ancestor).is_err() {
        ancestor = ancestor
            .parent()
            .ok_or_else(|| "missing path has no existing ancestor".to_string())?;
    }
    let canonical = std::fs::canonicalize(ancestor)
        .map_err(|error| format!("deepest existing ancestor cannot be canonicalized: {error}"))?;
    canonical
        .starts_with(root)
        .then_some(())
        .ok_or_else(|| "deepest existing ancestor resolves outside the workspace".to_string())
}

fn normalize_roots(
    root: &Path,
    raw_roots: Vec<PathBuf>,
) -> (BTreeSet<WorkspaceContainedRoot>, bool) {
    let mut contained = true;
    let roots = raw_roots
        .into_iter()
        .filter_map(|raw| match contained_workspace_root(root, &raw) {
            Ok(path) => Some(path),
            Err(_) => {
                contained = false;
                None
            }
        })
        .collect();
    (roots, contained)
}

fn contained_workspace_root(
    root: &Path,
    path: &Path,
) -> Result<WorkspaceContainedRoot, BackendRpcFailure> {
    let relative = if path.is_absolute() {
        path.strip_prefix(root)
            .map_err(|_| BackendRpcFailure::Containment {
                path: path.to_path_buf(),
                reason: "absolute root is outside the admitted workspace".to_string(),
            })?
            .to_path_buf()
    } else {
        path.to_path_buf()
    };
    let relative = WorkspaceContainedRoot::from_relative_path(relative).ok_or_else(|| {
        BackendRpcFailure::Containment {
            path: path.to_path_buf(),
            reason: "root is not normalized workspace-relative".to_string(),
        }
    })?;
    prove_containment(root, relative.as_path()).map_err(|reason| {
        BackendRpcFailure::Containment {
            path: path.to_path_buf(),
            reason,
        }
    })?;
    Ok(relative)
}

fn page_metadata_matches(
    root: &WorkspaceRoot,
    page: &WorkspaceModuleResponse,
    module: &MetadataModule,
) -> Result<bool, BackendRpcFailure> {
    if !strictly_sorted(&page.source_roots)
        || !strictly_sorted(&page.content_roots)
        || !strictly_sorted(&page.dependency_module_names)
    {
        return Ok(false);
    }
    let (source_roots, source_contained) =
        normalize_roots(root.as_path(), page.source_roots.clone());
    let (content_roots, content_contained) =
        normalize_roots(root.as_path(), page.content_roots.clone());
    let dependencies = page
        .dependency_module_names
        .iter()
        .cloned()
        .map(|name| {
            BackendModuleName::parse(name).ok_or_else(|| {
                BackendRpcFailure::InvalidResponse("invalid dependency module name".to_string())
            })
        })
        .collect::<Result<BTreeSet<_>, _>>()?;
    let page_containment_complete = source_contained && content_contained;
    Ok(page.source_roots == module.raw_source_roots
        && page.content_roots == module.raw_content_roots
        && source_roots == module.source_roots
        && content_roots == module.content_roots
        && dependencies == module.dependency_module_names
        && page_containment_complete == module.containment_complete)
}
