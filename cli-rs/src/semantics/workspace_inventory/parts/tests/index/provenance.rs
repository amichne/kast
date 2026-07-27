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
    let progress = snapshot
        .stamp()
        .module_progress()
        .iter()
        .next()
        .expect("module progress");
    assert_eq!(progress.module_name().as_str(), "app");
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

    for path in [
        "src/p1/IllegalRoot.kt",
        "src/p2/Dangling.kt",
        "src/p3/Unknown.kt",
        "src/p4/MissingReason.kt",
    ] {
        let WorkspaceFileIndexState::Incompatible(incompatibilities) =
            file(&snapshot, path).index_state()
        else {
            panic!("malformed package evidence for {path} must remain incompatible");
        };
        assert_eq!(
            incompatibilities,
            &BTreeSet::from([super::model::SourceIndexIncompatibility::PackageMetadataReference,])
        );
    }

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
