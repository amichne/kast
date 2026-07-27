fn fixture() -> (tempfile::TempDir, WorkspaceRoot, WorkspaceIndexFixture) {
    let temp = tempfile::tempdir().expect("workspace tempdir");
    let git_status = std::process::Command::new("git")
        .args(["init", "-q"])
        .current_dir(temp.path())
        .status()
        .expect("git init");
    assert!(git_status.success(), "fixture git repository");
    let root = WorkspaceRoot::try_from(temp.path()).expect("canonical workspace root");
    let database_path = crate::config::workspace_database_path(root.as_path())
        .expect("authoritative workspace database path");
    let fixture = WorkspaceIndexFixture::at_database_path(root.as_path(), database_path.as_path());
    (temp, root, fixture)
}

fn snapshot(root: &WorkspaceRoot) -> WorkspaceIndexSnapshot {
    let read = read_workspace_index(root);
    let WorkspaceIndexRead::Snapshot(snapshot) = read else {
        panic!("expected readable workspace snapshot, found {read:?}");
    };
    snapshot
}

fn insert_named_metadata(
    fixture: &WorkspaceIndexFixture,
    prefix_id: i64,
    filename: &str,
    fq_id: i64,
    fq_name: &str,
    legacy_source_set: Option<&str>,
) {
    let connection = fixture.connection();
    connection
        .execute(
            "INSERT OR IGNORE INTO fq_names(fq_id, fq_name) VALUES (?, ?)",
            params![fq_id, fq_name],
        )
        .expect("package fq name");
    connection
        .execute(
            "INSERT INTO file_metadata(prefix_id, filename, package_fq_id, package_state, package_unproven_reason, module_path, source_set) VALUES (?, ?, ?, 'PROVEN_NAMED', NULL, 'idea.legacy.label', ?)",
            params![prefix_id, filename, fq_id, legacy_source_set],
        )
        .expect("named package metadata");
}

fn file<'a>(
    snapshot: &'a WorkspaceIndexSnapshot,
    path: &str,
) -> &'a super::model::WorkspaceInventoryFile {
    snapshot
        .files()
        .iter()
        .find(|file| file.path().as_path() == Path::new(path))
        .unwrap_or_else(|| panic!("workspace file `{path}`"))
}
