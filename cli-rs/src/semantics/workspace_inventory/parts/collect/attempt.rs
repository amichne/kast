fn collect_attempt(inputs: &mut WorkspaceInventoryInputs<'_>) -> CollectedAttempt {
    let backend = collect_backend_inventory(&inputs.root, inputs.kind_domain, inputs.backend);
    let index_before = inputs
        .kind_domain
        .includes_sources()
        .then(|| inputs.lanes.read_source_index(&inputs.root));
    let candidates = candidate_paths(inputs.kind_domain, &backend, index_before.as_ref());
    let filesystem_before = inputs.lanes.read_filesystem(&inputs.root, &candidates);
    let dirty_before = inputs
        .dirty_evidence_relevant
        .then(|| inputs.lanes.read_dirty_workspace(&inputs.root));

    let backend_before_stamp = backend_lane_stamp(&backend);
    let index_before_evidence = index_lane_evidence(index_before.as_ref());
    let dirty_before_evidence = dirty_lane_evidence(dirty_before.as_ref());
    let snapshot = compose_snapshot(
        inputs.kind_domain,
        &backend,
        index_before.as_ref(),
        &filesystem_before,
        dirty_before.as_ref(),
    );

    let index_after = inputs
        .kind_domain
        .includes_sources()
        .then(|| inputs.lanes.read_source_index(&inputs.root));
    let filesystem_after = inputs.lanes.read_filesystem(&inputs.root, &candidates);
    let dirty_after = inputs
        .dirty_evidence_relevant
        .then(|| inputs.lanes.read_dirty_workspace(&inputs.root));
    let backend_after_stamp =
        revalidate_backend_inventory(&inputs.root, inputs.kind_domain, &backend, inputs.backend);

    CollectedAttempt {
        snapshot,
        before: CompositionLaneStamps {
            backend: relevant_lane(
                WorkspaceLanePurpose::CandidateInventory,
                backend_before_stamp,
            ),
            index: index_before_evidence,
            filesystem: relevant_lane(WorkspaceLanePurpose::CandidateAndFilter, filesystem_before),
            dirty: dirty_before_evidence,
        },
        after: CompositionLaneStamps {
            backend: relevant_lane(
                WorkspaceLanePurpose::CandidateInventory,
                backend_after_stamp,
            ),
            index: index_lane_evidence(index_after.as_ref()),
            filesystem: relevant_lane(WorkspaceLanePurpose::CandidateAndFilter, filesystem_after),
            dirty: dirty_lane_evidence(dirty_after.as_ref()),
        },
    }
}

fn candidate_paths(
    kind_domain: WorkspaceRequestedKindDomain,
    backend: &super::model::BackendWorkspaceInventory,
    index: Option<&WorkspaceIndexRead>,
) -> BTreeSet<WorkspaceFilePath> {
    let mut paths: BTreeSet<_> = backend
        .files()
        .keys()
        .filter(|path| kind_domain_includes_path(kind_domain, path))
        .cloned()
        .collect();
    if let Some(WorkspaceIndexRead::Snapshot(index)) = index {
        paths.extend(index.files().iter().map(|file| file.path().clone()));
    }
    paths
}

fn kind_domain_includes_path(
    kind_domain: WorkspaceRequestedKindDomain,
    path: &WorkspaceFilePath,
) -> bool {
    match path
        .as_path()
        .extension()
        .and_then(|extension| extension.to_str())
    {
        Some("kt") => kind_domain.includes_sources(),
        Some("kts") => kind_domain.includes_scripts(),
        _ => false,
    }
}
