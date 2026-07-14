#[path = "../../tests/support/workspace_files.rs"]
mod workspace_files_support;

use std::path::Path;

use rusqlite::params;
use workspace_files_support::WorkspaceIndexFixture;

use super::model::{
    WorkspaceCoverageDimension, WorkspaceFileDrift, WorkspaceFileIndexState, WorkspaceIndexRead,
    WorkspaceIndexSnapshot, WorkspaceInventoryLimitationCode, WorkspaceMatchCoverage,
    WorkspacePackageEvidence, WorkspacePackageInvalidReference, WorkspacePackageUnprovenReason,
    WorkspaceRoot, WorkspaceSourceSetEvidence,
};
use super::read_workspace_index;

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

#[test]
fn read_workspace_index_returns_every_kotlin_source_without_a_public_cap() {
    let (_temp, root, fixture) = fixture();
    fixture.seed_high_cardinality_sources(500);
    fixture.seed_non_source_manifest_rows();
    fixture.seed_exact_progress();

    let snapshot = snapshot(&root);

    assert_eq!(snapshot.files().len(), 500);
    assert_eq!(snapshot.coverage(), WorkspaceMatchCoverage::complete());
    assert!(snapshot.stamp().is_exact());
    assert_eq!(snapshot.stamp().generation().value(), 41);
}

#[test]
fn package_evidence_preserves_every_discriminated_schema_state() {
    let (_temp, root, fixture) = fixture();
    for (prefix, filename) in [
        (1, "Root.kt"),
        (2, "Named.kt"),
        (3, "Unproven.kt"),
        (4, "Missing.kt"),
    ] {
        fixture.insert_manifest_file(prefix, &format!("src/p{prefix}"), filename, true);
    }
    fixture
        .connection()
        .execute(
            "INSERT INTO file_metadata(prefix_id, filename, package_state, package_unproven_reason) VALUES (1, 'Root.kt', 'PROVEN_ROOT', NULL)",
            [],
        )
        .expect("root package metadata");
    insert_named_metadata(&fixture, 2, "Named.kt", 2, "com.example.`when`.Δ", None);
    fixture
        .connection()
        .execute(
            "INSERT INTO file_metadata(prefix_id, filename, package_state, package_unproven_reason) VALUES (3, 'Unproven.kt', 'UNPROVEN', 'SEMANTIC_ANALYSIS_UNAVAILABLE')",
            [],
        )
        .expect("unproven package metadata");
    for (prefix, filename) in [(1, "Root.kt"), (2, "Named.kt"), (3, "Unproven.kt")] {
        fixture.insert_project_evidence(prefix, filename, ".", ":app", "main");
    }
    fixture.seed_progress("app", "COMPLETE", 4, 4);

    let snapshot = snapshot(&root);

    assert!(matches!(
        file(&snapshot, "src/p1/Root.kt").package(),
        WorkspacePackageEvidence::ProvenRoot
    ));
    assert!(matches!(
        file(&snapshot, "src/p2/Named.kt").package(),
        WorkspacePackageEvidence::ProvenNamed(name)
            if name.as_str() == "com.example.`when`.Δ"
    ));
    assert_eq!(
        file(&snapshot, "src/p3/Unproven.kt").package(),
        &WorkspacePackageEvidence::Unproven(
            WorkspacePackageUnprovenReason::SemanticAnalysisUnavailable
        )
    );
    assert_eq!(
        file(&snapshot, "src/p4/Missing.kt").package(),
        &WorkspacePackageEvidence::Unavailable
    );
}

#[test]
fn malformed_and_dangling_package_rows_never_become_partial_proof() {
    let (_temp, root, fixture) = fixture();
    for (prefix, filename) in [
        (1, "IllegalRoot.kt"),
        (2, "Dangling.kt"),
        (3, "Unknown.kt"),
        (4, "MissingReason.kt"),
    ] {
        fixture.insert_manifest_file(prefix, &format!("src/p{prefix}"), filename, true);
    }
    let connection = fixture.connection();
    connection
        .execute_batch("PRAGMA ignore_check_constraints=ON; PRAGMA foreign_keys=OFF;")
        .expect("malformed fixture mode");
    for sql in [
        "INSERT INTO file_metadata(prefix_id, filename, package_fq_id, package_state) VALUES (1, 'IllegalRoot.kt', 1, 'PROVEN_ROOT')",
        "INSERT INTO file_metadata(prefix_id, filename, package_fq_id, package_state) VALUES (2, 'Dangling.kt', 999, 'PROVEN_NAMED')",
        "INSERT INTO file_metadata(prefix_id, filename, package_state) VALUES (3, 'Unknown.kt', 'UNKNOWN')",
        "INSERT INTO file_metadata(prefix_id, filename, package_state) VALUES (4, 'MissingReason.kt', 'UNPROVEN')",
    ] {
        connection.execute(sql, []).expect("malformed package row");
    }
    drop(connection);
    for (prefix, filename) in [
        (1, "IllegalRoot.kt"),
        (2, "Dangling.kt"),
        (3, "Unknown.kt"),
        (4, "MissingReason.kt"),
    ] {
        fixture.insert_project_evidence(prefix, filename, ".", ":app", "main");
    }
    fixture.seed_progress("app", "COMPLETE", 4, 4);

    let snapshot = snapshot(&root);

    assert!(matches!(
        file(&snapshot, "src/p1/IllegalRoot.kt").package(),
        WorkspacePackageEvidence::InvalidReference(
            WorkspacePackageInvalidReference::IllegalStateTuple
        )
    ));
    assert!(matches!(
        file(&snapshot, "src/p2/Dangling.kt").package(),
        WorkspacePackageEvidence::InvalidReference(
            WorkspacePackageInvalidReference::DanglingFqName
        )
    ));
    assert!(matches!(
        file(&snapshot, "src/p3/Unknown.kt").package(),
        WorkspacePackageEvidence::InvalidReference(WorkspacePackageInvalidReference::InvalidState)
    ));
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::PackageMetadataInvalid),
        4
    );
}

#[test]
fn build_qualified_projects_distinguish_root_and_included_app_and_ignore_legacy_labels() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "quality/kotlin", "Shared.kt", true);
    insert_named_metadata(&fixture, 1, "Shared.kt", 2, "sample.shared", Some("main"));
    fixture.insert_project_evidence(1, "Shared.kt", ".", ":app", "integrationTest");
    fixture.insert_project_evidence(1, "Shared.kt", "included", ":app", "integrationTest");
    fixture.seed_progress("app", "COMPLETE", 1, 1);

    let snapshot = snapshot(&root);
    let shared = file(&snapshot, "quality/kotlin/Shared.kt");
    let project_roots: Vec<_> = shared
        .indexed_gradle_projects()
        .iter()
        .map(|project| project.build_root().as_path().to_path_buf())
        .collect();
    let WorkspaceSourceSetEvidence::Proven(source_sets) = shared.source_sets() else {
        panic!("structured source sets must remain proven");
    };

    assert_eq!(
        project_roots,
        [
            Path::new("").to_path_buf(),
            Path::new("included").to_path_buf()
        ]
    );
    assert_eq!(source_sets.len(), 2);
    assert!(
        source_sets
            .iter()
            .all(|identity| identity.source_set_name().as_str() == "integrationTest")
    );
    assert!(
        shared
            .indexed_gradle_projects()
            .iter()
            .all(|identity| identity.project_path().as_str() == ":app")
    );
}

#[test]
fn legacy_module_and_source_set_labels_remain_unproven() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/legacy", "Legacy.kt", true);
    insert_named_metadata(&fixture, 1, "Legacy.kt", 2, "sample.legacy", Some("main"));
    fixture.seed_progress("legacy", "COMPLETE", 1, 1);

    let snapshot = snapshot(&root);
    let legacy = file(&snapshot, "src/legacy/Legacy.kt");

    assert!(legacy.indexed_gradle_projects().is_empty());
    assert!(matches!(
        legacy.source_sets(),
        WorkspaceSourceSetEvidence::Unproven(labels)
            if labels.iter().any(|label| label.as_str() == "main")
    ));
}

#[test]
fn malformed_associations_discard_only_the_affected_proof_sets() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/app", "BrokenOwner.kt", true);
    insert_named_metadata(&fixture, 1, "BrokenOwner.kt", 2, "sample.owner", None);
    fixture.insert_project_evidence(1, "BrokenOwner.kt", ".", ":app", "main");
    let connection = fixture.connection();
    connection
        .execute_batch("PRAGMA foreign_keys=OFF;")
        .expect("malformed association mode");
    connection
        .execute(
            "INSERT INTO file_gradle_projects(prefix_id, filename, build_root, project_path) VALUES (1, 'BrokenOwner.kt', '../outside', ':app')",
            [],
        )
        .expect("malformed project association");
    connection
        .execute(
            "INSERT INTO file_gradle_source_sets(prefix_id, filename, build_root, project_path, source_set_name) VALUES (1, 'BrokenOwner.kt', '.', ':missing', 'integrationTest')",
            [],
        )
        .expect("dangling source-set association");
    drop(connection);
    fixture.seed_progress("app", "COMPLETE", 1, 1);

    let snapshot = snapshot(&root);
    let file = file(&snapshot, "src/app/BrokenOwner.kt");

    assert!(file.indexed_gradle_projects().is_empty());
    assert_eq!(file.source_sets(), &WorkspaceSourceSetEvidence::Unavailable);
    assert!(matches!(
        file.index_state(),
        WorkspaceFileIndexState::Incompatible(_)
    ));
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::SourceIndexIncompatible),
        2
    );
}

#[cfg(unix)]
#[test]
fn containment_admits_missing_in_root_leaf_and_excludes_escapes_and_unprovable_paths() {
    use std::os::unix::fs::symlink;

    let (_temp, root, fixture) = fixture();
    std::fs::create_dir_all(fixture.workspace_root().join("src/missing"))
        .expect("missing leaf parent");
    fixture.insert_manifest_file(1, "src/missing", "Missing.kt", false);
    insert_named_metadata(&fixture, 1, "Missing.kt", 2, "sample.missing", None);
    fixture.insert_project_evidence(1, "Missing.kt", ".", ":app", "main");

    let outside = tempfile::tempdir().expect("outside tempdir");
    std::fs::write(outside.path().join("Escape.kt"), "package outside\n").expect("outside source");
    symlink(outside.path(), fixture.workspace_root().join("linked")).expect("outside symlink");
    symlink(
        fixture.workspace_root().join("does-not-exist"),
        fixture.workspace_root().join("dangling"),
    )
    .expect("dangling symlink");
    fixture.insert_manifest_file(2, "../outside", "Lexical.kt", false);
    fixture.insert_manifest_file(
        3,
        outside.path().to_str().expect("outside utf8"),
        "Escape.kt",
        false,
    );
    fixture.insert_manifest_file(4, "linked", "Escape.kt", false);
    fixture.insert_manifest_file(5, "linked/missing", "Missing.kt", false);
    fixture.insert_manifest_file(6, "dangling", "Missing.kt", false);
    fixture.seed_progress("app", "COMPLETE", 6, 6);

    let snapshot = snapshot(&root);

    assert_eq!(snapshot.files().len(), 1);
    assert_eq!(
        snapshot.files()[0].drift(),
        WorkspaceFileDrift::MissingOnDisk
    );
    assert_eq!(
        snapshot.coverage().candidate_inventory(),
        WorkspaceCoverageDimension::Partial
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::OutOfRootExcluded),
        4
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::PathContainmentUnprovable),
        1
    );
}

#[test]
fn exactness_requires_complete_equal_progress_and_zero_unapplied_pending_updates() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/app", "Pending.kt", true);
    insert_named_metadata(&fixture, 1, "Pending.kt", 2, "sample.pending", None);
    fixture.insert_project_evidence(1, "Pending.kt", ".", ":app", "main");
    fixture.seed_progress("app", "COMPLETE", 1, 2);
    fixture.seed_pending_update("Pending.kt", false);
    fixture.seed_pending_update("Applied.kt", true);

    let snapshot = snapshot(&root);

    assert!(!snapshot.stamp().is_exact());
    assert_eq!(snapshot.stamp().pending_count().value(), 1);
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete),
        1
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::SourceIndexUpdatesPending),
        1
    );
}

#[test]
fn empty_progress_never_claims_an_exact_source_inventory() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/app", "Uninitialized.kt", true);
    insert_named_metadata(
        &fixture,
        1,
        "Uninitialized.kt",
        2,
        "sample.uninitialized",
        None,
    );
    fixture.insert_project_evidence(1, "Uninitialized.kt", ".", ":app", "main");

    let snapshot = snapshot(&root);

    assert!(!snapshot.stamp().is_exact());
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete),
        1
    );
}

#[test]
fn schema_version_fails_closed_before_rows_are_read() {
    let (_temp, root, fixture) = fixture();
    let prior_version = env!("KAST_SOURCE_INDEX_SCHEMA_VERSION")
        .parse::<i64>()
        .expect("schema version")
        - 1;
    fixture.set_schema_version(prior_version);

    let read = read_workspace_index(&root);

    assert!(matches!(read, WorkspaceIndexRead::Incompatible(_)));
}

#[test]
fn claimed_current_schema_missing_required_association_table_fails_closed() {
    let (_temp, root, fixture) = fixture();
    fixture.drop_required_table("file_gradle_source_sets");

    let read = read_workspace_index(&root);

    assert!(matches!(read, WorkspaceIndexRead::Incompatible(_)));
}

#[test]
fn claimed_current_schema_without_package_tuple_checks_fails_closed() {
    let (_temp, root, fixture) = fixture();
    fixture.replace_file_metadata_without_package_checks();

    let read = read_workspace_index(&root);

    assert!(matches!(read, WorkspaceIndexRead::Incompatible(_)));
}

#[test]
fn reader_leaves_generation_and_manifest_unchanged() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/app", "ReadOnly.kt", true);
    insert_named_metadata(&fixture, 1, "ReadOnly.kt", 2, "sample.readonly", None);
    fixture.insert_project_evidence(1, "ReadOnly.kt", ".", ":app", "main");
    fixture.seed_progress("app", "COMPLETE", 1, 1);

    let _snapshot = snapshot(&root);
    let connection = fixture.connection();
    let generation: i64 = connection
        .query_row("SELECT generation FROM schema_version", [], |row| {
            row.get(0)
        })
        .expect("generation");
    let manifest_count: i64 = connection
        .query_row("SELECT COUNT(*) FROM file_manifest", [], |row| row.get(0))
        .expect("manifest count");

    assert_eq!(generation, 41);
    assert_eq!(manifest_count, 1);
}

#[test]
fn missing_database_is_a_typed_unavailable_read() {
    let temp = tempfile::tempdir().expect("workspace tempdir");
    let git_status = std::process::Command::new("git")
        .args(["init", "-q"])
        .current_dir(temp.path())
        .status()
        .expect("git init");
    assert!(git_status.success(), "fixture git repository");
    let root = WorkspaceRoot::try_from(temp.path()).expect("canonical workspace root");

    let WorkspaceIndexRead::Unavailable(failure) = read_workspace_index(&root) else {
        panic!("missing source-index database must be unavailable");
    };

    assert_eq!(
        failure.limitation(),
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable
    );
}
