use std::path::Path;

use rusqlite::params;

use super::super::model::{
    WorkspaceFileDrift, WorkspaceIndexRead, WorkspaceInventoryLimitationCode, WorkspaceRoot,
};
use super::super::workspace_files_test_support::WorkspaceIndexFixture;
use super::read_workspace_index;

fn fixture() -> (tempfile::TempDir, WorkspaceRoot, WorkspaceIndexFixture) {
    let temp = tempfile::tempdir().expect("workspace tempdir");
    let git_status = std::process::Command::new("git")
        .args(["init", "-q"])
        .current_dir(temp.path())
        .status()
        .expect("git init");
    assert!(git_status.success(), "fixture Git repository");
    let root = WorkspaceRoot::try_from(temp.path()).expect("canonical workspace root");
    let database_path = crate::config::workspace_database_path(root.as_path())
        .expect("authoritative workspace database path");
    let fixture = WorkspaceIndexFixture::at_database_path(root.as_path(), database_path.as_path());
    (temp, root, fixture)
}

fn insert_proven_root_metadata(fixture: &WorkspaceIndexFixture, prefix_id: i64, filename: &str) {
    fixture
        .connection()
        .execute(
            "INSERT INTO file_metadata(prefix_id, filename, package_state, package_unproven_reason) VALUES (?, ?, 'PROVEN_ROOT', NULL)",
            params![prefix_id, filename],
        )
        .expect("root package metadata");
    fixture.insert_project_evidence(prefix_id, filename, ".", ":app", "main");
}

#[test]
fn production_absolute_prefix_is_excluded_instead_of_reinterpreted_inside_the_workspace() {
    let (_temp, root, fixture) = fixture();
    let outside = tempfile::tempdir().expect("outside tempdir");
    let outside_file = outside.path().join("Outside.kt");
    std::fs::write(&outside_file, "package outside\n").expect("outside Kotlin source");
    let encoded_dir = format!(
        "__kast_abs__/{}",
        outside.path().to_str().expect("UTF-8 outside path")
    );
    fixture.insert_manifest_file(2, &encoded_dir, "Outside.kt", false);
    fixture.seed_progress("app", "COMPLETE", 1, 1);

    let read = read_workspace_index(&root);
    let WorkspaceIndexRead::Snapshot(snapshot) = read else {
        panic!("encoded outside path should produce a partial snapshot: {read:?}");
    };

    assert!(snapshot.files().is_empty());
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::OutOfRootExcluded),
        1
    );
}

#[test]
fn production_relative_escape_decodes_one_layer_before_containment() {
    let (_temp, root, fixture) = fixture();
    let relative = Path::new("__kast_abs__/legitimate/Inside.kt");
    let absolute = fixture.workspace_root().join(relative);
    std::fs::create_dir_all(absolute.parent().expect("inside parent")).expect("inside parent");
    std::fs::write(&absolute, "class Inside\n").expect("inside Kotlin source");
    fixture.insert_manifest_file(
        2,
        "__kast_rel__/__kast_abs__/legitimate",
        "Inside.kt",
        false,
    );
    insert_proven_root_metadata(&fixture, 2, "Inside.kt");
    fixture.seed_progress("app", "COMPLETE", 1, 1);

    let read = read_workspace_index(&root);
    let WorkspaceIndexRead::Snapshot(snapshot) = read else {
        panic!("escaped relative path should remain readable: {read:?}");
    };

    assert_eq!(snapshot.files().len(), 1);
    assert_eq!(snapshot.files()[0].path().as_path(), relative);
    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::InSync);
}

#[test]
fn claimed_current_schema_with_duplicate_manifest_authority_fails_closed() {
    let (_temp, root, fixture) = fixture();
    let source = fixture.workspace_root().join("src/app/Duplicate.kt");
    std::fs::create_dir_all(source.parent().expect("source parent")).expect("source parent");
    std::fs::write(&source, "class Duplicate\n").expect("source");
    fixture
        .connection()
        .execute_batch(
            r#"
            DROP TABLE file_manifest;
            CREATE TABLE file_manifest (
                prefix_id INTEGER NOT NULL,
                filename TEXT NOT NULL,
                last_modified_millis INTEGER NOT NULL
            );
            INSERT INTO path_prefixes(prefix_id, dir_path) VALUES (2, 'src/app');
            INSERT INTO file_manifest VALUES (2, 'Duplicate.kt', 1);
            INSERT INTO file_manifest VALUES (2, 'Duplicate.kt', 1);
            "#,
        )
        .expect("duplicate manifest schema");
    insert_proven_root_metadata(&fixture, 2, "Duplicate.kt");
    fixture.seed_progress("app", "COMPLETE", 1, 1);

    let read = read_workspace_index(&root);
    assert!(
        matches!(read, WorkspaceIndexRead::Incompatible(_)),
        "{read:?}"
    );
}

#[test]
fn claimed_current_schema_with_nondeterministic_consumed_shapes_fails_closed() {
    let corruptions = [
        (
            "schema version nullability",
            r#"
            ALTER TABLE schema_version RENAME TO original_schema_version;
            CREATE TABLE schema_version (version INTEGER, generation INTEGER);
            INSERT INTO schema_version(version, generation)
                SELECT version, generation FROM original_schema_version;
            DROP TABLE original_schema_version;
            "#,
        ),
        (
            "path prefix identity",
            r#"
            DROP TABLE path_prefixes;
            CREATE TABLE path_prefixes (
                prefix_id INTEGER PRIMARY KEY,
                dir_path TEXT NOT NULL
            );
            "#,
        ),
        (
            "fq name identity",
            r#"
            PRAGMA foreign_keys=OFF;
            DROP TABLE fq_names;
            CREATE TABLE fq_names (
                fq_id INTEGER PRIMARY KEY,
                fq_name TEXT NOT NULL
            );
            "#,
        ),
        (
            "module progress identity",
            r#"
            DROP TABLE module_index_progress;
            CREATE TABLE module_index_progress (
                module_name TEXT PRIMARY KEY,
                phase2_status TEXT NOT NULL,
                indexed_file_count INTEGER NOT NULL,
                total_file_count INTEGER NOT NULL
            );
            "#,
        ),
        (
            "pending applied nullability",
            r#"
            DROP TABLE pending_updates;
            CREATE TABLE pending_updates (applied INTEGER);
            "#,
        ),
    ];

    for (label, corruption) in corruptions {
        let (_temp, root, fixture) = fixture();
        fixture
            .connection()
            .execute_batch(corruption)
            .unwrap_or_else(|error| panic!("{label}: {error}"));

        let read = read_workspace_index(&root);
        assert!(
            matches!(read, WorkspaceIndexRead::Incompatible(_)),
            "{label}: {read:?}"
        );
    }
}

#[test]
fn claimed_current_schema_with_invalid_pending_applied_state_fails_closed() {
    let (_temp, root, fixture) = fixture();
    fixture
        .connection()
        .execute(
            "INSERT INTO pending_updates(op, prefix_id, filename, epoch_ms, applied) VALUES ('upsert_file', 1, 'Invalid.kt', 1, 2)",
            [],
        )
        .expect("invalid applied state");

    assert!(matches!(
        read_workspace_index(&root),
        WorkspaceIndexRead::Incompatible(_)
    ));
}
