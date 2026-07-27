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
fn current_producer_package_check_contract_is_accepted() {
    let (_temp, root, _fixture) = fixture();

    let read = read_workspace_index(&root);

    assert!(matches!(read, WorkspaceIndexRead::Snapshot(_)), "{read:?}");
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
    for accepted in [
        "when",
        "sample.when",
        "Ⅻvalue",
        "²value",
        "sample.non-identifier",
        "sample.`non-identifier`",
    ] {
        let parsed = super::model::KotlinPackageFqName::parse_persisted(accepted.to_string())
            .unwrap_or_else(|| panic!("semantic package name `{accepted}`"));
        assert_eq!(parsed.as_str(), accepted);
    }
    for rejected in [
        "",
        " sample",
        "sample ",
        ".sample",
        "sample.",
        "a..b",
        "a\nb",
        "sample/semantic",
        "sample\\semantic",
        "sample[semantic]",
        "sample:semantic",
        "sample.`semantic",
        "sample.semantic`",
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
