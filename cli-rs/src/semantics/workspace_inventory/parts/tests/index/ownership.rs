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
    let WorkspaceFileIndexState::Incompatible(incompatibilities) = file.index_state() else {
        panic!("malformed associations must remain incompatible");
    };

    assert!(file.indexed_gradle_projects().is_empty());
    assert_eq!(file.source_sets(), &WorkspaceSourceSetEvidence::Unavailable);
    assert_eq!(
        incompatibilities,
        &BTreeSet::from([
            super::model::SourceIndexIncompatibility::MalformedGradleProjectIdentity,
            super::model::SourceIndexIncompatibility::MalformedGradleSourceSetIdentity,
        ])
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::SourceIndexIncompatible),
        2
    );
}

#[test]
fn manifest_without_a_path_prefix_is_excluded_as_global_partial_evidence() {
    let (_temp, root, fixture) = fixture();
    fixture.insert_manifest_file(7, "src/missing-prefix", "MissingPrefix.kt", true);
    fixture.seed_progress("app", "COMPLETE", 1, 1);
    fixture
        .connection()
        .execute("DELETE FROM path_prefixes WHERE prefix_id = 7", [])
        .expect("remove path prefix");

    let snapshot = snapshot(&root);

    assert!(snapshot.files().is_empty());
    assert_eq!(
        snapshot.coverage().candidate_inventory(),
        WorkspaceCoverageDimension::Partial
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::SourceIndexIncompatible),
        1
    );
}

#[test]
fn association_without_a_manifest_owner_is_global_partial_evidence() {
    let (_temp, root, fixture) = fixture();
    insert_named_metadata(&fixture, 1, "Orphan.kt", 2, "sample.orphan", None);
    fixture
        .connection()
        .execute(
            "INSERT INTO file_gradle_projects(prefix_id, filename, build_root, project_path) VALUES (1, 'Orphan.kt', '.', ':app')",
            [],
        )
        .expect("orphan project association");
    fixture.seed_progress("app", "COMPLETE", 1, 1);

    let snapshot = snapshot(&root);

    assert!(snapshot.files().is_empty());
    assert_eq!(
        snapshot.coverage(),
        WorkspaceMatchCoverage::from_dimensions(
            WorkspaceCoverageDimension::Partial,
            WorkspaceCoverageDimension::Partial,
        )
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::SourceIndexIncompatible),
        1
    );
}

#[test]
fn malformed_module_progress_is_global_partial_evidence() {
    let (_temp, root, fixture) = fixture();
    let connection = fixture.connection();
    connection
        .execute_batch("PRAGMA ignore_check_constraints=ON;")
        .expect("malformed progress mode");
    connection
        .execute(
            "INSERT INTO module_index_progress(module_name, phase2_status, indexed_file_count, total_file_count) VALUES ('app', 'UNKNOWN', 1, 1)",
            [],
        )
        .expect("malformed module progress");

    let snapshot = snapshot(&root);

    assert!(!snapshot.stamp().is_exact());
    assert_eq!(
        snapshot.coverage().candidate_inventory(),
        WorkspaceCoverageDimension::Partial
    );
    assert_eq!(
        snapshot.limitation_count(WorkspaceInventoryLimitationCode::SourceIndexIncompatible),
        1
    );
}
