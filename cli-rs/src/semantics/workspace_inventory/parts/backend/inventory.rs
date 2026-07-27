fn module_inventory(
    module: &MetadataModule,
    coverage: BackendModuleCoverage,
) -> BackendModuleInventory {
    BackendModuleInventory::new(
        module.name.clone(),
        module.source_roots.clone(),
        module.content_roots.clone(),
        module.dependency_module_names.clone(),
        module.file_count,
        coverage,
    )
}

fn strictly_sorted<T: Ord>(values: &[T]) -> bool {
    values.windows(2).all(|window| window[0] < window[1])
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
