fn composition_digest(
    kind_domain: WorkspaceRequestedKindDomain,
    backend: WorkspaceLaneEvidence<BackendWorkspaceStamp>,
    index: WorkspaceLaneEvidence<SourceIndexSnapshotStamp>,
    filesystem: WorkspaceLaneEvidence<WorkspaceFilesystemStamp>,
    dirty: WorkspaceLaneEvidence<DirtyWorkspaceStamp>,
) -> String {
    let canonical = format!(
        "kind={kind_domain:?}|backend={backend:?}|index={index:?}|filesystem={filesystem:?}|dirty={dirty:?}"
    );
    hex::encode(Sha256::digest(canonical.as_bytes()))
}

fn merge_limitations(
    target: &mut BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    source: &BTreeMap<WorkspaceInventoryLimitationCode, usize>,
) {
    for (code, count) in source {
        target
            .entry(*code)
            .and_modify(|current| *current += count)
            .or_insert(*count);
    }
}

fn increment(
    limitations: &mut BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    code: WorkspaceInventoryLimitationCode,
) {
    limitations
        .entry(code)
        .and_modify(|count| *count += 1)
        .or_insert(1);
}
