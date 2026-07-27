fn validate_snapshot(
    kind_domain: WorkspaceRequestedKindDomain,
    snapshot: &BackendWorkspaceSnapshotToken,
    rpc: &mut dyn BackendWorkspaceRpc,
) -> Result<(), BackendRpcFailure> {
    let result = rpc.request(workspace_request(json!({
        "includeFiles": false,
        "kindDomain": kind_domain_wire(kind_domain),
        "snapshotToken": snapshot.as_str(),
    })))?;
    let decoded: WorkspaceInventoryResponse = serde_json::from_value(result)
        .map_err(|error| BackendRpcFailure::InvalidResponse(error.to_string()))?;
    let echoed = BackendWorkspaceSnapshotToken::parse(decoded.snapshot_token)
        .ok_or_else(|| BackendRpcFailure::InvalidResponse("invalid snapshot token".to_string()))?;
    if &echoed != snapshot {
        return Err(BackendRpcFailure::InvalidResponse(
            "snapshot validation returned another snapshot".to_string(),
        ));
    }
    Ok(())
}

fn stale_inventory(
    modules: BTreeMap<BackendModuleName, BackendModuleInventory>,
) -> BackendWorkspaceInventory {
    let mut limitations = BTreeMap::new();
    increment(
        &mut limitations,
        WorkspaceInventoryLimitationCode::BackendWorkspaceInventoryStale,
    );
    BackendWorkspaceInventory::new(
        BTreeMap::new(),
        modules,
        BackendWorkspaceCoverage::Partial,
        None,
        limitations,
    )
}

fn failure_inventory(attempt_failure: BackendAttemptFailure) -> BackendWorkspaceInventory {
    let BackendAttemptFailure {
        failure,
        scope,
        modules,
    } = attempt_failure;
    let mut limitations = BTreeMap::new();
    let (coverage, limitation) = if is_project_model_incomplete(&failure) {
        let coverage = match scope {
            BackendFailureScope::Metadata => BackendWorkspaceCoverage::Unavailable,
            BackendFailureScope::WholeAttempt => BackendWorkspaceCoverage::Partial,
        };
        (coverage, project_model_limitation(&failure))
    } else {
        let (coverage, limitation) = match scope {
            BackendFailureScope::Metadata => (
                BackendWorkspaceCoverage::Unavailable,
                WorkspaceInventoryLimitationCode::BackendMetadataUnavailable,
            ),
            BackendFailureScope::WholeAttempt => (
                BackendWorkspaceCoverage::Partial,
                WorkspaceInventoryLimitationCode::BackendPageIncomplete,
            ),
        };
        (coverage, limitation)
    };
    increment(&mut limitations, limitation);
    BackendWorkspaceInventory::new(BTreeMap::new(), modules, coverage, None, limitations)
}

fn partial_modules(
    metadata: &[MetadataModule],
) -> BTreeMap<BackendModuleName, BackendModuleInventory> {
    metadata
        .iter()
        .map(|module| {
            (
                module.name.clone(),
                module_inventory(module, BackendModuleCoverage::Partial),
            )
        })
        .collect()
}
