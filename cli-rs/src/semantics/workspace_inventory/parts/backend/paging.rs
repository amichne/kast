fn fetch_metadata(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> Result<BackendAttempt, BackendRpcFailure> {
    let result = rpc.request(workspace_request(json!({
        "includeFiles": false,
        "kindDomain": kind_domain_wire(kind_domain),
    })))?;
    let decoded: WorkspaceInventoryResponse = serde_json::from_value(result)
        .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
    let snapshot = BackendWorkspaceSnapshotToken::parse(decoded.snapshot_token)
        .ok_or_else(|| BackendRpcFailure::InvalidResponse("invalid snapshot token".to_string()))?;
    let mut names = BTreeSet::new();
    let mut modules = Vec::with_capacity(decoded.modules.len());
    for raw in decoded.modules {
        let name = BackendModuleName::parse(raw.name)
            .ok_or_else(|| BackendRpcFailure::InvalidResponse("invalid module name".to_string()))?;
        if !names.insert(name.clone())
            || !raw.files.is_empty()
            || raw.returned_file_count != 0
            || raw.files_truncated
            || raw.next_page_token.is_some()
        {
            return Err(BackendRpcFailure::InvalidResponse(
                "metadata modules must be unique and contain an empty non-paged file view"
                    .to_string(),
            ));
        }
        if !strictly_sorted(&raw.source_roots)
            || !strictly_sorted(&raw.content_roots)
            || !strictly_sorted(&raw.dependency_module_names)
        {
            return Err(BackendRpcFailure::InvalidResponse(
                "module roots and dependencies must be strictly sorted and unique".to_string(),
            ));
        }
        let raw_source_roots = raw.source_roots;
        let raw_content_roots = raw.content_roots;
        let (source_roots, source_contained) =
            normalize_roots(root.as_path(), raw_source_roots.clone());
        let (content_roots, content_contained) =
            normalize_roots(root.as_path(), raw_content_roots.clone());
        let dependency_module_names = raw
            .dependency_module_names
            .into_iter()
            .map(|dependency| {
                BackendModuleName::parse(dependency).ok_or_else(|| {
                    BackendRpcFailure::InvalidResponse("invalid dependency module name".to_string())
                })
            })
            .collect::<Result<_, _>>()?;
        modules.push(MetadataModule {
            name,
            raw_source_roots,
            source_roots,
            raw_content_roots,
            content_roots,
            dependency_module_names,
            file_count: raw.file_count,
            containment_complete: source_contained && content_contained,
        });
    }
    modules.sort_by(|left, right| left.name.cmp(&right.name));
    Ok(BackendAttempt { snapshot, modules })
}

fn exhaust_module(
    root: &WorkspaceRoot,
    kind_domain: WorkspaceRequestedKindDomain,
    snapshot: &BackendWorkspaceSnapshotToken,
    module: &MetadataModule,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> Result<BTreeSet<WorkspaceFilePath>, BackendRpcFailure> {
    if module.file_count == 0 {
        return Ok(BTreeSet::new());
    }
    let mut token: Option<BackendWorkspacePageToken> = None;
    let mut seen_tokens = BTreeSet::new();
    let mut files = BTreeSet::new();
    loop {
        if let Some(page_token) = token.as_ref()
            && !seen_tokens.insert(page_token.clone())
        {
            return Err(BackendRpcFailure::InvalidResponse(
                "workspace page token repeated".to_string(),
            ));
        }
        let mut params = json!({
            "includeFiles": true,
            "kindDomain": kind_domain_wire(kind_domain),
            "maxFilesPerModule": PAGE_SIZE,
            "moduleName": module.name.as_str(),
            "snapshotToken": snapshot.as_str(),
        });
        if let Some(page_token) = token.as_ref() {
            params["pageToken"] = Value::String(page_token.as_str().to_string());
        }
        let result = rpc.request(workspace_request(params))?;
        let decoded: WorkspaceInventoryResponse = serde_json::from_value(result)
            .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
        let echoed_snapshot = BackendWorkspaceSnapshotToken::parse(decoded.snapshot_token)
            .ok_or_else(|| {
                BackendRpcFailure::InvalidResponse("invalid snapshot token".to_string())
            })?;
        if &echoed_snapshot != snapshot || decoded.modules.len() != 1 {
            return Err(BackendRpcFailure::InvalidResponse(
                "workspace page is not bound to the requested snapshot and module".to_string(),
            ));
        }
        let page =
            decoded.modules.into_iter().next().ok_or_else(|| {
                BackendRpcFailure::InvalidResponse("missing module page".to_string())
            })?;
        if page.name != module.name.as_str()
            || page.file_count != module.file_count
            || page.returned_file_count != page.files.len()
            || page.files_truncated != page.next_page_token.is_some()
            || !page_metadata_matches(root, &page, module)?
        {
            return Err(BackendRpcFailure::InvalidResponse(
                "workspace page module identity, fingerprint, or cardinality changed".to_string(),
            ));
        }
        for raw_path in page.files {
            let path = contained_workspace_path(root.as_path(), &raw_path)?;
            if !files.insert(path) {
                return Err(BackendRpcFailure::InvalidResponse(
                    "workspace module pages overlap".to_string(),
                ));
            }
            if files.len() > module.file_count {
                return Err(BackendRpcFailure::InvalidResponse(
                    "workspace module returned more files than declared".to_string(),
                ));
            }
        }
        token = page
            .next_page_token
            .map(|value| {
                BackendWorkspacePageToken::parse(value).ok_or_else(|| {
                    BackendRpcFailure::InvalidResponse("invalid page token".to_string())
                })
            })
            .transpose()?;
        if token.is_some() && page.returned_file_count == 0 {
            return Err(BackendRpcFailure::InvalidResponse(
                "nonterminal workspace module pages must make progress".to_string(),
            ));
        }
        if token.is_none() {
            break;
        }
    }
    if files.len() != module.file_count {
        return Err(BackendRpcFailure::InvalidResponse(format!(
            "workspace module returned {} of {} declared files",
            files.len(),
            module.file_count
        )));
    }
    Ok(files)
}
