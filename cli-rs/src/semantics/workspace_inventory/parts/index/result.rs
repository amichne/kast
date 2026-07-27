fn coverage_dimension(partial: bool) -> WorkspaceCoverageDimension {
    if partial {
        WorkspaceCoverageDimension::Partial
    } else {
        WorkspaceCoverageDimension::Complete
    }
}

fn increment_limitation(
    limitations: &mut BTreeMap<WorkspaceInventoryLimitationCode, usize>,
    code: WorkspaceInventoryLimitationCode,
    count: usize,
) {
    if count > 0 {
        let updated = limitations
            .get(&code)
            .copied()
            .unwrap_or_default()
            .saturating_add(count);
        limitations.insert(code, updated);
    }
}

fn unavailable(detail: String) -> WorkspaceIndexRead {
    WorkspaceIndexRead::Unavailable(WorkspaceIndexReadFailure::new(
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable,
        detail,
    ))
}

fn incompatible(detail: String) -> WorkspaceIndexRead {
    WorkspaceIndexRead::Incompatible(WorkspaceIndexReadFailure::new(
        WorkspaceInventoryLimitationCode::SourceIndexIncompatible,
        detail,
    ))
}

fn incompatible_sql(error: rusqlite::Error) -> ReadDatabaseError {
    ReadDatabaseError::Incompatible(error.to_string())
}

#[derive(Debug)]
enum ReadDatabaseError {
    Unavailable(String),
    Incompatible(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PathContainment {
    Contained,
    Outside,
    Unprovable,
}

#[derive(Debug)]
struct ManifestRow {
    key: FileKey,
    filename: String,
    dir_path: Option<String>,
    metadata_present: bool,
    package_state: Option<String>,
    package_unproven_reason: Option<String>,
    package_fq_id: Option<i64>,
    package_fq_name: Option<String>,
    legacy_source_set: Option<String>,
}

#[derive(Debug)]
struct TableColumn {
    not_null: bool,
    primary_key_position: i64,
}

#[derive(Debug)]
struct ForeignKeyColumn {
    id: i64,
    sequence: i64,
    target_table: String,
    from_column: String,
    to_column: String,
    delete_action: String,
}

#[derive(Debug, Default)]
struct AssociationRows {
    projects: BTreeMap<FileKey, BTreeSet<BuildQualifiedGradleProjectIdentity>>,
    invalid_projects: BTreeMap<FileKey, usize>,
    source_sets: BTreeMap<FileKey, BTreeSet<BuildQualifiedGradleSourceSetIdentity>>,
    invalid_source_sets: BTreeMap<FileKey, usize>,
}
