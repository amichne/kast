use std::collections::VecDeque;
use std::path::Path;

use rusqlite::params;

use super::model::{
    BackendModuleCoverage, BackendWorkspaceCoverage, DirtyWorkspaceRead,
    WorkspaceCoverageDimension, WorkspaceFileDirtyState, WorkspaceFileDrift,
    WorkspaceFileIndexState, WorkspaceFilePath, WorkspaceIndexRead, WorkspaceIndexReadFailure,
    WorkspaceIndexSnapshot, WorkspaceInventoryLimitationCode, WorkspaceMatchCoverage,
    WorkspacePackageEvidence, WorkspacePackageInvalidReference, WorkspacePackageUnprovenReason,
    WorkspaceRequestedKindDomain, WorkspaceRoot, WorkspaceSourceSetEvidence,
};
use super::read_workspace_index;
use super::workspace_files_test_support::WorkspaceIndexFixture;

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

#[test]
fn persisted_semantic_package_names_allow_keywords_without_reparsing_source_syntax() {
    for accepted in ["when", "sample.when", "Ⅻvalue", "²value", "sample/semantic"] {
        let parsed = super::model::KotlinPackageFqName::parse_persisted(accepted.to_string())
            .unwrap_or_else(|| panic!("semantic package name `{accepted}`"));
        assert_eq!(parsed.as_str(), accepted);
    }
    for rejected in [
        "", " sample", "sample ", ".sample", "sample.", "a..b", "a\nb",
    ] {
        assert!(
            super::model::KotlinPackageFqName::parse_persisted(rejected.to_string()).is_none(),
            "invalid semantic package name `{rejected}`"
        );
    }
}

#[test]
fn persisted_gradle_build_roots_reject_drives_controls_and_non_normalized_paths() {
    for accepted in [".", "included", "included/tools"] {
        assert!(
            super::model::WorkspaceRelativeGradleBuildRoot::parse(accepted.to_string()).is_some(),
            "valid build root `{accepted}`"
        );
    }
    for rejected in [
        "C:included",
        "C:/included",
        "included\\tools",
        "/included",
        "included/../tools",
        "included//tools",
        "included\ntools",
    ] {
        assert!(
            super::model::WorkspaceRelativeGradleBuildRoot::parse(rejected.to_string()).is_none(),
            "invalid build root `{rejected}`"
        );
    }
}

struct ScriptedWorkspaceBackend {
    responses: VecDeque<Result<serde_json::Value, super::backend::BackendRpcFailure>>,
    requests: Vec<serde_json::Value>,
}

impl ScriptedWorkspaceBackend {
    fn new(responses: Vec<Result<serde_json::Value, super::backend::BackendRpcFailure>>) -> Self {
        Self {
            responses: responses.into(),
            requests: Vec::new(),
        }
    }
}

impl super::backend::BackendWorkspaceRpc for ScriptedWorkspaceBackend {
    fn request(
        &mut self,
        request: serde_json::Value,
    ) -> Result<serde_json::Value, super::backend::BackendRpcFailure> {
        self.requests.push(request);
        self.responses
            .pop_front()
            .expect("scripted workspace backend response")
    }
}

fn backend_result(
    snapshot: &str,
    modules: Vec<serde_json::Value>,
) -> Result<serde_json::Value, super::backend::BackendRpcFailure> {
    Ok(serde_json::json!({
        "snapshotToken": snapshot,
        "modules": modules,
        "schemaVersion": 3
    }))
}

fn backend_module(
    name: &str,
    count: usize,
    files: &[&str],
    next: Option<&str>,
) -> serde_json::Value {
    backend_module_with_ownership(name, count, files, next, &[], &[], &[])
}

fn backend_module_with_ownership(
    name: &str,
    count: usize,
    files: &[&str],
    next: Option<&str>,
    source_roots: &[&str],
    content_roots: &[&str],
    dependencies: &[&str],
) -> serde_json::Value {
    serde_json::json!({
        "name": name,
        "sourceRoots": source_roots,
        "contentRoots": content_roots,
        "dependencyModuleNames": dependencies,
        "files": files,
        "returnedFileCount": files.len(),
        "filesTruncated": next.is_some(),
        "fileCount": count,
        "nextPageToken": next,
    })
}

#[test]
fn backend_pages_are_exhausted_in_opaque_cursor_order_and_shared_paths_keep_every_owner() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let snapshot = "snapshot-alpha";
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            snapshot,
            vec![
                backend_module("module-b", 2, &[], None),
                backend_module("module-a", 3, &[], None),
            ],
        ),
        backend_result(
            snapshot,
            vec![backend_module(
                "module-a",
                3,
                &["src/Shared.kt"],
                Some("opaque not an offset"),
            )],
        ),
        backend_result(
            snapshot,
            vec![backend_module("module-a", 3, &["src/A.kt"], Some("a-last"))],
        ),
        backend_result(
            snapshot,
            vec![backend_module("module-a", 3, &["src/Z.kt"], None)],
        ),
        backend_result(
            snapshot,
            vec![backend_module(
                "module-b",
                2,
                &["src/Shared.kt"],
                Some("b-last"),
            )],
        ),
        backend_result(
            snapshot,
            vec![backend_module("module-b", 2, &["src/B.kt"], None)],
        ),
        backend_result(snapshot, vec![]),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::Mixed,
        &mut backend,
    );

    let shared = inventory
        .files()
        .iter()
        .find(|(path, _)| path.as_path() == Path::new("src/Shared.kt"))
        .expect("shared path");
    let owners: Vec<_> = shared.1.iter().map(|owner| owner.as_str()).collect();
    let cursors: Vec<_> = backend
        .requests
        .iter()
        .filter_map(|request| request["params"]["pageToken"].as_str())
        .collect();

    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Complete);
    assert_eq!(inventory.files().len(), 4);
    assert_eq!(owners, ["module-a", "module-b"]);
    assert_eq!(cursors, ["opaque not an offset", "a-last", "b-last"]);
    assert!(
        backend.requests[1]["params"].get("pageToken").is_none(),
        "the first exact-module request is cursorless: {:?}",
        backend.requests[1]
    );
    assert!(
        inventory
            .modules()
            .values()
            .all(|module| module.coverage() == BackendModuleCoverage::Complete)
    );
}

#[test]
fn repeated_page_handle_makes_only_its_module_partial() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result("snapshot", vec![backend_module("module-a", 2, &[], None)]),
        backend_result(
            "snapshot",
            vec![backend_module("module-a", 2, &["src/A.kt"], Some("same"))],
        ),
        backend_result(
            "snapshot",
            vec![backend_module("module-a", 2, &["src/B.kt"], Some("same"))],
        ),
        backend_result("snapshot", vec![]),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut backend,
    );

    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert!(inventory.files().is_empty());
    assert_eq!(
        inventory
            .modules()
            .values()
            .next()
            .map(|module| module.coverage()),
        Some(BackendModuleCoverage::Partial)
    );
    assert_eq!(
        inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::BackendPageIncomplete),
        Some(&1)
    );
}

fn backend_api_failure(
    code: &str,
    reason: Option<&str>,
) -> Result<serde_json::Value, super::backend::BackendRpcFailure> {
    Err(super::backend::BackendRpcFailure::Api {
        code: code.to_string(),
        message: code.to_string(),
        reason: reason.map(str::to_string),
    })
}

#[test]
fn overlapping_or_short_module_pages_are_partial_and_never_publish_their_candidates() {
    for responses in [
        vec![
            backend_result("snapshot", vec![backend_module("module", 2, &[], None)]),
            backend_result(
                "snapshot",
                vec![backend_module("module", 2, &["src/A.kt"], Some("last"))],
            ),
            backend_result(
                "snapshot",
                vec![backend_module("module", 2, &["src/A.kt"], None)],
            ),
            backend_result("snapshot", vec![]),
        ],
        vec![
            backend_result("snapshot", vec![backend_module("module", 2, &[], None)]),
            backend_result(
                "snapshot",
                vec![backend_module("module", 2, &["src/A.kt"], None)],
            ),
            backend_result("snapshot", vec![]),
        ],
    ] {
        let temp = tempfile::tempdir().expect("workspace");
        let root = WorkspaceRoot::try_from(temp.path()).expect("root");
        let mut backend = ScriptedWorkspaceBackend::new(responses);

        let inventory = super::backend::collect_backend_inventory(
            &root,
            WorkspaceRequestedKindDomain::SourceOnly,
            &mut backend,
        );

        assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
        assert!(inventory.files().is_empty());
    }
}

#[test]
fn generic_page_failure_is_local_to_the_requested_module() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            "snapshot",
            vec![
                backend_module("module-a", 1, &[], None),
                backend_module("module-b", 1, &[], None),
            ],
        ),
        Err(super::backend::BackendRpcFailure::Transport(
            "module-a unavailable".to_string(),
        )),
        backend_result(
            "snapshot",
            vec![backend_module("module-b", 1, &["src/B.kt"], None)],
        ),
        backend_result("snapshot", vec![]),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut backend,
    );

    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert_eq!(inventory.files().len(), 1);
    assert_eq!(
        inventory
            .modules()
            .iter()
            .map(|(name, module)| (name.as_str(), module.coverage()))
            .collect::<Vec<_>>(),
        [
            ("module-a", BackendModuleCoverage::Partial),
            ("module-b", BackendModuleCoverage::Complete)
        ]
    );
}

#[test]
fn second_stale_attempt_is_bounded_and_discards_all_stale_candidates() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            "old-snapshot",
            vec![backend_module("old-module", 1, &[], None)],
        ),
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
        backend_result(
            "new-snapshot",
            vec![backend_module("new-module", 1, &[], None)],
        ),
        backend_result(
            "new-snapshot",
            vec![backend_module("new-module", 1, &["src/New.kt"], None)],
        ),
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
    ]);

    let inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut backend,
    );

    assert_eq!(backend.requests.len(), 5, "no third metadata request");
    assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert!(inventory.files().is_empty());
    assert_eq!(
        inventory
            .modules()
            .keys()
            .map(|name| name.as_str())
            .collect::<Vec<_>>(),
        ["new-module"]
    );
    assert_eq!(
        inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::BackendWorkspaceInventoryStale),
        Some(&1)
    );
}

#[test]
fn project_model_metadata_failure_is_unavailable_and_page_failure_is_workspace_partial() {
    let temp = tempfile::tempdir().expect("workspace");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let mut metadata_failure = ScriptedWorkspaceBackend::new(vec![backend_api_failure(
        "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
        Some("PROJECT_MODEL_UNAVAILABLE"),
    )]);
    let unavailable = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::Mixed,
        &mut metadata_failure,
    );

    let mut page_failure = ScriptedWorkspaceBackend::new(vec![
        backend_result("snapshot", vec![backend_module("module", 1, &[], None)]),
        backend_api_failure(
            "WORKSPACE_PROJECT_MODEL_INCOMPLETE",
            Some("RUNTIME_INDEXING"),
        ),
    ]);
    let partial = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::Mixed,
        &mut page_failure,
    );

    assert_eq!(
        unavailable.coverage(),
        BackendWorkspaceCoverage::Unavailable
    );
    assert_eq!(partial.coverage(), BackendWorkspaceCoverage::Partial);
    assert!(partial.files().is_empty());
    assert_eq!(
        unavailable
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::ProjectModelUnavailable),
        Some(&1)
    );
    assert_eq!(
        partial
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::RuntimeIndexing),
        Some(&1)
    );
}

#[cfg(unix)]
#[test]
fn backend_paths_and_ownership_roots_require_canonical_containment() {
    use std::os::unix::fs::symlink;

    let temp = tempfile::tempdir().expect("workspace");
    let outside = tempfile::tempdir().expect("outside");
    std::fs::write(outside.path().join("Escape.kt"), "package outside\n").expect("outside file");
    symlink(outside.path(), temp.path().join("escape")).expect("escaping symlink");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");

    let mut escaping_file = ScriptedWorkspaceBackend::new(vec![
        backend_result("snapshot", vec![backend_module("module", 1, &[], None)]),
        backend_result(
            "snapshot",
            vec![backend_module("module", 1, &["escape/Escape.kt"], None)],
        ),
        backend_result("snapshot", vec![]),
    ]);
    let file_inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut escaping_file,
    );

    let escape_root = temp.path().join("escape").display().to_string();
    let mut escaping_root = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            "snapshot",
            vec![backend_module_with_ownership(
                "module",
                1,
                &[],
                None,
                &[escape_root.as_str()],
                &[],
                &[],
            )],
        ),
        backend_result(
            "snapshot",
            vec![backend_module_with_ownership(
                "module",
                1,
                &["Inside.kt"],
                None,
                &[escape_root.as_str()],
                &[],
                &[],
            )],
        ),
        backend_result("snapshot", vec![]),
    ]);
    let root_inventory = super::backend::collect_backend_inventory(
        &root,
        WorkspaceRequestedKindDomain::SourceOnly,
        &mut escaping_root,
    );

    assert!(file_inventory.files().is_empty());
    assert_eq!(file_inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert_eq!(
        file_inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::PathContainmentUnprovable),
        Some(&1)
    );
    assert_eq!(root_inventory.coverage(), BackendWorkspaceCoverage::Partial);
    assert_eq!(
        escaping_root.requests.len(),
        3,
        "valid module files are still paged"
    );
    assert_eq!(root_inventory.files().len(), 1);
    assert_eq!(
        root_inventory
            .limitations()
            .get(&WorkspaceInventoryLimitationCode::PathContainmentUnprovable),
        Some(&1)
    );
}

#[test]
fn module_fingerprint_retains_sorted_content_roots_and_dependencies() {
    let temp = tempfile::tempdir().expect("workspace");
    std::fs::create_dir_all(temp.path().join("src")).expect("source root");
    std::fs::create_dir_all(temp.path().join("content-a")).expect("content a");
    std::fs::create_dir_all(temp.path().join("content-b")).expect("content b");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    let collect = |content_root: &str| {
        let mut backend = ScriptedWorkspaceBackend::new(vec![
            backend_result(
                "same-token",
                vec![backend_module_with_ownership(
                    "module",
                    0,
                    &[],
                    None,
                    &["src"],
                    &[content_root],
                    &["dependency"],
                )],
            ),
            backend_result("same-token", vec![]),
        ]);
        super::backend::collect_backend_inventory(
            &root,
            WorkspaceRequestedKindDomain::SourceOnly,
            &mut backend,
        )
    };

    let first = collect("content-a");
    let second = collect("content-b");

    assert_ne!(first.stamp(), second.stamp());
    let module = first.modules().values().next().expect("module");
    assert_eq!(
        module.content_roots().iter().collect::<Vec<_>>(),
        [Path::new("content-a")]
    );
    assert_eq!(
        module
            .dependency_module_names()
            .iter()
            .map(|name| name.as_str())
            .collect::<Vec<_>>(),
        ["dependency"]
    );
}

#[test]
fn unsorted_or_duplicate_module_fingerprint_metadata_fails_closed() {
    for source_roots in [vec!["src-b", "src-a"], vec!["src-a", "src-a"]] {
        let temp = tempfile::tempdir().expect("workspace");
        std::fs::create_dir_all(temp.path().join("src-a")).expect("src a");
        std::fs::create_dir_all(temp.path().join("src-b")).expect("src b");
        let root = WorkspaceRoot::try_from(temp.path()).expect("root");
        let mut backend = ScriptedWorkspaceBackend::new(vec![backend_result(
            "snapshot",
            vec![backend_module_with_ownership(
                "module",
                0,
                &[],
                None,
                &source_roots,
                &[],
                &[],
            )],
        )]);

        let inventory = super::backend::collect_backend_inventory(
            &root,
            WorkspaceRequestedKindDomain::SourceOnly,
            &mut backend,
        );

        assert_eq!(inventory.coverage(), BackendWorkspaceCoverage::Unavailable);
        assert!(inventory.files().is_empty());
    }
}

fn git(workdir: &Path, args: &[&str]) {
    let output = std::process::Command::new("git")
        .args(args)
        .current_dir(workdir)
        .output()
        .expect("git fixture command");
    assert!(
        output.status.success(),
        "git {args:?}: stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

#[test]
fn nested_git_mapping_overrides_relative_paths_and_maps_only_workspace_records() {
    let temp = tempfile::tempdir().expect("repository");
    let repository = temp.path();
    let workspace = repository.join("nested/workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    git(repository, &["init", "-q"]);
    git(repository, &["config", "user.email", "fixture@example.com"]);
    git(repository, &["config", "user.name", "Fixture"]);
    git(repository, &["config", "status.relativePaths", "true"]);
    for path in ["Modified.kt", "Deleted.kt", "Old.kt"] {
        std::fs::write(workspace.join(path), "before\n").expect("tracked file");
    }
    std::fs::write(repository.join("Outside.kt"), "before\n").expect("outside file");
    git(repository, &["add", "."]);
    git(repository, &["commit", "-qm", "fixture"]);

    std::fs::write(workspace.join("Modified.kt"), "after\n").expect("modified");
    std::fs::remove_file(workspace.join("Deleted.kt")).expect("deleted");
    std::fs::rename(workspace.join("Old.kt"), workspace.join("New.kt")).expect("renamed");
    std::fs::write(workspace.join("Added.kt"), "added\n").expect("added");
    std::fs::write(workspace.join("Untracked.kt"), "untracked\n").expect("untracked");
    std::fs::write(repository.join("Outside.kt"), "after\n").expect("outside modified");
    git(
        repository,
        &[
            "add",
            "-A",
            "--",
            "nested/workspace/Old.kt",
            "nested/workspace/New.kt",
            "nested/workspace/Added.kt",
        ],
    );
    let root = WorkspaceRoot::try_from(workspace.as_path()).expect("root");

    let DirtyWorkspaceRead::Snapshot(snapshot) = super::dirty::read_dirty_workspace(&root) else {
        panic!("nested Git workspace must be readable");
    };
    let dirty: Vec<_> = snapshot
        .stamp()
        .dirty_paths()
        .iter()
        .map(|path| path.as_path().to_path_buf())
        .collect();

    assert_eq!(
        dirty,
        [
            Path::new("Added.kt").to_path_buf(),
            Path::new("Deleted.kt").to_path_buf(),
            Path::new("Modified.kt").to_path_buf(),
            Path::new("New.kt").to_path_buf(),
            Path::new("Old.kt").to_path_buf(),
            Path::new("Untracked.kt").to_path_buf(),
        ]
    );
    let clean = WorkspaceFilePath::from_relative_path(Path::new("Clean.kt").to_path_buf())
        .expect("clean path");
    assert_eq!(snapshot.state_for(&clean), WorkspaceFileDirtyState::Clean);
}

#[test]
fn porcelain_v2_maps_conflicts_and_each_contained_rename_endpoint() {
    let status = b"u UU N... 100644 100644 100644 100644 aaaaaaa bbbbbbb ccccccc nested/workspace/Conflict.kt\0\
2 R. N... 100644 100644 100644 aaaaaaa bbbbbbb R100 nested/workspace/Inside.kt\0outside/Before.kt\0\
2 R. N... 100644 100644 100644 aaaaaaa bbbbbbb R100 outside/After.kt\0nested/workspace/Before.kt\0";

    let paths = super::dirty::parse_porcelain_v2(status, Path::new("nested/workspace"))
        .expect("porcelain v2");
    let paths: Vec<_> = paths
        .iter()
        .map(|path| path.as_path().to_path_buf())
        .collect();

    assert_eq!(
        paths,
        [
            Path::new("Before.kt").to_path_buf(),
            Path::new("Conflict.kt").to_path_buf(),
            Path::new("Inside.kt").to_path_buf(),
        ]
    );
}

fn complete_backend_responses(
    snapshot: &str,
    module_name: &str,
    source_roots: &[&str],
    content_roots: &[&str],
    files: &[&str],
) -> Vec<Result<serde_json::Value, super::backend::BackendRpcFailure>> {
    let mut responses = vec![backend_result(
        snapshot,
        vec![backend_module_with_ownership(
            module_name,
            files.len(),
            &[],
            None,
            source_roots,
            content_roots,
            &[],
        )],
    )];
    if !files.is_empty() {
        responses.push(backend_result(
            snapshot,
            vec![backend_module_with_ownership(
                module_name,
                files.len(),
                files,
                None,
                source_roots,
                content_roots,
                &[],
            )],
        ));
    }
    responses.push(backend_result(snapshot, vec![]));
    responses
}

#[test]
fn composition_distinguishes_scripts_filesystem_index_and_missing_drift() {
    let (_temp, root, fixture) = fixture();
    for (filename, create) in [
        ("Both.kt", true),
        ("IndexOnly.kt", true),
        ("Missing.kt", false),
    ] {
        fixture.insert_manifest_file(1, "src/main/kotlin/sample", filename, create);
        insert_named_metadata(&fixture, 1, filename, 1, "sample", None);
        fixture.insert_project_evidence(1, filename, ".", ":app", "main");
    }
    fixture.seed_progress("app", "COMPLETE", 3, 3);
    std::fs::write(
        root.as_path().join("src/main/kotlin/sample/BackendOnly.kt"),
        "package sample\n",
    )
    .expect("backend-only source");
    std::fs::write(root.as_path().join("build.gradle.kts"), "plugins {}\n").expect("script");
    let files = [
        "src/main/kotlin/sample/Both.kt",
        "src/main/kotlin/sample/BackendOnly.kt",
        "src/main/kotlin/sample/Missing.kt",
        "build.gradle.kts",
    ];
    let mut backend = ScriptedWorkspaceBackend::new(complete_backend_responses(
        "snapshot",
        "module",
        &["src/main/kotlin/sample"],
        &[],
        &files,
    ));
    let mut lanes = super::collect::SystemWorkspaceLaneReader;

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::Mixed,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");
    let drift = |path: &str| {
        snapshot
            .files()
            .iter()
            .find(|file| file.path().as_path() == Path::new(path))
            .map(|file| (file.index_state().clone(), file.drift()))
            .unwrap_or_else(|| panic!("composed file {path}"))
    };

    assert_eq!(
        drift("build.gradle.kts"),
        (
            WorkspaceFileIndexState::NotApplicable,
            WorkspaceFileDrift::NotApplicable
        )
    );
    assert_eq!(
        drift("src/main/kotlin/sample/BackendOnly.kt").1,
        WorkspaceFileDrift::FilesystemOnly
    );
    assert_eq!(
        drift("src/main/kotlin/sample/IndexOnly.kt").1,
        WorkspaceFileDrift::IndexOnly
    );
    assert_eq!(
        drift("src/main/kotlin/sample/Both.kt").1,
        WorkspaceFileDrift::InSync
    );
    assert_eq!(
        drift("src/main/kotlin/sample/Missing.kt").1,
        WorkspaceFileDrift::MissingOnDisk
    );
    assert_eq!(
        snapshot.coverage().candidate_inventory(),
        WorkspaceCoverageDimension::Complete
    );
}

#[test]
fn partial_possible_owner_makes_index_only_drift_unknown() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/main/kotlin/sample", "IndexOnly.kt", true);
    insert_named_metadata(&fixture, 1, "IndexOnly.kt", 1, "sample", None);
    fixture.insert_project_evidence(1, "IndexOnly.kt", ".", ":app", "main");
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_result(
            "snapshot",
            vec![
                backend_module_with_ownership(
                    "complete-owner",
                    0,
                    &[],
                    None,
                    &["src/main/kotlin/sample"],
                    &[],
                    &[],
                ),
                backend_module_with_ownership(
                    "partial-owner",
                    1,
                    &[],
                    None,
                    &[],
                    &["src/main/kotlin/sample"],
                    &[],
                ),
            ],
        ),
        Err(super::backend::BackendRpcFailure::Transport(
            "partial owner page".to_string(),
        )),
        backend_result("snapshot", vec![]),
    ]);
    let mut lanes = super::collect::SystemWorkspaceLaneReader;

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::Unknown);
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::ProjectModelOwnershipUnknown),
        1
    );
}

#[test]
fn workspace_wide_stale_with_zero_modules_never_claims_index_only() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/main/kotlin/sample", "IndexOnly.kt", true);
    insert_named_metadata(&fixture, 1, "IndexOnly.kt", 1, "sample", None);
    fixture.insert_project_evidence(1, "IndexOnly.kt", ".", ":app", "main");
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    let mut backend = ScriptedWorkspaceBackend::new(vec![
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
        backend_api_failure("STALE_WORKSPACE_INVENTORY", None),
    ]);
    let mut lanes = super::collect::SystemWorkspaceLaneReader;

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(backend.requests.len(), 2);
    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::Unknown);
    assert_eq!(
        snapshot.backend_coverage(),
        BackendWorkspaceCoverage::Partial
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::BackendWorkspaceInventoryStale),
        1
    );
}

struct MutatingIndexLaneReader {
    database_path: std::path::PathBuf,
    mutation_sql: &'static str,
    mutate_on_even_reads: usize,
    index_reads: usize,
    filesystem_reads: usize,
    dirty_reads: usize,
    inner: super::collect::SystemWorkspaceLaneReader,
}

impl MutatingIndexLaneReader {
    fn new(
        database_path: std::path::PathBuf,
        mutation_sql: &'static str,
        mutate_on_even_reads: usize,
    ) -> Self {
        Self {
            database_path,
            mutation_sql,
            mutate_on_even_reads,
            index_reads: 0,
            filesystem_reads: 0,
            dirty_reads: 0,
            inner: super::collect::SystemWorkspaceLaneReader,
        }
    }
}

impl super::collect::WorkspaceInventoryLaneReader for MutatingIndexLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        self.index_reads += 1;
        let even_observation = self.index_reads.is_multiple_of(2);
        let mutation_number = self.index_reads / 2;
        if even_observation && mutation_number <= self.mutate_on_even_reads {
            rusqlite::Connection::open(&self.database_path)
                .expect("mutation database")
                .execute_batch(self.mutation_sql)
                .expect("lane mutation");
        }
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        self.dirty_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        self.filesystem_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_filesystem(&mut self.inner, root, paths)
    }
}

fn barrier_fixture() -> (
    tempfile::TempDir,
    WorkspaceRoot,
    WorkspaceIndexFixture,
    Vec<Result<serde_json::Value, super::backend::BackendRpcFailure>>,
) {
    let (temp, root, fixture) = fixture();
    fixture.insert_manifest_file(1, "src/main/kotlin/sample", "Stable.kt", true);
    insert_named_metadata(&fixture, 1, "Stable.kt", 1, "sample", None);
    fixture.insert_project_evidence(1, "Stable.kt", ".", ":app", "main");
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    let responses = complete_backend_responses(
        "snapshot",
        "module",
        &["src/main/kotlin/sample"],
        &[],
        &["src/main/kotlin/sample/Stable.kt"],
    );
    (temp, root, fixture, responses)
}

#[test]
fn source_index_generation_movement_retries_the_whole_composition_once() {
    let (_temp, root, fixture, responses) = barrier_fixture();
    let mut backend = ScriptedWorkspaceBackend::new(
        responses
            .iter()
            .cloned()
            .chain(responses.iter().cloned())
            .collect(),
    );
    let mut lanes = MutatingIndexLaneReader::new(
        fixture.database_path().to_path_buf(),
        "UPDATE schema_version SET generation = generation + 1;",
        1,
    );

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(lanes.index_reads, 4);
    assert_eq!(backend.requests.len(), 6);
    assert!(snapshot.continuation_allowed());
    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::InSync);
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable),
        0
    );
}

#[test]
fn second_source_index_movement_returns_typed_unstable_partial_evidence() {
    let (_temp, root, fixture, responses) = barrier_fixture();
    let mut backend = ScriptedWorkspaceBackend::new(
        responses
            .iter()
            .cloned()
            .chain(responses.iter().cloned())
            .collect(),
    );
    let mut lanes = MutatingIndexLaneReader::new(
        fixture.database_path().to_path_buf(),
        "UPDATE schema_version SET generation = generation + 1;",
        2,
    );

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(lanes.index_reads, 4);
    assert_eq!(backend.requests.len(), 6);
    assert!(!snapshot.continuation_allowed());
    assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::Unknown);
    assert_eq!(
        snapshot.coverage().candidate_inventory(),
        WorkspaceCoverageDimension::Partial
    );
    assert_eq!(
        snapshot.kind_coverage().source(),
        Some(WorkspaceCoverageDimension::Partial)
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable),
        1
    );
}

#[test]
fn stable_incomplete_progress_and_pending_updates_do_not_spin() {
    for (mutation_sql, limitation) in [
        (
            "UPDATE module_index_progress SET total_file_count = 2;",
            WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete,
        ),
        (
            "INSERT INTO pending_updates(op, prefix_id, filename, epoch_ms, applied) VALUES ('upsert_file', 1, 'Stable.kt', 2, 0);",
            WorkspaceInventoryLimitationCode::SourceIndexUpdatesPending,
        ),
    ] {
        let (_temp, root, fixture, responses) = barrier_fixture();
        let mut backend = ScriptedWorkspaceBackend::new(
            responses
                .iter()
                .cloned()
                .chain(responses.iter().cloned())
                .collect(),
        );
        let mut lanes =
            MutatingIndexLaneReader::new(fixture.database_path().to_path_buf(), mutation_sql, 1);

        let snapshot =
            super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
                root,
                kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
                dirty_evidence_relevant: false,
                backend: &mut backend,
                lanes: &mut lanes,
            })
            .expect("composition");

        assert_eq!(lanes.index_reads, 4);
        assert_eq!(backend.requests.len(), 6);
        assert_eq!(snapshot.limitation_count(limitation), 1);
        assert_eq!(
            snapshot.coverage().candidate_inventory(),
            WorkspaceCoverageDimension::Partial
        );
        assert_eq!(
            snapshot
                .limitation_count(WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable),
            0
        );
    }
}

struct ScriptedIndexLaneReader {
    index: VecDeque<WorkspaceIndexRead>,
    index_reads: usize,
    inner: super::collect::SystemWorkspaceLaneReader,
}

impl super::collect::WorkspaceInventoryLaneReader for ScriptedIndexLaneReader {
    fn read_source_index(&mut self, _root: &WorkspaceRoot) -> WorkspaceIndexRead {
        self.index_reads += 1;
        self.index.pop_front().expect("scripted index observation")
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        super::collect::WorkspaceInventoryLaneReader::read_filesystem(&mut self.inner, root, paths)
    }
}

#[test]
fn lane_availability_and_unavailable_reason_transitions_participate_in_the_barrier() {
    let (_temp, root, _fixture, responses) = barrier_fixture();
    let available = read_workspace_index(&root);
    let unavailable_a = WorkspaceIndexRead::Unavailable(WorkspaceIndexReadFailure::new(
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable,
        "reason-a".to_string(),
    ));
    let unavailable_b = WorkspaceIndexRead::Unavailable(WorkspaceIndexReadFailure::new(
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable,
        "reason-b".to_string(),
    ));
    for (observations, expected_coverage) in [
        (
            vec![
                available.clone(),
                unavailable_b.clone(),
                unavailable_b.clone(),
                unavailable_b.clone(),
            ],
            WorkspaceCoverageDimension::Partial,
        ),
        (
            vec![
                unavailable_b.clone(),
                available.clone(),
                available.clone(),
                available.clone(),
            ],
            WorkspaceCoverageDimension::Complete,
        ),
        (
            vec![
                unavailable_a.clone(),
                unavailable_b.clone(),
                unavailable_b.clone(),
                unavailable_b.clone(),
            ],
            WorkspaceCoverageDimension::Partial,
        ),
        (
            vec![
                unavailable_b.clone(),
                unavailable_a.clone(),
                unavailable_a.clone(),
                unavailable_a.clone(),
            ],
            WorkspaceCoverageDimension::Partial,
        ),
    ] {
        let mut backend = ScriptedWorkspaceBackend::new(
            responses
                .iter()
                .cloned()
                .chain(responses.iter().cloned())
                .collect(),
        );
        let mut lanes = ScriptedIndexLaneReader {
            index: observations.into(),
            index_reads: 0,
            inner: super::collect::SystemWorkspaceLaneReader,
        };

        let snapshot =
            super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
                root: root.clone(),
                kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
                dirty_evidence_relevant: false,
                backend: &mut backend,
                lanes: &mut lanes,
            })
            .expect("composition");

        assert_eq!(lanes.index_reads, 4);
        assert!(snapshot.continuation_allowed());
        assert_eq!(snapshot.coverage().candidate_inventory(), expected_coverage);
    }
}

struct CountingSystemLaneReader {
    index_reads: usize,
    filesystem_reads: usize,
    dirty_reads: usize,
    inner: super::collect::SystemWorkspaceLaneReader,
}

impl CountingSystemLaneReader {
    fn new() -> Self {
        Self {
            index_reads: 0,
            filesystem_reads: 0,
            dirty_reads: 0,
            inner: super::collect::SystemWorkspaceLaneReader,
        }
    }
}

impl super::collect::WorkspaceInventoryLaneReader for CountingSystemLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        self.index_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        self.dirty_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        self.filesystem_reads += 1;
        super::collect::WorkspaceInventoryLaneReader::read_filesystem(&mut self.inner, root, paths)
    }
}

#[test]
fn kind_relevance_skips_the_source_index_for_script_only_and_keeps_mixed_coverage_separate() {
    let temp = tempfile::tempdir().expect("workspace");
    std::fs::write(temp.path().join("Source.kt"), "class Source\n").expect("source");
    std::fs::write(temp.path().join("build.gradle.kts"), "plugins {}\n").expect("script");
    let root = WorkspaceRoot::try_from(temp.path()).expect("root");
    for (domain, files, expected_index_reads) in [
        (
            WorkspaceRequestedKindDomain::ScriptOnly,
            vec!["build.gradle.kts"],
            0,
        ),
        (
            WorkspaceRequestedKindDomain::SourceOnly,
            vec!["Source.kt"],
            2,
        ),
        (
            WorkspaceRequestedKindDomain::Mixed,
            vec!["Source.kt", "build.gradle.kts"],
            2,
        ),
    ] {
        let mut backend = ScriptedWorkspaceBackend::new(complete_backend_responses(
            "snapshot",
            "module",
            &[],
            &[],
            &files,
        ));
        let mut lanes = CountingSystemLaneReader::new();

        let snapshot =
            super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
                root: root.clone(),
                kind_domain: domain,
                dirty_evidence_relevant: false,
                backend: &mut backend,
                lanes: &mut lanes,
            })
            .expect("composition");

        assert_eq!(lanes.index_reads, expected_index_reads, "domain={domain:?}");
        assert_eq!(lanes.dirty_reads, 0, "domain={domain:?}");
        if domain.includes_scripts() {
            assert_eq!(
                snapshot.kind_coverage().script(),
                Some(WorkspaceCoverageDimension::Complete),
                "domain={domain:?}"
            );
        }
        if domain == WorkspaceRequestedKindDomain::Mixed {
            assert_eq!(
                snapshot.kind_coverage().source(),
                Some(WorkspaceCoverageDimension::Partial)
            );
        }
    }
}

struct MutatingFilesystemLaneReader {
    target: std::path::PathBuf,
    filesystem_reads: usize,
    inner: super::collect::SystemWorkspaceLaneReader,
}

impl super::collect::WorkspaceInventoryLaneReader for MutatingFilesystemLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        self.filesystem_reads += 1;
        if self.filesystem_reads == 2 {
            std::fs::remove_file(&self.target).expect("filesystem lane mutation");
        }
        super::collect::WorkspaceInventoryLaneReader::read_filesystem(&mut self.inner, root, paths)
    }
}

#[test]
fn filesystem_existence_movement_discards_the_attempt_and_retries_once() {
    let (_temp, root, _fixture, responses) = barrier_fixture();
    let target = root.as_path().join("src/main/kotlin/sample/Stable.kt");
    let mut backend = ScriptedWorkspaceBackend::new(
        responses
            .iter()
            .cloned()
            .chain(responses.iter().cloned())
            .collect(),
    );
    let mut lanes = MutatingFilesystemLaneReader {
        target,
        filesystem_reads: 0,
        inner: super::collect::SystemWorkspaceLaneReader,
    };

    let snapshot =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut backend,
            lanes: &mut lanes,
        })
        .expect("composition");

    assert_eq!(lanes.filesystem_reads, 4);
    assert_eq!(backend.requests.len(), 6);
    assert_eq!(
        snapshot.files()[0].drift(),
        WorkspaceFileDrift::MissingOnDisk
    );
    assert!(snapshot.continuation_allowed());
}

struct UnavailableFilesystemLaneReader {
    reason: super::model::WorkspaceLaneUnavailableReason,
    inner: super::collect::SystemWorkspaceLaneReader,
}

impl super::collect::WorkspaceInventoryLaneReader for UnavailableFilesystemLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        _root: &WorkspaceRoot,
        _paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        super::model::WorkspaceLaneStamp::Unavailable(self.reason.clone())
    }
}

#[test]
fn stable_filesystem_unavailability_retains_proven_backend_candidates_and_reason_identity() {
    let (_temp, root, _fixture, responses) = barrier_fixture();
    let mut digests = Vec::new();
    for reason in ["permission-denied", "observer-closed"] {
        let mut backend = ScriptedWorkspaceBackend::new(responses.clone());
        let mut lanes = UnavailableFilesystemLaneReader {
            reason: super::model::WorkspaceLaneUnavailableReason::new(reason),
            inner: super::collect::SystemWorkspaceLaneReader,
        };

        let snapshot =
            super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
                root: root.clone(),
                kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
                dirty_evidence_relevant: false,
                backend: &mut backend,
                lanes: &mut lanes,
            })
            .expect("composition");

        assert_eq!(snapshot.files().len(), 1);
        assert_eq!(snapshot.files()[0].drift(), WorkspaceFileDrift::Unknown);
        assert_eq!(
            snapshot.coverage().candidate_inventory(),
            WorkspaceCoverageDimension::Partial
        );
        assert!(snapshot.continuation_allowed());
        digests.push(snapshot.composition_digest().to_string());
    }
    assert_ne!(digests[0], digests[1]);
}

struct MutatingDirtyLaneReader {
    target: std::path::PathBuf,
    dirty_reads: usize,
    inner: super::collect::SystemWorkspaceLaneReader,
}

impl super::collect::WorkspaceInventoryLaneReader for MutatingDirtyLaneReader {
    fn read_source_index(&mut self, root: &WorkspaceRoot) -> WorkspaceIndexRead {
        super::collect::WorkspaceInventoryLaneReader::read_source_index(&mut self.inner, root)
    }

    fn read_dirty_workspace(&mut self, root: &WorkspaceRoot) -> DirtyWorkspaceRead {
        self.dirty_reads += 1;
        if self.dirty_reads == 2 {
            std::fs::write(&self.target, "package sample\n\nclass Changed\n")
                .expect("Git lane mutation");
        }
        super::collect::WorkspaceInventoryLaneReader::read_dirty_workspace(&mut self.inner, root)
    }

    fn read_filesystem(
        &mut self,
        root: &WorkspaceRoot,
        paths: &std::collections::BTreeSet<WorkspaceFilePath>,
    ) -> super::model::WorkspaceLaneStamp<super::model::WorkspaceFilesystemStamp> {
        super::collect::WorkspaceInventoryLaneReader::read_filesystem(&mut self.inner, root, paths)
    }
}

#[test]
fn git_movement_is_barrier_relevant_only_when_dirty_evidence_is_requested() {
    let (_temp, root, _fixture, responses) = barrier_fixture();
    git(
        root.as_path(),
        &["config", "user.email", "fixture@example.com"],
    );
    git(root.as_path(), &["config", "user.name", "Fixture"]);
    git(root.as_path(), &["add", "."]);
    git(root.as_path(), &["commit", "-qm", "fixture"]);
    let target = root.as_path().join("src/main/kotlin/sample/Stable.kt");

    let mut relevant_backend = ScriptedWorkspaceBackend::new(
        responses
            .iter()
            .cloned()
            .chain(responses.iter().cloned())
            .collect(),
    );
    let mut relevant_lanes = MutatingDirtyLaneReader {
        target: target.clone(),
        dirty_reads: 0,
        inner: super::collect::SystemWorkspaceLaneReader,
    };
    let relevant =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root: root.clone(),
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: true,
            backend: &mut relevant_backend,
            lanes: &mut relevant_lanes,
        })
        .expect("dirty-relevant composition");

    std::fs::write(&target, "package sample\n").expect("restore clean content");
    git(
        root.as_path(),
        &["checkout", "--", "src/main/kotlin/sample/Stable.kt"],
    );
    let mut irrelevant_backend = ScriptedWorkspaceBackend::new(responses);
    let mut irrelevant_lanes = MutatingDirtyLaneReader {
        target,
        dirty_reads: 0,
        inner: super::collect::SystemWorkspaceLaneReader,
    };
    let irrelevant =
        super::collect::collect_workspace_inventory(super::collect::WorkspaceInventoryInputs {
            root,
            kind_domain: WorkspaceRequestedKindDomain::SourceOnly,
            dirty_evidence_relevant: false,
            backend: &mut irrelevant_backend,
            lanes: &mut irrelevant_lanes,
        })
        .expect("dirty-irrelevant composition");

    assert_eq!(relevant_lanes.dirty_reads, 4);
    assert_eq!(relevant_backend.requests.len(), 6);
    assert_eq!(
        relevant.files()[0].dirty_state(),
        WorkspaceFileDirtyState::Dirty
    );
    assert_eq!(irrelevant_lanes.dirty_reads, 0);
    assert_eq!(irrelevant_backend.requests.len(), 3);
    assert_eq!(
        irrelevant.files()[0].dirty_state(),
        WorkspaceFileDirtyState::NotApplicable
    );
}
